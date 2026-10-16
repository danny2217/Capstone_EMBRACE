#!/usr/bin/env python3
"""Air-Rhythm 실시간 호흡 파형 시각화 (FR-02, FR-03)

ESP32(sensor_reader.ino)가 보내는 'seq<TAB>t_ms<TAB>mv<TAB>ma_mv' 줄을 받아
실시간 그래프로 그리고, 동시에 CSV 로 기록한다.

그래프는 두 칸으로 나뉜다.
  ① 측정값      : 기기가 보낸 mV 그대로 (오버샘플링 평균)
  ② 필터링 값   : 2차 Butterworth 대역통과 0.08~0.7 Hz (PC 에서 계산, 인과적)
                  드리프트(자세 변화)와 고주파 노이즈를 제거해 0 mV 를 중심으로
                  진동하는 하나의 매끈한 곡선이 된다.
  필터는 원본 Air-Rhythm 레포 breath/filters.py 의 BandpassButter2 를 그대로 옮긴 것이다.

흡기/호기 판별 (② 그래프 위에 표시)
  필터링 값에 원본 레포 breath/detectors/slope.py 의 SlopeDetector 를 적용한다.
    빨간 선 + ▲ = 흡기 구간 / 흡기 시작 확정 지점
    초록 선 + ▼ = 호기 구간 / 호기 시작 확정 지점
    회색 선     = 판정 보류 (시작 후 12초 안정화, 또는 신호가 너무 작음)
  "상승 = 흡기" 가 기본이다. 센서가 반대로 동작하면 --invert 를 붙인다.
  실시간 경로와 --replay 경로가 같은 클래스를 쓰므로 두 결과가 구조적으로 동일하다.

실행 모드
  실시간    python visualizer.py --port COM5          [--invert]
  포트 목록  python visualizer.py --list
  모의 신호  python visualizer.py --demo            (보드 없이 화면/저장 기능 점검)
  CSV 재생  python visualizer.py --replay ../data/sample_breath_log.csv
                                  [--start 10 --duration 30] [--no-show]
            → 보고서용 PNG 3장 생성 (재현 가능)
               *_raw.png (측정값만) / *_filtered.png (필터링 값만) / *_both.png (2단)

실시간 창 단축키
  s : 현재 화면을 report_images/ 에 PNG 저장 (TC-04)
  p : 일시정지/재개 (정지 중에도 수신·CSV 기록은 계속됨)
  q : 종료 (종료 시 마지막 화면 자동 저장)

구조
  [수신 스레드] 시리얼 readline → 파싱 → queue
  [GUI 스레드 ] 타이머(50 ms)마다 queue 를 비우며 버퍼·CSV 갱신 → 다시 그리기
  GUI 렌더링이 느려져도 수신 스레드는 막히지 않으므로 샘플 유실이 생기지 않는다.
"""

from __future__ import annotations

import argparse
import csv
import math
import queue
import random
import sys
import threading
import time
from collections import deque
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data"
IMG_DIR = ROOT / "report_images"

DEFAULT_BAUD = 115200
DEFAULT_FS = 50.0        # 펌웨어 PERIOD_MS=20 과 일치
DEFAULT_WINDOW_S = 20.0  # 화면에 보이는 시간 폭
DEMO_MA_WINDOW = 10      # 펌웨어 MA_WINDOW 와 동일

# 펌웨어 sensor_app.cpp 의 OPEN_MV / SHORT_MV 와 같은 값
SAT_HIGH_MV = 3000   # 이상이면 ADC 상한 포화 (단선 또는 센서 저항 >> 풀업)
SAT_LOW_MV = 60      # 이하면 단락
SAT_CHECK_N = 25     # 최근 0.5 s 전부가 범위 밖일 때만 경고

# 대역통과 차단주파수 — 원본 레포 breath/config.py 와 동일
HP_HZ = 0.08   # 고역: 약 5 bpm 이하 제거 (기저선 드리프트)
LP_HZ = 0.70   # 저역: 약 42 bpm 이상 제거 (노이즈·떨림)

RAW_COLOR = "#7f8a99"
FILT_COLOR = "#1f5fbf"
INHALE_COLOR = "#d62728"   # 흡기 = 빨강 (원본 레포 config.py 와 동일)
EXHALE_COLOR = "#2ca02c"   # 호기 = 초록
UNKNOWN_COLOR = "#a0a7b1"  # 판정 보류 = 회색


# =============================================================================
#  2차 Butterworth 대역통과 — 한 샘플씩 처리 (미래 샘플 미참조 = 실시간 가능)
# =============================================================================
class Biquad:
    """Direct Form II transposed 2차 IIR 한 단."""

    def __init__(self, b0, b1, b2, a1, a2):
        self.b0, self.b1, self.b2, self.a1, self.a2 = b0, b1, b2, a1, a2
        self.s1 = self.s2 = 0.0
        self.ready = False

    def _init_steady(self, x):
        # 입력 x 가 계속 들어왔던 것처럼 상태를 맞춘다 → 시작 순간의 큰 튐(과도응답) 제거
        y = (self.b0 + self.b1 + self.b2) / (1.0 + self.a1 + self.a2) * x
        self.s2 = self.b2 * x - self.a2 * y
        self.s1 = self.b1 * x - self.a1 * y + self.s2
        self.ready = True

    def update(self, x):
        if not self.ready:
            self._init_steady(x)
        y = self.b0 * x + self.s1
        self.s1 = self.b1 * x - self.a1 * y + self.s2
        self.s2 = self.b2 * x - self.a2 * y
        return y


def _butter2(cutoff_hz, fs, kind):
    """2차 Butterworth 계수 (scipy.signal.butter(2, fc, kind) 와 동일)."""
    k = math.tan(math.pi * cutoff_hz / fs)
    norm = 1.0 / (1.0 + math.sqrt(2.0) * k + k * k)
    a1 = 2.0 * (k * k - 1.0) * norm
    a2 = (1.0 - math.sqrt(2.0) * k + k * k) * norm
    if kind == "low":
        b0 = k * k * norm
        return Biquad(b0, 2.0 * b0, b0, a1, a2)
    return Biquad(norm, -2.0 * norm, norm, a1, a2)


class BandpassButter2:
    """고역통과 biquad → 저역통과 biquad. 상태는 biquad 2개뿐."""

    def __init__(self, fs=DEFAULT_FS, hp_hz=HP_HZ, lp_hz=LP_HZ):
        self.hp = _butter2(hp_hz, fs, "high")
        self.lp = _butter2(lp_hz, fs, "low")

    def update(self, x):
        return self.lp.update(self.hp.update(x))


# =============================================================================
#  흡기/호기 판별 — 원본 레포 breath/detectors/slope.py 의 SlopeDetector 이식
# =============================================================================
#  핵심 아이디어: "극점(꼭대기/바닥)을 찾는" 게 아니라 "기울기 부호가 확실히 바뀌었는가"
#  로 전환을 확정한다. 실시간에서는 지금이 꼭대기인지 미래를 보지 않고는 알 수 없기
#  때문이다. 꼭대기를 지나 기울기가 확실히 음(-)으로 돌아선 순간 = 호기 시작 확정.
#
#  오검출 방지 장치 (단순 "극값에서 방향이 바뀌면 전환" 이 아닌 이유)
#    1) 적응형 데드밴드: |기울기| 가 최근 평균의 25% (최소 1 mV/s) 를 넘어야 인정
#    2) 최소 위상시간 1.2 s: 전환 직후 반대 전환 금지 → 꼭대기의 작은 요철(double hump) 무시
#    3) 중점 게이트: 흡기 전환은 파형 중간선 아래에서만, 호기 전환은 위에서만 허용
#    4) 진폭 하한 5 mV: 신호가 너무 작으면(숨 참기·센서 이탈) 판정 보류
#    5) 안정화 12 s: 필터가 자리 잡기 전에는 판정 보류
SLOPE_TAU_S = 0.08    # 기울기 평활 시상수 (지연 ↔ 노이즈)
AVG_TAU_S = 8.0       # 평균 |기울기| 추정 시상수
K_SLOPE = 0.25        # 데드밴드 = K_SLOPE × 평균 |기울기|
SLOPE_FLOOR = 1.0     # 데드밴드 하한 (mV/s)
MIN_PHASE_S = 1.2     # 전환 후 반대 전환 금지 시간 (s) → 최대 약 25 bpm 까지 대응
MIN_AMP = 5.0         # 최근 진폭(p-p) 하한 (mV)
ENV_DECAY_S = 6.0     # 진폭 포락선 완화 시상수 (s)
SETTLE_S = 12.0       # 시작 후 판정 보류 (s)


class SlopeDetector:
    """평활 기울기의 부호 전환으로 흡기/호기 시작을 확정하는 인과적 검출기.

    update(t, y) 는 샘플 하나마다 호출하며, 전환이 확정되면 이벤트 dict 를 돌려준다.
      {"type": "inhale"|"exhale", "t": 확정 시각, "y": 확정 시 값,
       "ext_t": 실제 골/마루 시각, "ext_y": 골/마루 값}   검출 지연 = t - ext_t
    phase: +1 흡기 중 / -1 호기 중 / 0 판정 보류
    """

    def __init__(self, polarity=+1):
        self.polarity = polarity
        self.t0 = self.last_t = self.y_prev = None
        self.sd = 0.0
        self.avg_abs_sd = 0.0
        self.env_hi = self.env_lo = None
        self.phase = 0
        self.ext_val = self.ext_t = self.ext_yraw = None
        self.last_transition_t = None
        self.inhale_times: list[float] = []
        self.delays: list[float] = []

    def amplitude(self):
        return 0.0 if self.env_hi is None else self.env_hi - self.env_lo

    def _seed(self, y, t, y_raw):
        self.ext_val, self.ext_t, self.ext_yraw = y, t, y_raw

    def update(self, t, y_raw):
        y = self.polarity * y_raw
        if self.t0 is None:
            self.t0 = self.last_t = self.last_transition_t = t
            self.y_prev = y
            self.env_hi = self.env_lo = y
            self._seed(y, t, y_raw)
            return None
        dt = max(t - self.last_t, 1e-6)
        self.last_t = t

        # 기울기 → 평활 → 평균 |기울기|
        d = (y - self.y_prev) / dt
        self.y_prev = y
        self.sd += dt / (SLOPE_TAU_S + dt) * (d - self.sd)
        self.avg_abs_sd += dt / (AVG_TAU_S + dt) * (abs(self.sd) - self.avg_abs_sd)

        # 진폭 포락선 (서서히 좁혀지는 최고/최저선)
        amp0 = self.amplitude()
        decay = (amp0 if amp0 > 0 else 1.0) * dt / ENV_DECAY_S
        self.env_hi = max(y, self.env_hi - decay)
        self.env_lo = min(y, self.env_lo + decay)
        amp = self.amplitude()

        sth = max(K_SLOPE * self.avg_abs_sd, SLOPE_FLOOR)
        if t - self.t0 < SETTLE_S or amp < MIN_AMP:          # 판정 보류
            self._seed(y, t, y_raw)
            self.phase = 0
            return None

        can_switch = (t - self.last_transition_t) >= MIN_PHASE_S
        mid = 0.5 * (self.env_hi + self.env_lo)
        gate_open = (y < mid) if self.phase == -1 else (y > mid)

        if self.phase == 0:                                   # 첫 방향 잡기
            if self.sd > sth:
                self.phase = +1
            elif self.sd < -sth:
                self.phase = -1
            else:
                return None
            self._seed(y, t, y_raw)
            self.last_transition_t = t
            return None

        if self.phase == -1:                                  # 호기 중 → 골 추적
            if y < self.ext_val:
                self._seed(y, t, y_raw)
            if can_switch and gate_open and self.sd > sth:
                return self._switch(+1, "inhale", t, y, y_raw)
        else:                                                 # 흡기 중 → 마루 추적
            if y > self.ext_val:
                self._seed(y, t, y_raw)
            if can_switch and gate_open and self.sd < -sth:
                return self._switch(-1, "exhale", t, y, y_raw)
        return None

    def _switch(self, new_phase, kind, t, y, y_raw):
        ev = {"type": kind, "t": t, "y": y_raw, "ext_t": self.ext_t, "ext_y": self.ext_yraw}
        self.delays.append(t - self.ext_t)
        if kind == "inhale":
            self.inhale_times.append(t)
        self.phase = new_phase
        self.last_transition_t = t
        self._seed(y, t, y_raw)
        return ev

    def bpm(self):
        """최근 흡기 간격(1.4~12 s) 5개의 중앙값 → 분당 호흡수."""
        iv = [b - a for a, b in zip(self.inhale_times, self.inhale_times[1:]) if 1.4 <= b - a <= 12]
        if not iv:
            return None
        iv = sorted(iv[-5:])
        return 60.0 / iv[len(iv) // 2]


PHASE_NAME = {+1: "inhale", -1: "exhale", 0: "unknown"}
PHASE_KO = {+1: "흡기 ▲", -1: "호기 ▼", 0: "판정 보류"}


def draw_phase_line(ax, t, y, phase, lw=1.8):
    """필터링 곡선을 상태별 색으로 그린다. 경계 샘플은 양쪽에 넣어 선이 끊기지 않게 한다."""
    import numpy as np
    t = np.asarray(t, float); y = np.asarray(y, float); ph = np.asarray(phase)
    lines = []
    for code, color in ((+1, INHALE_COLOR), (-1, EXHALE_COLOR), (0, UNKNOWN_COLOR)):
        m = ph == code
        m = m | np.concatenate(([False], m[:-1]))            # 다음 샘플까지 이어 그리기
        lines.append(ax.plot(t, np.where(m, y, np.nan), color=color, lw=lw)[0])
    return lines


def phase_legend(ax):
    from matplotlib.lines import Line2D
    h = [Line2D([], [], color=INHALE_COLOR, lw=2, marker="^", ms=7, label="흡기 (▲ 흡기 시작 확정)"),
         Line2D([], [], color=EXHALE_COLOR, lw=2, marker="v", ms=7, label="호기 (▼ 호기 시작 확정)"),
         Line2D([], [], color=UNKNOWN_COLOR, lw=2, label="판정 보류")]
    ax.legend(handles=h, loc="upper left", frameon=False, fontsize=9, ncol=3)


# =============================================================================
#  데이터 모델 / 파싱
# =============================================================================
@dataclass(frozen=True)
class Sample:
    seq: int
    t_ms: int
    mv: float
    ma: float


def parse_sample(line: str) -> Sample | None:
    """데이터 줄이면 Sample, 아니면 None. '#' 진단 줄과 깨진 줄은 걸러진다."""
    if not line or line.startswith("#"):
        return None
    parts = line.split("\t")
    if len(parts) != 4:
        return None
    try:
        return Sample(int(parts[0]), int(parts[1]), float(parts[2]), float(parts[3]))
    except ValueError:
        return None


# =============================================================================
#  입력 소스 (스레드) — 실제 시리얼 / 모의 신호
# =============================================================================
class Source(threading.Thread):
    """샘플은 self.q 로, 진단 메시지는 self.diag 로 내보낸다."""

    def __init__(self) -> None:
        super().__init__(daemon=True)
        self.q: queue.Queue[Sample] = queue.Queue()
        self.diag: queue.Queue[str] = queue.Queue()
        self.stop_evt = threading.Event()
        self.error: str | None = None
        self.label = ""

    def stop(self) -> None:
        self.stop_evt.set()


class SerialSource(Source):
    def __init__(self, port: str, baud: int) -> None:
        super().__init__()
        import serial  # 지연 import: --replay 모드는 pyserial 없이도 동작

        self.label = f"{port} @ {baud}"
        try:
            self.ser = serial.Serial(port, baud, timeout=0.5)
        except serial.SerialException as e:
            print(f"\n[오류] 포트를 열지 못했습니다: {e}")
            print("  - 포트 이름 확인, Arduino IDE 의 시리얼 모니터/플로터가 닫혀 있는지 확인")
            list_ports()
            sys.exit(1)
        # ESP32 는 포트를 열면 리셋된다 → 부팅 로그가 지나갈 때까지 대기 후 버퍼 비움
        time.sleep(1.5)
        self.ser.reset_input_buffer()

    def run(self) -> None:
        try:
            while not self.stop_evt.is_set():
                raw = self.ser.readline()
                if not raw:
                    continue
                line = raw.decode("utf-8", errors="ignore").strip()
                s = parse_sample(line)
                if s is not None:
                    self.q.put(s)
                elif line.startswith("#"):
                    self.diag.put(line)
        except Exception as e:  # USB 분리 등
            self.error = f"시리얼 연결 끊김: {e}"
        finally:
            try:
                self.ser.close()
            except Exception:
                pass


class DemoSource(Source):
    """보드 없이 화면·저장 기능을 점검하기 위한 모의 호흡 신호 (약 15 bpm)."""

    def __init__(self, fs: float) -> None:
        super().__init__()
        self.fs = fs
        self.label = "DEMO (모의 신호)"

    def run(self) -> None:
        period = 1.0 / self.fs
        buf: deque[float] = deque(maxlen=DEMO_MA_WINDOW)
        rng = random.Random(7)
        seq, t0, phase = 0, time.perf_counter(), 0.0
        self.diag.put("# AirRhythm DEMO fs=%.0fHz" % self.fs)
        while not self.stop_evt.is_set():
            t = seq * period
            bpm = 15 + 2 * math.sin(2 * math.pi * t / 60)       # 호흡수 13~17 회/분 완만한 변동
            phase += 2 * math.pi * bpm / 60 * period            # 위상은 적분해야 주기가 안 흔들린다
            breath = 45 * (math.sin(phase) + 0.25 * math.sin(2 * phase + 0.6))
            drift = 15 * math.sin(2 * math.pi * t / 90)          # 자세 변화로 인한 기저선 이동
            mv = 900 + breath + drift + rng.gauss(0, 6)
            if 40 < t % 60 < 41.5:                                # 주기적 움직임 아티팩트
                mv += rng.gauss(0, 40)
            mv = round(mv)
            buf.append(mv)
            ma = round(sum(buf) / len(buf))
            self.q.put(Sample(seq, int(t * 1000), mv, ma))
            seq += 1
            # 절대 시각 기준 대기 (누적 오차 없음)
            sleep = t0 + seq * period - time.perf_counter()
            if sleep > 0:
                time.sleep(sleep)


def list_ports() -> None:
    try:
        from serial.tools import list_ports as lp
    except ImportError:
        print("pyserial 이 필요합니다: pip install -r requirements.txt")
        return
    ports = list(lp.comports())
    if not ports:
        print("연결된 시리얼 포트가 없습니다. (USB 케이블이 데이터 전송용인지, CP210x 드라이버 설치 확인)")
        return
    print("사용 가능한 포트:")
    for p in ports:
        print(f"  {p.device:<10} {p.description}")


# =============================================================================
#  공통 그래프 스타일 (보고서 품질)
# =============================================================================
def setup_matplotlib(headless: bool = False):
    import matplotlib

    if headless:
        matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager

    # 한글 폰트: 없으면 그래프의 한글이 □ 로 깨진다
    available = {f.name for f in font_manager.fontManager.ttflist}
    for name in ("Malgun Gothic", "AppleGothic", "NanumGothic", "Noto Sans CJK KR", "Noto Sans KR"):
        if name in available:
            plt.rcParams["font.family"] = name
            break
    plt.rcParams.update({
        "axes.unicode_minus": False,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.grid": True,
        "grid.alpha": 0.25,
        "figure.dpi": 100,
        "savefig.dpi": 200,
    })
    return plt


def timestamp() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


# =============================================================================
#  실시간 모드
# =============================================================================
class LiveApp:
    def __init__(self, src: Source, window_s: float, fs: float, log_csv: bool,
                 polarity: int = +1) -> None:
        self.src = src
        self.fs = fs
        self.window_s = window_s
        n = int(window_s * fs * 1.2)
        self.t: deque[float] = deque(maxlen=n)
        self.mv: deque[float] = deque(maxlen=n)
        self.ma: deque[float] = deque(maxlen=n)
        self.bp: deque[float] = deque(maxlen=n)
        self.ph: deque[int] = deque(maxlen=n)
        self.events: deque[tuple] = deque(maxlen=200)   # (t, y, "inhale"|"exhale")
        self.polarity = polarity
        self.filt = BandpassButter2(fs)
        self.det = SlopeDetector(polarity)

        self.t0_ms: int | None = None
        self.last_seq: int | None = None
        self.received = 0
        self.dropped = 0
        self.dev_fs: str = "-"
        self.warn: str = ""
        self.paused = False
        self.arrivals: deque[float] = deque()

        self.csv_file = None
        self.csv_writer = None
        if log_csv:
            DATA_DIR.mkdir(exist_ok=True)
            self.csv_path = DATA_DIR / f"breath_{timestamp()}.csv"
            self.csv_file = open(self.csv_path, "w", newline="", encoding="utf-8")
            self.csv_writer = csv.writer(self.csv_file)
            self.csv_writer.writerow(["host_time", "seq", "t_ms", "elapsed_s",
                                      "mv", "ma_mv", "bp_mv", "phase", "event"])

    # --- 수신 데이터 반영 -----------------------------------------------------
    def drain(self) -> None:
        now = time.perf_counter()
        while True:
            try:
                s = self.src.q.get_nowait()
            except queue.Empty:
                break
            # 시퀀스 번호로 유실/리셋 감지
            if self.last_seq is not None:
                gap = s.seq - self.last_seq - 1
                if gap > 0:
                    self.dropped += gap
                elif s.seq <= self.last_seq:          # 보드 리셋 → 시간축 재설정
                    self.t0_ms = None
                    self.warn = "보드 리셋 감지"
                    self.filt = BandpassButter2(self.fs)   # 신호가 끊겼으니 필터도 새로 시작
                    self.det = SlopeDetector(self.polarity)
            self.last_seq = s.seq
            if self.t0_ms is None:
                # 첫 샘플이면 0 s, 리셋 후면 직전 시각 + 1주기에서 이어 그린다
                resume_ms = (self.t[-1] * 1000 + 1000 / self.fs) if self.t else 0
                self.t0_ms = s.t_ms - resume_ms
            elapsed = (s.t_ms - self.t0_ms) / 1000.0   # 기기 시각 기준 시간축

            self.t.append(elapsed)
            self.mv.append(s.mv)
            self.ma.append(s.ma)
            bp = self.filt.update(s.mv)
            ev = self.det.update(elapsed, bp)
            if ev:
                self.events.append((ev["t"], ev["y"], ev["type"]))
            self.bp.append(bp)
            self.ph.append(self.det.phase)
            self.received += 1
            self.arrivals.append(now)
            if self.csv_writer:
                self.csv_writer.writerow([datetime.now().isoformat(timespec="milliseconds"),
                                          s.seq, s.t_ms, f"{elapsed:.3f}", s.mv, s.ma,
                                          f"{bp:.2f}", PHASE_NAME[self.det.phase],
                                          ev["type"] if ev else ""])
        while self.arrivals and self.arrivals[0] < now - 3.0:
            self.arrivals.popleft()

        while True:
            try:
                d = self.src.diag.get_nowait()
            except queue.Empty:
                break
            if d.startswith("# fs="):
                self.dev_fs = d.split()[1].split("=")[1]
            elif "WARN" in d:
                self.warn = d.lstrip("# ")
                print(d)
            elif "sensor_ok" in d:
                self.warn = ""
                print(d)
            else:
                print(d)

    def host_rate(self) -> float:
        if len(self.arrivals) < 2:
            return 0.0
        span = self.arrivals[-1] - self.arrivals[0]
        return (len(self.arrivals) - 1) / span if span > 0 else 0.0

    # --- 실행 ----------------------------------------------------------------
    def run(self) -> None:
        plt = setup_matplotlib()
        from matplotlib.animation import FuncAnimation

        fig, (ax, ax_f) = plt.subplots(2, 1, figsize=(11, 7), sharex=True)
        fig.canvas.manager.set_window_title("Air-Rhythm 실시간 호흡 파형")
        (l_raw,) = ax.plot([], [], color=RAW_COLOR, lw=0.9)
        l_ph = draw_phase_line(ax_f, [0.0], [0.0], [0])
        (m_in,) = ax_f.plot([], [], ls="", marker="^", ms=9, color=INHALE_COLOR)
        (m_ex,) = ax_f.plot([], [], ls="", marker="v", ms=9, color=EXHALE_COLOR)
        phase_legend(ax_f)
        state_txt = ax_f.text(0.995, 1.02, "", transform=ax_f.transAxes, ha="right",
                              va="bottom", fontsize=10, color="#333")
        ax.set_title("① 측정값 (오버샘플링 mV)", loc="left", fontsize=10.5)
        ax_f.set_title(f"② 필터링 값 + 흡기/호기 판별 (대역통과 {HP_HZ}~{LP_HZ} Hz)",
                       loc="left", fontsize=10.5)
        ax.set_ylabel("mV")
        ax_f.set_ylabel("mV")
        ax_f.axhline(0, color="#999", lw=0.6)
        ax_f.set_xlabel("시간 (s)")
        status = ax.text(0.995, 1.02, "", transform=ax.transAxes, ha="right", va="bottom",
                         fontsize=9, color="#555")
        warn_txt = ax.text(0.5, 0.95, "", transform=ax.transAxes, ha="center", va="top",
                           fontsize=11, color="#c0392b", fontweight="bold")
        fig.suptitle(f"호흡 인장 센서 파형  ·  {self.src.label}", x=0.01, ha="left", fontsize=11.5)
        fig.tight_layout()

        def save(tag: str = "") -> None:
            IMG_DIR.mkdir(exist_ok=True)
            path = IMG_DIR / f"breath_live_{timestamp()}{tag}.png"
            fig.savefig(path, bbox_inches="tight")
            print(f"[저장] {path}")

        def on_key(ev) -> None:
            if ev.key == "s":
                save()
            elif ev.key == "p":
                self.paused = not self.paused
                print("[일시정지]" if self.paused else "[재개]")

        def update(_frame):
            self.drain()
            # 기기 경고 줄은 부팅 직후 한 번만 오는데, 포트를 열 때 버퍼를 비우면서
            # 사라질 수 있다 → 수신 데이터로 직접 포화 여부를 판정한다
            recent = list(self.mv)[-SAT_CHECK_N:]
            if self.src.error:
                warn_txt.set_text(self.src.error)
            elif len(recent) == SAT_CHECK_N and min(recent) >= SAT_HIGH_MV:
                warn_txt.set_text(f"ADC 상한 포화 ({recent[-1]:.0f} mV): "
                                  "센서 단선 또는 센서 저항이 풀업보다 훨씬 큼")
            elif len(recent) == SAT_CHECK_N and max(recent) <= SAT_LOW_MV:
                warn_txt.set_text(f"ADC 하한 ({recent[-1]:.0f} mV): 센서 단락 의심")
            else:
                warn_txt.set_text("")
            status.set_text(
                f"수신 {self.host_rate():5.1f} Hz | 기기 fs {self.dev_fs} | "
                f"샘플 {self.received} | 유실 {self.dropped}"
                + ("  [일시정지]" if self.paused else "")
            )
            if self.paused or not self.t:
                return (l_raw, *l_ph, m_in, m_ex, status, warn_txt, state_txt)

            t_end = self.t[-1]
            t_start = max(0.0, t_end - self.window_s)
            l_raw.set_data(self.t, self.mv)
            import numpy as np
            tt_a = np.fromiter(self.t, float); bp_a = np.fromiter(self.bp, float)
            ph_a = np.fromiter(self.ph, int)
            for line, code in zip(l_ph, (+1, -1, 0)):
                m = ph_a == code
                m = m | np.concatenate(([False], m[:-1]))
                line.set_data(tt_a, np.where(m, bp_a, np.nan))
            ev_in = [(e[0], e[1]) for e in self.events if e[2] == "inhale" and e[0] >= t_start]
            ev_ex = [(e[0], e[1]) for e in self.events if e[2] == "exhale" and e[0] >= t_start]
            m_in.set_data([e[0] for e in ev_in], [e[1] for e in ev_in])
            m_ex.set_data([e[0] for e in ev_ex], [e[1] for e in ev_ex])
            bpm = self.det.bpm()
            if self.det.t0 is not None and self.t[-1] - self.det.t0 < SETTLE_S:
                state_txt.set_text(f"안정화 중… {SETTLE_S - (self.t[-1] - self.det.t0):.0f}초")
            else:
                state_txt.set_text(f"현재: {PHASE_KO[self.det.phase]}"
                                   + (f"  |  {bpm:.1f} 회/분" if bpm else ""))
            ax.set_xlim(t_start, max(t_start + self.window_s, t_end))

            # 보이는 구간 기준 y 자동 스케일 (최소 폭 보장 → 무호흡 시 노이즈 과대 확대 방지)
            vis = [v for tt, v in zip(self.t, self.mv) if tt >= t_start]
            lo, hi = min(vis), max(vis)
            mid, half = (lo + hi) / 2, max((hi - lo) / 2 * 1.15, 20.0)
            ax.set_ylim(mid - half, mid + half)
            # 필터링 값은 0 중심 대칭
            peak = max(abs(v) for tt, v in zip(self.t, self.bp) if tt >= t_start)
            lim = max(peak * 1.2, 10.0)
            ax_f.set_ylim(-lim, lim)
            return (l_raw, *l_ph, m_in, m_ex, status, warn_txt, state_txt)

        fig.canvas.mpl_connect("key_press_event", on_key)
        self.anim = FuncAnimation(fig, update, interval=50, blit=False, cache_frame_data=False)

        print("단축키: s=PNG 저장, p=일시정지, q=종료")
        self.src.start()
        try:
            plt.show()
        finally:
            if self.t:
                save("_final")
            self.src.stop()
            self.src.join(timeout=2)
            if self.csv_file:
                self.csv_file.close()
                print(f"[CSV]  {self.csv_path}  ({self.received} 샘플, 유실 {self.dropped})")
            print_detect_summary(self.det)


# =============================================================================
#  재생 모드 — CSV → 보고서용 PNG
# =============================================================================
def print_detect_summary(det: SlopeDetector) -> None:
    n_in = len(det.inhale_times)
    if not det.delays:
        print("[판별] 확정된 전환 없음 (측정이 너무 짧거나 신호가 너무 작음)")
        return
    bpm = det.bpm()
    print(f"[판별] 전환 {len(det.delays)}회 (흡기 시작 {n_in}회)"
          + (f", 호흡수 {bpm:.1f} 회/분" if bpm else "")
          + f", 평균 검출 지연 {1000 * sum(det.delays) / len(det.delays):.0f} ms "
          "(필터링 곡선의 골/마루 → 확정)")


def run_replay(path: Path, start: float, duration: float | None, show: bool,
               polarity: int = +1) -> list[Path]:
    t, mv = [], []
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            try:
                tt = float(row["elapsed_s"]); x = float(row["mv"])
            except (KeyError, ValueError):
                continue
            t.append(tt); mv.append(x)
    if not t:
        sys.exit(f"[오류] {path} 에서 데이터를 읽지 못했습니다. (elapsed_s, mv 열 필요)")

    # 필터는 파일 "처음부터" 돌린 뒤 구간을 자른다 → 구간 시작부에 과도응답이 남지 않는다
    filt = BandpassButter2(DEFAULT_FS)
    bp = [filt.update(x) for x in mv]
    det = SlopeDetector(polarity)
    ph, events = [], []
    for tt, y in zip(t, bp):
        ev = det.update(tt, y)
        if ev:
            events.append(ev)
        ph.append(det.phase)
    print_detect_summary(det)

    end = start + duration if duration else t[-1]
    idx = [i for i, tt in enumerate(t) if start <= tt <= end]
    if not idx:
        sys.exit(f"[오류] {start}~{end}s 구간에 데이터가 없습니다. (전체 0~{t[-1]:.1f}s)")
    t = [t[i] for i in idx]; mv = [mv[i] for i in idx]; bp = [bp[i] for i in idx]
    ph = [ph[i] for i in idx]
    events = [e for e in events if t[0] <= e["t"] <= t[-1]]
    if start < 10:
        print("[참고] 측정 시작 후 약 10초는 필터가 자리를 잡는 구간입니다. "
              "보고서용이면 --start 10 이상을 권장합니다.")

    plt = setup_matplotlib(headless=not show)
    span = f"{t[0]:.0f}-{t[-1]:.0f}s"
    head = f"{path.name}  ({t[0]:.0f}–{t[-1]:.0f} s)"
    raw_title = "① 측정값 (오버샘플링 mV)"
    bp_title = f"② 필터링 값 + 흡기/호기 판별 (대역통과 {HP_HZ}~{LP_HZ} Hz)"

    def draw_raw(ax):
        ax.plot(t, mv, color=RAW_COLOR, lw=0.8)
        ax.set_ylabel("mV"); ax.set_title(raw_title, loc="left", fontsize=10.5)

    def draw_bp(ax):
        ax.axhline(0, color="#999", lw=0.6)
        draw_phase_line(ax, t, bp, ph, lw=1.6)
        for kind, mk, color in (("inhale", "^", INHALE_COLOR), ("exhale", "v", EXHALE_COLOR)):
            ev = [e for e in events if e["type"] == kind]
            ax.plot([e["t"] for e in ev], [e["y"] for e in ev], ls="", marker=mk, ms=8, color=color)
        phase_legend(ax)
        lim = max(max(abs(v) for v in bp) * 1.35, 10.0)   # 위쪽 범례 자리 확보
        ax.set_ylim(-lim, lim)
        ax.set_ylabel("mV"); ax.set_title(bp_title, loc="left", fontsize=10.5)

    IMG_DIR.mkdir(exist_ok=True)
    outs: list[Path] = []

    # 1·2) 측정값만 / 필터링 값만 — 각각 따로
    for tag, draw in (("raw", draw_raw), ("filtered", draw_bp)):
        fig, ax = plt.subplots(figsize=(11, 3.8))
        draw(ax)
        ax.set_xlim(t[0], t[-1]); ax.set_xlabel("시간 (s)")
        fig.suptitle(head, x=0.01, ha="left", fontsize=10, color="#555")
        fig.tight_layout()
        out = IMG_DIR / f"{path.stem}_{span}_{tag}.png"
        fig.savefig(out, bbox_inches="tight"); plt.close(fig)
        outs.append(out); print(f"[저장] {out}")

    # 3) 두 개를 위아래로 (비교용)
    fig, (a1, a2) = plt.subplots(2, 1, figsize=(11, 6.5), sharex=True)
    draw_raw(a1); draw_bp(a2)
    a2.set_xlim(t[0], t[-1]); a2.set_xlabel("시간 (s)")
    fig.suptitle(f"호흡 인장 센서 파형  ·  {head}", x=0.01, ha="left", fontsize=11.5)
    fig.tight_layout()
    out = IMG_DIR / f"{path.stem}_{span}_both.png"
    fig.savefig(out, bbox_inches="tight")
    outs.append(out); print(f"[저장] {out}")
    if show:
        plt.show()
    plt.close(fig)
    return outs


# =============================================================================
def main() -> None:
    ap = argparse.ArgumentParser(description="Air-Rhythm 실시간 호흡 파형 시각화")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--port", help="시리얼 포트 (예: COM5, /dev/ttyUSB0)")
    g.add_argument("--list", action="store_true", help="사용 가능한 포트 목록")
    g.add_argument("--demo", action="store_true", help="보드 없이 모의 신호로 실행")
    g.add_argument("--replay", type=Path, help="저장된 CSV 로 보고서용 PNG 생성")
    ap.add_argument("--baud", type=int, default=DEFAULT_BAUD)
    ap.add_argument("--window", type=float, default=DEFAULT_WINDOW_S, help="화면 시간 폭(s)")
    ap.add_argument("--no-log", action="store_true", help="CSV 기록 끄기")
    ap.add_argument("--start", type=float, default=0.0, help="[replay] 시작 시각(s)")
    ap.add_argument("--duration", type=float, default=None, help="[replay] 구간 길이(s)")
    ap.add_argument("--no-show", action="store_true", help="[replay] 창 없이 PNG 만 저장")
    ap.add_argument("--invert", action="store_true",
                    help="센서가 '숨을 들이쉬면 값이 내려가는' 방향일 때 흡기/호기를 뒤집는다")
    args = ap.parse_args()

    if args.list:
        list_ports()
        return
    if args.replay:
        run_replay(args.replay, args.start, args.duration, show=not args.no_show,
                   polarity=-1 if args.invert else +1)
        return

    src: Source = DemoSource(DEFAULT_FS) if args.demo else SerialSource(args.port, args.baud)
    LiveApp(src, args.window, DEFAULT_FS, log_csv=not args.no_log,
            polarity=-1 if args.invert else +1).run()


if __name__ == "__main__":
    main()
