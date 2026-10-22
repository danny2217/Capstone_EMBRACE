#!/usr/bin/env python3
"""Air-Rhythm 흡기/호기 판별기 — 제품 연동용 경량판

visualizer.py 에서 그래프·CSV·PNG 기능을 모두 덜어내고 "지금 흡기인가, 호기인가"
만 판단하는 부분을 남긴 모듈이다. 의존성은 pyserial 하나뿐이다 (numpy·matplotlib 불필요).

신호 처리 경로는 visualizer.py 와 동일하다.
    ESP32 (seq, t_ms, mv)  →  대역통과 0.08~0.7 Hz  →  SlopeDetector  →  흡기 / 호기 / 판정 보류
  - BandpassButter2 : 원본 Air-Rhythm breath/filters.py
  - SlopeDetector   : 원본 Air-Rhythm breath/detectors/slope.py (MID_GATE=on, PROM_RATIO=0 고정)
  같은 입력이면 visualizer.py 와 같은 시각에 같은 전환을 확정한다.

visualizer.py 에 없고 여기서 추가한 것 (제품에 붙일 때 필요한 안전 장치)
  1) 센서 이상(단선·단락 = ADC 포화 0.5 s 지속) → 즉시 '판정 보류', 복구 시 처음부터 재안정화
  2) 데이터 끊김(STALE_S 동안 샘플 없음)        → 즉시 '판정 보류'
  3) 샘플 대량 유실(0.5 s 이상) / 보드 리셋     → 필터·검출기 재시작
  4) 기록 리스트를 길이 제한 deque 로 바꿔 장시간 실행해도 메모리가 늘지 않음
  '판정 보류(UNKNOWN)' 는 "확실하지 않으니 아무 동작도 하지 말라" 는 뜻이다.
  제품 쪽은 UNKNOWN 을 받으면 반드시 안전 상태(가압 금지 등)로 가야 한다.

실행
  python detector.py --list                   포트 목록
  python detector.py --port COM5              실시간 판별 (전환될 때만 한 줄 출력)
  python detector.py --port COM5 --invert     들이쉬면 전압이 내려가는 센서일 때
  python detector.py --port COM5 --plain      다른 프로그램이 읽기 쉬운 TAB 형식
  python detector.py --demo [--fast]          보드 없이 모의 신호로 동작 확인

다른 파이썬 코드에서 쓰기
  from detector import BreathPhaseDetector, Phase
  det = BreathPhaseDetector(on_change=lambda st: print(st.phase))
  det.update(seq, t_ms, mv)          # 샘플이 올 때마다
  det.check_stale(time.monotonic())  # 주기적으로 (샘플이 안 와도)
  det.phase                          # Phase.INHALE / Phase.EXHALE / Phase.UNKNOWN
"""

from __future__ import annotations

import argparse
import math
import random
import sys
import time
from collections import deque
from dataclasses import dataclass
from enum import IntEnum
from statistics import median
from typing import Callable, Iterator

# =============================================================================
#  설정 — 펌웨어(sensor_app.cpp) 및 visualizer.py 와 같은 값
# =============================================================================
DEFAULT_BAUD = 115200
DEFAULT_FS = 50.0        # 펌웨어 PERIOD_MS=20

HP_HZ = 0.08             # 대역통과 고역 차단 (약 5 bpm 이하 드리프트 제거)
LP_HZ = 0.70             # 대역통과 저역 차단 (약 42 bpm 이상 노이즈 제거)

SAT_HIGH_MV = 3000       # 이상이면 단선/ADC 상한 포화 (펌웨어 OPEN_MV)
SAT_LOW_MV = 60          # 이하면 단락 (펌웨어 SHORT_MV)
SAT_CHECK_N = 25         # 연속 0.5 s 범위 밖이어야 센서 이상으로 판정 (펌웨어 FAULT_HOLD)

MAX_GAP_SAMPLES = 25     # seq 가 이만큼(0.5 s) 이상 건너뛰면 필터·검출기를 재시작
STALE_S = 0.5            # 이 시간 동안 샘플이 하나도 안 오면 판정 보류

# SlopeDetector 파라미터 (원본 breath/detectors/slope.py 기본값)
SLOPE_TAU_S = 0.08       # 기울기 평활 시상수 (지연 ↔ 노이즈)
AVG_TAU_S = 8.0          # 평균 |기울기| 추정 시상수
K_SLOPE = 0.25           # 데드밴드 = K_SLOPE × 평균 |기울기|
SLOPE_FLOOR = 1.0        # 데드밴드 하한 (mV/s)
MIN_PHASE_S = 1.2        # 전환 후 반대 전환 금지 (s) → 최대 약 25 bpm
MIN_AMP = 5.0            # 진폭(p-p) 하한 (mV). 이보다 작으면 판정 보류
ENV_DECAY_S = 6.0        # 진폭 포락선 완화 시상수 (s)
SETTLE_S = 12.0          # 시작(재시작) 후 판정 보류 (s)
RATE_WINDOW = 5          # 호흡수 = 최근 흡기 간격 5개의 중앙값
RATE_MIN_S, RATE_MAX_S = 1.4, 12.0


class Phase(IntEnum):
    INHALE = +1      # 흡기 중
    EXHALE = -1      # 호기 중
    UNKNOWN = 0      # 판정 보류 → 제품은 아무 동작도 하지 않는다


# 판정 보류 사유 (Phase 가 UNKNOWN 일 때만 의미가 있다)
REASON_OK = "ok"
REASON_SETTLING = "settling"          # 시작/재시작 후 필터 안정화 중
REASON_LOW_SIGNAL = "low_signal"      # 진폭이 너무 작음 (숨 참기, 스트랩 풀림)
REASON_SENSOR_OPEN = "sensor_open"    # ADC 상한 포화 (단선, 센서 분리)
REASON_SENSOR_SHORT = "sensor_short"  # ADC 하한 (단락)
REASON_STALE = "stale"                # 데이터가 들어오지 않음 (USB 끊김, 보드 정지)

REASON_KO = {
    REASON_OK: "정상",
    REASON_SETTLING: "안정화 중",
    REASON_LOW_SIGNAL: "신호 약함",
    REASON_SENSOR_OPEN: "센서 단선/분리",
    REASON_SENSOR_SHORT: "센서 단락",
    REASON_STALE: "데이터 끊김",
}


# =============================================================================
#  2차 Butterworth 대역통과 — 원본 breath/filters.py (visualizer.py 와 동일)
# =============================================================================
class Biquad:
    """Direct Form II transposed 2차 IIR 한 단."""

    def __init__(self, b0, b1, b2, a1, a2):
        self.b0, self.b1, self.b2, self.a1, self.a2 = b0, b1, b2, a1, a2
        self.s1 = self.s2 = 0.0
        self.ready = False

    def _init_steady(self, x):
        # 입력 x 가 계속 들어왔던 것처럼 상태를 맞춘다 → 시작 순간의 큰 튐 제거
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
    """고역통과 biquad → 저역통과 biquad. 미래 샘플을 보지 않는다(실시간 가능)."""

    def __init__(self, fs=DEFAULT_FS, hp_hz=HP_HZ, lp_hz=LP_HZ):
        self.hp = _butter2(hp_hz, fs, "high")
        self.lp = _butter2(lp_hz, fs, "low")

    def update(self, x):
        return self.lp.update(self.hp.update(x))


# =============================================================================
#  기울기 검출기 — 원본 breath/detectors/slope.py (visualizer.py 와 동일 로직)
# =============================================================================
class SlopeDetector:
    """평활 기울기의 부호가 '확실히' 바뀐 순간을 흡기/호기 시작으로 확정한다.

    update(t, y) 는 전환이 확정되면 이벤트 dict 를, 아니면 None 을 돌려준다.
      {"type": "inhale"|"exhale", "t": 확정 시각, "ext_t": 실제 골/마루 시각}
    phase: +1 흡기 / -1 호기 / 0 판정 보류
    """

    def __init__(self, polarity=+1):
        self.polarity = polarity
        self.t0 = self.last_t = self.y_prev = None
        self.sd = 0.0
        self.avg_abs_sd = 0.0
        self.env_hi = self.env_lo = None
        self.phase = 0
        self.ext_val = self.ext_t = None
        self.last_transition_t = None
        # visualizer.py 는 list 라 계속 늘어난다. 제품용은 최근 것만 보관.
        self.inhale_times: deque[float] = deque(maxlen=RATE_WINDOW * 3)

    def amplitude(self):
        return 0.0 if self.env_hi is None else self.env_hi - self.env_lo

    def settling(self, t):
        return self.t0 is None or t - self.t0 < SETTLE_S

    def update(self, t, y_raw):
        y = self.polarity * y_raw
        if self.t0 is None:
            self.t0 = self.last_t = self.last_transition_t = t
            self.y_prev = y
            self.env_hi = self.env_lo = y
            self.ext_val, self.ext_t = y, t
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
            self.ext_val, self.ext_t = y, t
            self.phase = 0
            return None

        can_switch = (t - self.last_transition_t) >= MIN_PHASE_S
        mid = 0.5 * (self.env_hi + self.env_lo)
        gate_open = (y < mid) if self.phase == -1 else (y > mid)   # 중점 게이트

        if self.phase == 0:                                   # 첫 방향 잡기
            if self.sd > sth:
                self.phase = +1
            elif self.sd < -sth:
                self.phase = -1
            else:
                return None
            self.ext_val, self.ext_t = y, t
            self.last_transition_t = t
            return None

        if self.phase == -1:                                  # 호기 중 → 골 추적
            if y < self.ext_val:
                self.ext_val, self.ext_t = y, t
            if can_switch and gate_open and self.sd > sth:
                return self._switch(+1, "inhale", t, y)
        else:                                                 # 흡기 중 → 마루 추적
            if y > self.ext_val:
                self.ext_val, self.ext_t = y, t
            if can_switch and gate_open and self.sd < -sth:
                return self._switch(-1, "exhale", t, y)
        return None

    def _switch(self, new_phase, kind, t, y):
        ev = {"type": kind, "t": t, "ext_t": self.ext_t}
        if kind == "inhale":
            self.inhale_times.append(t)
        self.phase = new_phase
        self.last_transition_t = t
        self.ext_val, self.ext_t = y, t
        return ev

    def bpm(self):
        """최근 흡기 간격(1.4~12 s) 5개의 중앙값 → 분당 호흡수 (원본 slope.py 와 동일)."""
        it = list(self.inhale_times)
        iv = [b - a for a, b in zip(it, it[1:]) if RATE_MIN_S <= b - a <= RATE_MAX_S]
        return 60.0 / median(iv[-RATE_WINDOW:]) if iv else None


# =============================================================================
#  제품 연동용 진입점
# =============================================================================
@dataclass(frozen=True)
class BreathEvent:
    """흡기/호기 시작이 확정된 순간."""
    phase: Phase          # INHALE 또는 EXHALE
    t: float              # 확정 시각 (기기 시계 기준 s)
    delay_s: float        # 실제 골/마루 → 확정까지 걸린 시간
    bpm: float | None


@dataclass(frozen=True)
class BreathState:
    """현재 판정 상태. phase 가 UNKNOWN 이면 reason 에 이유가 있다."""
    phase: Phase
    reason: str
    t: float
    bpm: float | None
    amp_mv: float


class BreathPhaseDetector:
    """샘플을 하나씩 넣으면 흡기/호기/판정 보류를 판단한다.

    on_event(BreathEvent)  : 흡기/호기 시작이 확정될 때마다 호출
    on_change(BreathState) : phase 또는 reason 이 바뀔 때마다 호출 (판정 보류 진입 포함)
    """

    def __init__(self, fs: float = DEFAULT_FS, polarity: int = +1,
                 on_event: Callable[[BreathEvent], None] | None = None,
                 on_change: Callable[[BreathState], None] | None = None) -> None:
        self.fs = fs
        self.polarity = polarity
        self.on_event = on_event
        self.on_change = on_change

        self.t0_ms: int | None = None      # 기기 시각 기준점
        self.t = 0.0                       # 마지막 샘플의 경과 시간 (s)
        self.last_seq: int | None = None
        self.last_host_t: float | None = None
        self.received = 0
        self.dropped = 0
        self.restarts = 0

        self._recent_mv: deque[float] = deque(maxlen=SAT_CHECK_N)
        self._fault: str | None = None     # sensor_open / sensor_short / None
        self._stale = False
        self._reported: tuple[Phase, str] | None = None
        self._new_pipeline()

    # --- 내부 -----------------------------------------------------------------
    def _new_pipeline(self) -> None:
        """필터·검출기를 처음 상태로. 이후 SETTLE_S 동안 판정 보류."""
        self._filt = BandpassButter2(self.fs)
        self._det = SlopeDetector(self.polarity)

    def _restart(self) -> None:
        self._new_pipeline()
        self.restarts += 1

    def _classify_fault(self) -> str | None:
        if len(self._recent_mv) < SAT_CHECK_N:
            return None
        if min(self._recent_mv) >= SAT_HIGH_MV:
            return REASON_SENSOR_OPEN
        if max(self._recent_mv) <= SAT_LOW_MV:
            return REASON_SENSOR_SHORT
        return None

    def _notify_if_changed(self) -> None:
        st = self.state()
        key = (st.phase, st.reason)
        if key != self._reported:
            self._reported = key
            if self.on_change:
                self.on_change(st)

    # --- 공개 API -------------------------------------------------------------
    @property
    def phase(self) -> Phase:
        return self.state().phase

    def state(self) -> BreathState:
        det = self._det
        if self._stale:
            reason = REASON_STALE
        elif self._fault:
            reason = self._fault
        elif det.settling(self.t):
            reason = REASON_SETTLING
        elif det.phase == 0:
            reason = REASON_LOW_SIGNAL
        else:
            reason = REASON_OK
        phase = Phase(det.phase) if reason == REASON_OK else Phase.UNKNOWN
        return BreathState(phase, reason, self.t, det.bpm(), det.amplitude())

    def update(self, seq: int, t_ms: int, mv: float,
               host_t: float | None = None) -> BreathEvent | None:
        """샘플 하나 처리. 전환이 확정되면 BreathEvent 를 돌려준다."""
        self.last_host_t = time.monotonic() if host_t is None else host_t
        self._stale = False

        # 1) 일련번호로 유실·리셋 감지
        if self.last_seq is not None:
            gap = seq - self.last_seq - 1
            if seq <= self.last_seq:                 # 보드 리셋 → 시간축·파이프라인 재시작
                self.t0_ms = None
                self._recent_mv.clear()
                self._restart()
            elif gap > 0:
                self.dropped += gap
                if gap >= MAX_GAP_SAMPLES:           # 0.5 s 이상 비면 필터 상태를 믿을 수 없다
                    self._restart()
        self.last_seq = seq

        # 2) 기기 시각 → 경과 시간 (millis() 32비트 순환에도 안전한 부호 없는 뺄셈)
        if self.t0_ms is None:
            self.t0_ms = (t_ms - int(round(self.t * 1000))) & 0xFFFFFFFF
        self.t = ((t_ms - self.t0_ms) & 0xFFFFFFFF) / 1000.0
        self.received += 1

        # 3) 센서 이상 (ADC 포화) — 이상 중에는 판정하지 않고, 복구되면 처음부터
        self._recent_mv.append(mv)
        fault = self._classify_fault()
        if fault != self._fault:
            was_fault, self._fault = self._fault, fault
            if was_fault and not fault:
                self._restart()
        if self._fault:
            self._notify_if_changed()
            return None

        # 4) 필터 → 검출
        ev = self._det.update(self.t, self._filt.update(mv))
        out = None
        if ev:
            out = BreathEvent(Phase.INHALE if ev["type"] == "inhale" else Phase.EXHALE,
                              ev["t"], ev["t"] - ev["ext_t"], self._det.bpm())
        self._notify_if_changed()
        if out and self.on_event:
            self.on_event(out)
        return out

    def check_stale(self, now: float | None = None) -> bool:
        """샘플이 STALE_S 이상 안 왔으면 판정 보류로 바꾼다. 수신 루프에서 주기적으로 호출."""
        now = time.monotonic() if now is None else now
        if self.last_host_t is not None and now - self.last_host_t > STALE_S:
            if not self._stale:
                self._stale = True
                self._notify_if_changed()
        return self._stale


# =============================================================================
#  시리얼 줄 파싱 / 입력 소스
# =============================================================================
def parse_sample(line: str) -> tuple[int, int, float] | None:
    """'seq<TAB>t_ms<TAB>mv<TAB>ma_mv' → (seq, t_ms, mv). '#' 진단 줄·깨진 줄은 None."""
    if not line or line.startswith("#"):
        return None
    parts = line.split("\t")
    if len(parts) != 4:
        return None
    try:
        return int(parts[0]), int(parts[1]), float(parts[2])
    except ValueError:
        return None


def list_ports() -> None:
    try:
        from serial.tools import list_ports as lp
    except ImportError:
        print("pyserial 이 필요합니다: pip install pyserial")
        return
    ports = list(lp.comports())
    if not ports:
        print("연결된 시리얼 포트가 없습니다.")
        return
    print("사용 가능한 포트:")
    for p in ports:
        print(f"  {p.device:<10} {p.description}")


def serial_lines(port: str, baud: int) -> Iterator[str | None]:
    """시리얼 줄을 내보낸다. 타임아웃(0.1 s)이면 None — 호출 측이 stale 검사를 할 수 있게."""
    import serial

    try:
        ser = serial.Serial(port, baud, timeout=0.1)
    except serial.SerialException as e:
        print(f"[오류] 포트를 열지 못했습니다: {e}", file=sys.stderr)
        print("  - 포트 이름, Arduino IDE 시리얼 모니터가 닫혀 있는지 확인", file=sys.stderr)
        sys.exit(1)
    time.sleep(1.5)            # ESP32 는 포트를 열면 리셋된다 → 부팅 로그를 흘려보낸다
    ser.reset_input_buffer()
    try:
        while True:
            raw = ser.readline()
            yield raw.decode("utf-8", errors="ignore").strip() if raw else None
    finally:
        ser.close()


def demo_lines(fs: float, realtime: bool) -> Iterator[str | None]:
    """모의 신호 (약 15 bpm). 구간별로 정상 / 숨 참기 / 움직임 / 센서 분리를 흉내 낸다.

      0~40 s  정상 호흡   40~41.5 s 움직임 잡음   50~60 s 숨 참기
      70~73 s 센서 분리(3145 mV)   73 s~ 정상 → 재안정화 후 다시 판정   100 s 종료
    """
    period = 1.0 / fs
    rng = random.Random(7)
    phase = 0.0
    t0 = time.perf_counter()
    yield "# AirRhythm DEMO"
    for seq in range(int(100 * fs)):
        t = seq * period
        phase += 2 * math.pi * 15 / 60 * period
        breath = 45 * (math.sin(phase) + 0.25 * math.sin(2 * phase + 0.6))
        if 50 <= t < 60:
            breath = 0.0
        mv = 900 + breath + 15 * math.sin(2 * math.pi * t / 90) + rng.gauss(0, 3)
        if 40 < t < 41.5:
            mv += rng.gauss(0, 40)
        if 70 <= t < 73:
            mv = 3145
        yield f"{seq}\t{int(t * 1000)}\t{round(mv)}\t{round(mv)}"
        if realtime:
            sleep = t0 + (seq + 1) * period - time.perf_counter()
            if sleep > 0:
                time.sleep(sleep)


# =============================================================================
#  CLI
# =============================================================================
PHASE_KO = {Phase.INHALE: "▲ 흡기", Phase.EXHALE: "▼ 호기", Phase.UNKNOWN: "■ 판정 보류"}


def run(lines: Iterator[str | None], polarity: int, plain: bool) -> BreathPhaseDetector:
    def on_change(st: BreathState) -> None:
        if plain:
            print(f"{st.t:.3f}\t{st.phase.name}\t{st.reason}", flush=True)
            return
        if st.phase == Phase.UNKNOWN:
            extra = f" (진폭 {st.amp_mv:.1f} mV)" if st.reason == REASON_LOW_SIGNAL else ""
            print(f"[{st.t:8.2f}s] {PHASE_KO[st.phase]}: {REASON_KO[st.reason]}{extra}", flush=True)

    def on_event(ev: BreathEvent) -> None:
        if plain:
            return                             # plain 모드는 on_change 한 줄로 충분
        rate = f", {ev.bpm:.1f} 회/분" if ev.bpm else ""
        print(f"[{ev.t:8.2f}s] {PHASE_KO[ev.phase]} 시작  (지연 {ev.delay_s * 1000:.0f} ms{rate})",
              flush=True)

    det = BreathPhaseDetector(DEFAULT_FS, polarity, on_event=on_event, on_change=on_change)
    try:
        for line in lines:
            if line:
                s = parse_sample(line)
                if s is not None:
                    det.update(*s)
                elif line.startswith("#") and not line.startswith("# fs=") and not plain:
                    print(line, flush=True)      # 펌웨어 진단(WARN 등)은 그대로 보여준다
            det.check_stale()
    except KeyboardInterrupt:
        pass
    except Exception as e:                       # USB 분리 등
        print(f"[오류] 입력이 끊겼습니다: {e} → 판정 보류", file=sys.stderr)
    if not plain:
        bpm = det.state().bpm
        print(f"\n[종료] 샘플 {det.received}, 유실 {det.dropped}, 재시작 {det.restarts}"
              + (f", 마지막 호흡수 {bpm:.1f} 회/분" if bpm else ""))
    return det


def main() -> None:
    ap = argparse.ArgumentParser(description="Air-Rhythm 흡기/호기 판별기 (그래프·CSV 없음)")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--port", help="시리얼 포트 (예: COM5, /dev/ttyUSB0)")
    g.add_argument("--list", action="store_true", help="사용 가능한 포트 목록")
    g.add_argument("--demo", action="store_true", help="보드 없이 모의 신호로 실행")
    ap.add_argument("--baud", type=int, default=DEFAULT_BAUD)
    ap.add_argument("--invert", action="store_true",
                    help="들이쉬면 값이 내려가는 센서일 때 흡기/호기를 뒤집는다")
    ap.add_argument("--plain", action="store_true",
                    help="상태가 바뀔 때마다 't<TAB>PHASE<TAB>reason' 한 줄 (다른 프로그램 연동용)")
    ap.add_argument("--fast", action="store_true", help="[demo] 실시간 대기 없이 바로 끝까지 실행")
    args = ap.parse_args()

    if args.list:
        list_ports()
        return
    polarity = -1 if args.invert else +1
    if args.demo:
        lines = demo_lines(DEFAULT_FS, realtime=not args.fast)
    else:
        lines = serial_lines(args.port, args.baud)
    if not args.plain:
        print(f"판별 시작 — 처음 {SETTLE_S:.0f}초는 안정화(판정 보류). Ctrl+C 로 종료\n")
    run(lines, polarity, args.plain)


if __name__ == "__main__":
    main()
