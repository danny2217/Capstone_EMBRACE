#!/usr/bin/env python3
"""EMBRACE 공통 수신 모듈 — 시리얼 프로토콜 v2 파싱 · 입력 소스 · CSV 기록/재생

모든 PC 프로그램(호흡·악력·압박 visualizer, detector, calibrate, monitor_all)이 이 파일
하나로 펌웨어(firmware/embrace_node)와 통신한다. 프로토콜이 바뀌면 여기만 고친다.
의존성: pyserial (시리얼을 쓸 때만). numpy·matplotlib 는 쓰지 않는다.

프로토콜 v2 (펌웨어 src/frame_out.cpp 와 1:1 대응)
    # EMBRACE embrace_node v2.0.0 board=ESP32-S3 ... fs=50Hz ...     헤더
    # ch chest pin=7 kind=fsr r_ref=470000 r_bias=4700000 sense_mm=10.0 ...  채널 정의
    # columns:<TAB>seq<TAB>t_ms<TAB>breath<TAB>...<TAB>flags            열 이름
    123<TAB>2460<TAB>1712<TAB>...<TAB>00000                           데이터
    # WARN ch=chest state=open mv=3   /  # INFO ch=chest state=ok mv=210
    # fs=50.00Hz avg=20000us min=...us max=...us work_max=...us drop=0

v1 (breath-sensor v1.0, WROOM-32) 의 'seq<TAB>t_ms<TAB>mv<TAB>ma_mv' 도 읽는다
→ columns 줄이 없고 필드가 4개면 breath 한 채널로 해석.

다른 스크립트에서 쓰는 법 (저장소 루트의 common/ 을 경로에 넣는다)
    import sys; from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "common"))
    from embrace_link import open_source, Timeline, CsvLogger
"""

from __future__ import annotations

import csv
import math
import queue
import random
import sys
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Iterable, Iterator

REPO_ROOT = Path(__file__).resolve().parent.parent

DEFAULT_BAUD = 115200
DEFAULT_FS = 50.0
CHANNEL_ORDER = ("breath", "grip_l", "grip_r", "chest", "back")
CHANNEL_KO = {"breath": "호흡", "grip_l": "악력 L", "grip_r": "악력 R",
              "chest": "가슴 압박", "back": "등 압박"}
HEALTH_NAME = {0: "ok", 1: "open", 2: "short"}
HEALTH_KO = {0: "정상", 1: "단선", 2: "단락/상한"}

# 펌웨어 config.h 기본값 (헤더를 아직 못 받았을 때만 쓰는 대체값)
FW_DEFAULTS = {"r_ref": 10000.0, "r_bias": 150000.0, "sense_mm": 10.0}
GRIP_R = (10_000, 150_000)        # 악력 채널 (R_ref, R_bias)
COMP_R = (470_000, 4_700_000)     # 압박 채널 (R_ref, R_bias) — 집중판 없이 감지부 단독 하중


# =============================================================================
#  데이터 모델
# =============================================================================
@dataclass(frozen=True)
class Frame:
    """데이터 한 줄. values = 채널별 mV, ma = 이동평균 열(있을 때만), flags = 채널별 상태 코드"""
    seq: int
    t_ms: int
    values: dict[str, float]
    flags: dict[str, int] = field(default_factory=dict)
    ma: dict[str, float] = field(default_factory=dict)

    def flag(self, name: str) -> int:
        return self.flags.get(name, 0)


@dataclass
class ChannelInfo:
    name: str
    params: dict[str, str]          # pin, kind, r_ref, r_bias, disc_mm, low_mv, high_mv ...

    @property
    def kind(self) -> str:
        return self.params.get("kind", "?")

    def num(self, key: str, default: float | None = None) -> float | None:
        v = self.params.get(key)
        try:
            return float(v) if v not in (None, "unknown") else default
        except ValueError:
            return default


@dataclass
class DeviceInfo:
    """기기 헤더에서 모은 정보. header_lines 는 CSV 맨 위에 그대로 남긴다."""
    header: dict[str, str] = field(default_factory=dict)
    channels: dict[str, ChannelInfo] = field(default_factory=dict)
    columns: list[str] = field(default_factory=list)
    header_lines: list[str] = field(default_factory=list)
    protocol: str = "unknown"       # "v2" / "v1" / "unknown"

    @property
    def value_columns(self) -> list[str]:
        return [c for c in self.columns[2:] if c != "flags" and not c.endswith("_ma")]

    @property
    def fs(self) -> float:
        v = self.header.get("fs", "")
        try:
            return float(v.rstrip("Hz"))
        except ValueError:
            return DEFAULT_FS

    def channel_num(self, ch: str, key: str) -> float | None:
        info = self.channels.get(ch)
        val = info.num(key) if info else None
        return val if val is not None else FW_DEFAULTS.get(key)


def _kv(tokens: Iterable[str]) -> dict[str, str]:
    out = {}
    for t in tokens:
        if "=" in t:
            k, v = t.split("=", 1)
            out[k] = v
    return out


# =============================================================================
#  줄 파서 — 상태(열 이름)를 기억한다
# =============================================================================
class LineParser:
    """feed(line) → Frame 이면 데이터, None 이면 진단/헤더/깨진 줄.

    진단 줄은 self.diag_lines 에 쌓이지 않고 feed 의 두 번째 반환값으로 돌려준다.
    """

    def __init__(self) -> None:
        self.info = DeviceInfo()
        self.bad_lines = 0           # 열 정보가 없어 해석 못 한 데이터 줄 수
        self._header_block: list[str] = []

    def feed(self, line: str) -> tuple[Frame | None, str | None]:
        line = line.strip()
        if not line:
            return None, None
        if line.startswith("#"):
            self._meta(line)
            return None, line
        return self._data(line), None

    # --- 헤더/진단 ------------------------------------------------------------
    def _meta(self, line: str) -> None:
        body = line[1:].strip()
        if body.startswith("EMBRACE"):
            self.info.header = _kv(body.split())
            self.info.header["version"] = next((t for t in body.split() if t.startswith("v")), "")
            self._header_block = [line]
        elif body.startswith("ch "):
            parts = body.split()
            if len(parts) >= 2:
                self.info.channels[parts[1]] = ChannelInfo(parts[1], _kv(parts[2:]))
            self._header_block.append(line)
        elif body.startswith(("det ", "pn ")):
            # 판단 기준·공압 설정 (펌웨어 v2.2+) — CSV 머리에 함께 남긴다
            self._header_block.append(line)
        elif body.startswith("columns:"):
            cols = body[len("columns:"):].split()
            if cols[:2] == ["seq", "t_ms"]:
                self.info.columns = cols
                self.info.protocol = "v2"
            self._header_block.append(line)
            self.info.header_lines = list(self._header_block)

    # --- 데이터 ---------------------------------------------------------------
    def _data(self, line: str) -> Frame | None:
        parts = line.split("\t")
        cols = self.info.columns
        if cols:
            if len(parts) != len(cols):
                self.bad_lines += 1
                return None
            try:
                seq, t_ms = int(parts[0]), int(parts[1])
                values, ma, flags = {}, {}, {}
                for name, raw in zip(cols[2:], parts[2:]):
                    if name == "flags":
                        continue
                    if name.endswith("_ma"):
                        ma[name[:-3]] = float(raw)
                    else:
                        values[name] = float(raw)
                if "flags" in cols:
                    fl = parts[cols.index("flags")]
                    names = [c for c in cols[2:] if c != "flags" and not c.endswith("_ma")]
                    flags = {n: int(ch) for n, ch in zip(names, fl) if ch.isdigit()}
                return Frame(seq, t_ms, values, flags, ma)
            except ValueError:
                self.bad_lines += 1
                return None
        # 열 정보가 없을 때: v1 형식(seq, t_ms, mv, ma_mv) 만 해석한다
        if len(parts) == 4:
            try:
                f = Frame(int(parts[0]), int(parts[1]), {"breath": float(parts[2])},
                          {}, {"breath": float(parts[3])})
                self.info.protocol = "v1"
                return f
            except ValueError:
                pass
        self.bad_lines += 1
        return None


# =============================================================================
#  시간축 / 유실 추적 (모든 visualizer 공통)
# =============================================================================
class Timeline:
    """seq 로 유실·리셋을 세고, 기기 시각(t_ms)으로 경과 시간(s)을 만든다.

    update() 반환: (elapsed_s, gap, reset)
      gap   : 건너뛴 프레임 수 (0 이면 정상)
      reset : 보드가 리셋되어 seq 가 되돌아감 → 필터 등을 새로 시작해야 함
    """

    def __init__(self, fs: float = DEFAULT_FS) -> None:
        self.fs = fs
        self.t0_ms: int | None = None
        self.last_seq: int | None = None
        self.t = 0.0
        self.received = 0
        self.dropped = 0
        self.resets = 0

    def update(self, f: Frame) -> tuple[float, int, bool]:
        gap, reset = 0, False
        if self.last_seq is not None:
            if f.seq <= self.last_seq:
                reset = True
                self.resets += 1
                self.t0_ms = None
            else:
                gap = f.seq - self.last_seq - 1
                self.dropped += gap
        self.last_seq = f.seq
        if self.t0_ms is None:
            # 첫 샘플이면 0 s, 리셋 후면 직전 시각 + 1주기에서 이어 그린다
            resume_ms = int(round((self.t + 1.0 / self.fs) * 1000)) if self.received else 0
            self.t0_ms = (f.t_ms - resume_ms) & 0xFFFFFFFF
        self.t = ((f.t_ms - self.t0_ms) & 0xFFFFFFFF) / 1000.0   # millis() 32비트 순환 대응
        self.received += 1
        return self.t, gap, reset


# =============================================================================
#  입력 소스 (스레드) — 실제 시리얼 / 모의 장치
# =============================================================================
class Source(threading.Thread):
    """프레임은 self.q, 진단 줄은 self.diag 로 내보낸다. 헤더 정보는 self.parser.info."""

    def __init__(self) -> None:
        super().__init__(daemon=True)
        self.q: queue.Queue[Frame] = queue.Queue()
        self.diag: queue.Queue[str] = queue.Queue()
        self.stop_evt = threading.Event()
        self.parser = LineParser()
        self.error: str | None = None
        self.label = ""
        self.last_fs_line = ""

    @property
    def info(self) -> DeviceInfo:
        return self.parser.info

    def stop(self) -> None:
        self.stop_evt.set()

    def _on_line(self, line: str) -> None:
        frame, meta = self.parser.feed(line)
        if frame is not None:
            self.q.put(frame)
        elif meta is not None:
            if meta.startswith("# fs="):
                self.last_fs_line = meta
            self.diag.put(meta)

    def wait_header(self, timeout: float = 6.0) -> bool:
        """열 이름(columns) 을 받을 때까지 기다린다. 펌웨어는 5 s 마다 헤더를 보낸다."""
        t_end = time.monotonic() + timeout
        while time.monotonic() < t_end and not self.error:
            if self.info.columns or self.info.protocol == "v1":
                return True
            time.sleep(0.05)
        return bool(self.info.columns)


class SerialSource(Source):
    def __init__(self, port: str, baud: int = DEFAULT_BAUD) -> None:
        super().__init__()
        import serial  # 지연 import: --replay 모드는 pyserial 없이도 동작

        self.label = f"{port} @ {baud}"
        try:
            self.ser = serial.Serial(port, baud, timeout=0.2)
        except serial.SerialException as e:
            print(f"\n[오류] 포트를 열지 못했습니다: {e}")
            print("  - 포트 이름 확인 (--list), Arduino IDE 의 시리얼 모니터/플로터가 닫혀 있는지 확인")
            list_ports()
            sys.exit(1)
        # ESP32 는 포트를 열면 리셋될 수 있다 → 부팅 로그를 흘려보낸 뒤 헤더를 요청
        time.sleep(1.5)
        self.ser.reset_input_buffer()
        self.request_header()

    def request_header(self) -> None:
        try:
            self.ser.write(b"h")
        except Exception:
            pass

    def run(self) -> None:
        try:
            while not self.stop_evt.is_set():
                raw = self.ser.readline()
                if raw:
                    self._on_line(raw.decode("utf-8", errors="ignore"))
        except Exception as e:  # USB 분리 등
            self.error = f"시리얼 연결 끊김: {e}"
        finally:
            try:
                self.ser.close()
            except Exception:
                pass


class DemoSource(Source):
    """보드 없이 화면·저장 기능을 점검하는 모의 장치. 실제 펌웨어와 같은 텍스트 줄을 만든다."""

    def __init__(self, device: "DemoDevice | None" = None, realtime: bool = True) -> None:
        super().__init__()
        self.device = device or DemoDevice()
        self.realtime = realtime
        self.label = "DEMO (모의 데이터)"

    def run(self) -> None:
        for line in self.device.lines(realtime=self.realtime, stop=self.stop_evt):
            self._on_line(line)
        self.error = self.error or ("모의 데이터 끝" if not self.stop_evt.is_set() else None)


def open_source(port: str | None, baud: int = DEFAULT_BAUD, demo: bool = False,
                device: "DemoDevice | None" = None) -> Source:
    if demo:
        return DemoSource(device)
    if not port:
        sys.exit("[오류] --port 또는 --demo 가 필요합니다")
    return SerialSource(port, baud)


def list_ports() -> None:
    try:
        from serial.tools import list_ports as lp
    except ImportError:
        print("pyserial 이 필요합니다: pip install -r requirements.txt")
        return
    ports = list(lp.comports())
    if not ports:
        print("연결된 시리얼 포트가 없습니다. (USB 케이블이 데이터 전송용인지, 드라이버 설치 확인)")
        return
    print("사용 가능한 포트:")
    for p in ports:
        print(f"  {p.device:<10} {p.description}")


# =============================================================================
#  모의 장치 — 펌웨어와 같은 줄을 만든다 (프로토콜·파서 시험용 겸 데모)
# =============================================================================
FSR_K_OHM_G = 1.05e7    # 모의 모델: R_fsr ≈ K / F  (21 g → 500 kΩ, 32 g → 328 kΩ — 계획서 예시값)
FSR_MIN_G = 10.0        # 모의 모델의 응답 하한 (사양: 20 g 미만 어딘가)


def fsr_mv(grams: float, r_ref: float = 10000, r_bias: float | None = 150000,
           vcc_mv: float = 3300) -> float:
    """ZD10-100 + 분압 회로의 대략적인 모델 (모의 데이터용, 실측 보정 아님).

    FSR 저항은 하중에 거의 반비례한다고 본다 (R ≈ K / F). 응답 하한 미만은 개방(10 GΩ).
    출력 = 3.3 V × R_ref / (R_ref + (R_fsr ∥ R_bias))
    """
    r_fsr = 1e10 if grams < FSR_MIN_G else FSR_K_OHM_G / grams
    r_eff = r_fsr if not r_bias else 1.0 / (1.0 / r_fsr + 1.0 / r_bias)
    return min(vcc_mv * r_ref / (r_ref + r_eff), 3140.0)


class DemoDevice:
    """시나리오 모의 장치. load 를 바꾸면(calibrate 의 --demo) 압박 채널이 따라 움직인다.

    기본 시나리오 (duration_s 동안 반복)
      호흡   : 약 15 회/분 + 기저선 드리프트, 40 s 근처 움직임 잡음
      악력   : 6 s 마다 좌/우 번갈아 2 s 쥐기, 30 s 근처 한 번은 포화될 만큼 세게
      압박   : 10 s 부터 8 s 가압 → 감지부 약 30 g(≈28 mmHg, Ø10 mm) 유지(크리프 약간) → 48 s 배기
      고장   : 60~62 s 가슴 압박 케이블 분리 (단선 경고)
    """

    def __init__(self, fs: float = DEFAULT_FS, channels: Iterable[str] = CHANNEL_ORDER,
                 duration_s: float | None = None, seed: int = 7, scenario: bool = True) -> None:
        self.fs = fs
        self.channels = [c for c in CHANNEL_ORDER if c in set(channels)]
        self.duration_s = duration_s
        self.rng = random.Random(seed)
        self.scenario = scenario
        self.load_g = {"chest": 0.0, "back": 0.0, "grip_l": 0.0, "grip_r": 0.0}
        self.unplugged: set[str] = set()
        self._phase = 0.0

    def set_load(self, ch: str, grams: float) -> None:
        self.load_g[ch] = grams

    def header_lines(self) -> list[str]:
        out = [f"# EMBRACE embrace_node v2.0.0 board=DEMO core=0.0.0 fs={self.fs:.0f}Hz "
               "oversample=8 discard=1 ma_window=10 send_ma=0"]
        pins = {"breath": 4, "grip_l": 5, "grip_r": 6, "chest": 7, "back": 8}
        for c in self.channels:
            if c == "breath":
                out.append("# ch breath pin=4 kind=pullup r_pu=unknown low_mv=60:short high_mv=3000:open")
            else:
                rr, rb = COMP_R if c in ("chest", "back") else GRIP_R
                sense = " sense_mm=10.0" if c in ("chest", "back") else ""
                out.append(f"# ch {c} pin={pins[c]} kind=fsr r_ref={rr} r_bias={rb} "
                           f"open_check=on{sense} low_mv=80:open high_mv=3000:short")
        out.append("# columns:\t" + "\t".join(["seq", "t_ms", *self.channels, "flags"]))
        return out

    def _scenario_loads(self, t: float) -> None:
        if not self.scenario:
            return
        cyc = t % 6.0
        k = int(t // 6.0)
        strong = 30 <= t < 36
        g = (750 if strong else 320) if cyc < 2.0 else 0.0
        self.load_g["grip_l"] = g if k % 2 == 0 else 0.0
        self.load_g["grip_r"] = g if k % 2 == 1 else 0.0
        tt = t % 70.0
        hold_g = 30.0                                     # 감지부 하중 ≈ 28 mmHg (Ø10 mm)
        if tt < 10:
            p = 0.0
        elif tt < 18:
            p = hold_g * (tt - 10) / 8
        elif tt < 48:
            p = hold_g * (1 - 0.04 * (tt - 18) / 30)     # 크리프: 30 s 동안 4% 감소
        else:
            p = 0.0
        self.load_g["chest"] = p
        self.load_g["back"] = p * 0.85
        self.unplugged = {"chest"} if 60 <= tt < 62 else set()

    def _value(self, ch: str, t: float) -> tuple[float, int]:
        rng = self.rng
        if ch == "breath":
            mv = 900 + 45 * (math.sin(self._phase) + 0.25 * math.sin(2 * self._phase + 0.6))
            mv += 15 * math.sin(2 * math.pi * t / 90) + rng.gauss(0, 4)
            if self.scenario and 40 < t % 70 < 41.5:
                mv += rng.gauss(0, 40)
            return mv, 0
        if ch in self.unplugged:
            return max(0.0, rng.gauss(2, 1)), 1
        rr, rb = COMP_R if ch in ("chest", "back") else GRIP_R
        mv = fsr_mv(self.load_g.get(ch, 0.0), rr, rb) + rng.gauss(0, 3)
        return mv, 0

    def lines(self, realtime: bool = True, stop: threading.Event | None = None) -> Iterator[str]:
        period = 1.0 / self.fs
        t0 = time.perf_counter()
        seq = 0
        hold = {c: 0 for c in self.channels}
        state = {c: 0 for c in self.channels}
        yield from self.header_lines()
        while not (stop and stop.is_set()):
            t = seq * period
            if self.duration_s is not None and t >= self.duration_s:
                return
            if seq and seq % int(5 * self.fs) == 0:
                yield from self.header_lines()
            self._phase += 2 * math.pi * (15 + 2 * math.sin(2 * math.pi * t / 60)) / 60 * period
            self._scenario_loads(t)
            vals, flags = [], []
            for c in self.channels:
                mv, fault = self._value(c, t)
                hold[c] = hold[c] + 1 if fault else 0
                new = 1 if hold[c] >= 25 else (0 if not fault else state[c])
                if new != state[c]:
                    state[c] = new
                    yield (f"# WARN ch={c} state=open mv={mv:.0f}" if new
                           else f"# INFO ch={c} state=ok mv={mv:.0f}")
                vals.append(str(int(round(max(0.0, mv)))))
                flags.append(str(state[c]))
            yield "\t".join([str(seq), str(int(t * 1000)), *vals, "".join(flags)])
            seq += 1
            if seq % int(self.fs) == 0:
                yield (f"# fs={self.fs:.2f}Hz avg={int(1e6 / self.fs)}us min={int(1e6 / self.fs) - 9}us "
                       f"max={int(1e6 / self.fs) + 9}us work_max=1250us drop=0")
            if realtime:
                sleep = t0 + seq * period - time.perf_counter()
                if sleep > 0:
                    time.sleep(sleep)


# =============================================================================
#  CSV 기록 / 읽기
# =============================================================================
def timestamp() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


class CsvLogger:
    """프레임 전체(모든 채널 + flags)와 앱별 추가 열을 기록한다.

    파일 맨 위(및 처음 받은 시점)에 기기 헤더를 '# ' 주석 줄로 남긴다 → 회로 상수·펌웨어
    버전이 데이터와 함께 보존된다. 열은 첫 프레임을 받을 때 정해진다.
    """

    def __init__(self, path: Path, info: DeviceInfo, extra_cols: Iterable[str] = (),
                 note: str = "") -> None:
        self.path = path
        self.info = info
        self.extra = list(extra_cols)
        self.note = note
        self._f = None
        self._w = None
        self._cols: list[str] = []
        self._header_written = False
        self.rows = 0

    def _open(self, frame: Frame) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._f = open(self.path, "w", newline="", encoding="utf-8")
        self._f.write(f"# recorded {datetime.now().isoformat(timespec='seconds')} {self.note}\n")
        self._write_header()
        self._cols = list(frame.values)
        ma_cols = [f"{c}_ma" for c in frame.ma]
        self._w = csv.writer(self._f)
        self._w.writerow(["host_time", "seq", "t_ms", "elapsed_s", *self._cols, *ma_cols,
                          "flags", *self.extra])

    def _write_header(self) -> None:
        if self._header_written or not self.info.header_lines:
            return
        for line in self.info.header_lines:
            self._f.write(line.rstrip() + "\n")
        self._header_written = True

    def write(self, frame: Frame, elapsed: float, extra: Iterable[object] = ()) -> None:
        if self._f is None:
            self._open(frame)
        elif not self._header_written:
            self._write_header()
        flags = "".join(str(frame.flags.get(c, 0)) for c in self._cols)
        self._w.writerow([datetime.now().isoformat(timespec="milliseconds"), frame.seq, frame.t_ms,
                          f"{elapsed:.3f}", *(_fmt(frame.values.get(c)) for c in self._cols),
                          *(_fmt(v) for v in frame.ma.values()), flags,
                          *extra])
        self.rows += 1

    def close(self) -> None:
        if self._f:
            self._f.close()
            self._f = None


def _fmt(v: float | None) -> str:
    if v is None:
        return ""
    return str(int(v)) if float(v).is_integer() else f"{v:.2f}"


@dataclass
class Recording:
    """read_csv() 결과. rows 는 dict 의 list, meta 는 '#' 주석 줄들."""
    path: Path
    rows: list[dict[str, str]]
    meta: list[str]
    info: DeviceInfo

    def col(self, name: str, default: float = math.nan) -> list[float]:
        out = []
        for r in self.rows:
            try:
                out.append(float(r[name]))
            except (KeyError, TypeError, ValueError):
                out.append(default)
        return out

    def has(self, name: str) -> bool:
        return bool(self.rows) and name in self.rows[0]

    def flags(self, name: str) -> list[int]:
        """flags 열에서 채널 하나의 상태 코드 목록"""
        cols = self.info.value_columns or [c for c in CHANNEL_ORDER if self.has(c)]
        if name not in cols:
            return [0] * len(self.rows)
        i = cols.index(name)
        out = []
        for r in self.rows:
            s = (r.get("flags") or "").lstrip("'")
            out.append(int(s[i]) if len(s) > i and s[i].isdigit() else 0)
        return out


def read_csv(path: Path) -> Recording:
    """visualizer 가 남긴 CSV 를 읽는다. v1(breath_*.csv, mv 열) 과 v2 모두 지원."""
    meta: list[str] = []
    body: list[str] = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            (meta if line.startswith("#") else body).append(line)
    parser = LineParser()
    for m in meta:
        parser.feed(m)
    rows = list(csv.DictReader(body))
    info = parser.info
    if rows and "breath" not in rows[0] and "mv" in rows[0]:      # v1 호흡 CSV
        for r in rows:
            r["breath"] = r["mv"]
        info.protocol = "v1"
    if rows and not info.columns:
        names = [c for c in CHANNEL_ORDER if c in rows[0]]
        info.columns = ["seq", "t_ms", *names, "flags"]
    return Recording(Path(path), rows, meta, info)


# =============================================================================
#  작은 통계 도구 (numpy 없이)
# =============================================================================
def mean(xs: Iterable[float]) -> float:
    xs = [x for x in xs if not math.isnan(x)]
    return sum(xs) / len(xs) if xs else math.nan


def stdev(xs: Iterable[float]) -> float:
    xs = [x for x in xs if not math.isnan(x)]
    if len(xs) < 2:
        return math.nan
    m = sum(xs) / len(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1))


def grams_to_mmhg(grams: float, sense_mm: float) -> float:
    """감지부 하중(g) → 등가 압력(mmHg). P = m·g / (π·(d/2)²), d = 감지부 지름"""
    area_m2 = math.pi * (sense_mm / 2000.0) ** 2
    return grams / 1000.0 * 9.80665 / area_m2 / 133.322


def mmhg_to_grams(mmhg: float, sense_mm: float) -> float:
    area_m2 = math.pi * (sense_mm / 2000.0) ** 2
    return mmhg * 133.322 * area_m2 / 9.80665 * 1000.0


if __name__ == "__main__":
    # 간단 점검: 모의 장치 2초 분량을 파싱해 본다
    p = LineParser()
    n = 0
    for ln in DemoDevice(duration_s=2).lines(realtime=False):
        fr, _ = p.feed(ln)
        n += fr is not None
    print(f"프레임 {n}개, 열 {p.info.columns}, 채널 {list(p.info.channels)}")
