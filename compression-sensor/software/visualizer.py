#!/usr/bin/env python3
"""EMBRACE 압박 시각화 (compression-sensor) — 가슴/등 ZD10-100, 집중판 없이 감지부 단독 하중

ESP32-S3 펌웨어(firmware/embrace_node)의 chest, back 열을 받아 신호 처리 체인을 거쳐 그리고,
프레임 전체와 체인의 각 단계 값을 CSV 로 기록한다.

신호 처리 체인 (설계 계획서 '압박 신호 처리 체인과 증폭' 절과 같은 순서)
  [회로]   1. 분압 R_ref 470 kΩ        — 진짜 증폭은 여기서만 (10 kΩ 대비 약 10배)
           2. RC 저역통과 (×100 nF)    — 약 7.6 Hz 위 감쇠, 지연 약 21 ms
  [펌웨어] 3. 버림 1회 + 오버샘플 8회   — 잡음 ÷2.8 (백색잡음 가정)
  [PC]     4. 이동평균 10샘플 (0.2 s)  — 잡음 ÷3.2, 지연 약 90 ms (펌웨어 MovingAverage 와 같은 계산)
           5. 영점 보정 (tare)         — 시작 후 TARE_S 초(배기 상태) 평균을 보정표의 0 g 에 맞춤
           6. 보정표 환산              — mV → 감지부 하중 g → mmHg (P = m·g / 감지부 면적)
           7. 표시용 지수평활 τ 0.5 s  — 화면 표시 전용. 제어 지연 예산에는 넣지 않는다
  ※ 필터(3~4, 7)는 신호를 키우지 않는다. 잡음을 줄일 뿐이다.
    감지부 힘이 센서 응답 하한보다 작으면 출력이 0 이고, 어떤 필터도 0 을 키우지 못한다.

그래프
  ① 측정값 (mV)  : 옅은 선 = 기기 원값(3단계 결과), 진한 선 = 이동평균(4단계)
  ② 보정 파일이 있으면 등가 압력 (mmHg, 6~7단계), 없으면 영점 기준 변화 Δ mV (5단계)

실행
  python visualizer.py --list
  python visualizer.py --port COM5                 실시간 (처음 3초는 에어백을 뺀 상태로 → 영점)
  python visualizer.py --demo                      보드 없이 모의 데이터
  python visualizer.py --replay ../data/compression_20261105_101500.csv [--start 0 --duration 60]
            → PNG + 구간 통계: 원값/이동평균 잡음 비교(필터 효과), 평균 압력, 크리프

실시간 창 단축키:  t = 지금(앞 1초 평균)을 영점으로,  s = PNG 저장,  p = 일시정지,  q = 종료
"""

from __future__ import annotations

import argparse
import math
import queue
import sys
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT.parent / "common"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from calib_model import Calibration  # noqa: E402
from embrace_link import (CHANNEL_KO, DEFAULT_BAUD, DEFAULT_FS, CsvLogger, DemoDevice,  # noqa: E402
                          Source, Timeline, list_ports, mean, open_source, read_csv, stdev,
                          timestamp)
from plotting import COLORS, setup_matplotlib, shade_faults, stamp_demo  # noqa: E402

DATA_DIR = ROOT / "data"
IMG_DIR = ROOT / "report_images"
CHANNELS = ("chest", "back")
TARGET_MMHG = (20.0, 30.0)
DEFAULT_WINDOW_S = 60.0     # 가압 8 s + 유지 구간이 보이도록 넉넉하게

MA_WINDOW = 10              # 4단계: 펌웨어 config.h MA_WINDOW 와 같은 값
TARE_S = 3.0                # 5단계: 시작 후 이 시간 평균을 영점으로
SMOOTH_TAU_S = 0.5          # 7단계: 표시용 지수평활 시상수
CHAIN_TEXT = "이동평균 0.2 s → 영점 → 보정표 → 표시 평활 τ 0.5 s"


# =============================================================================
#  신호 처리 체인 — 실시간과 재생이 같은 클래스를 쓴다
# =============================================================================
@dataclass
class Stage:
    raw: float
    ma: float
    delta: float          # 영점 기준 변화 (mV)
    grams: float
    mmhg: float
    smooth: float         # 표시용 평활값 (mmHg, 보정 없으면 Δ mV)
    out_of_range: bool = False


@dataclass
class CompressionChain:
    name: str
    cal: Calibration | None = None
    fs: float = DEFAULT_FS
    tare: float | None = None
    _buf: deque = field(default_factory=lambda: deque(maxlen=MA_WINDOW))
    _tare_buf: list = field(default_factory=list)
    _smooth: float | None = None

    def set_tare(self, mv: float) -> None:
        self.tare = mv
        self._smooth = None

    def update(self, t: float, mv: float, fault: int) -> Stage:
        nan = math.nan
        if fault or mv != mv:
            # 센서 이상 구간: 값을 버리고 평균 창도 비운다 (복구 직후 잘못된 평균 방지)
            self._buf.clear()
            self._smooth = None
            return Stage(mv, nan, nan, nan, nan, nan)
        self._buf.append(mv)
        ma = sum(self._buf) / len(self._buf)                       # 4. 이동평균
        if self.tare is None:                                      # 5. 영점 (처음 TARE_S 초)
            self._tare_buf.append(ma)
            if t >= TARE_S:
                self.tare = sorted(self._tare_buf)[len(self._tare_buf) // 2]
            return Stage(mv, ma, nan, nan, nan, nan)
        delta = ma - self.tare
        grams = mmhg = nan
        oor = False
        if self.cal:                                               # 6. 보정표 (영점을 보정표 0 g 에 맞춤)
            grams, oor = self.cal.to_grams(self.cal.mv[0] + delta)
            mmhg, _ = self.cal.to_mmhg(self.cal.mv[0] + delta)
        target = mmhg if self.cal else delta
        a = (1.0 / self.fs) / (SMOOTH_TAU_S + 1.0 / self.fs)       # 7. 지수평활
        self._smooth = target if self._smooth is None else self._smooth + a * (target - self._smooth)
        return Stage(mv, ma, delta, grams, mmhg, self._smooth, oor)


def load_calibs(names, demo: bool = False) -> dict[str, Calibration]:
    out = {}
    for c in names:
        cal = Calibration.load(c, demo=demo)
        if cal:
            out[c] = cal
            print(f"[보정] {CHANNEL_KO[c]}: {Path(cal.source).name} (감지부 Ø{cal.sense_mm} mm, "
                  f"{cal.grams[0]:.1f}~{cal.grams[-1]:.1f} g)")
        else:
            print(f"[보정] {CHANNEL_KO[c]}: 보정 파일 없음 → 영점 기준 Δ mV 만 표시 (calibrate.py 를 먼저 실행)")
    return out


def _fmt(v: float, nd: int = 2) -> str:
    return "" if v != v else f"{v:.{nd}f}"


# =============================================================================
#  실시간 모드
# =============================================================================
class LiveApp:
    def __init__(self, src: Source, window_s: float, log_csv: bool, is_demo: bool) -> None:
        self.src = src
        self.window_s = window_s
        self.is_demo = is_demo
        self.tl = Timeline(DEFAULT_FS)
        self.cal = load_calibs(CHANNELS, demo=is_demo)
        self.chain = {c: CompressionChain(c, self.cal.get(c)) for c in CHANNELS}
        n = int(window_s * DEFAULT_FS * 1.2)
        self.t: deque[float] = deque(maxlen=n)
        self.raw = {c: deque(maxlen=n) for c in CHANNELS}
        self.ma = {c: deque(maxlen=n) for c in CHANNELS}
        self.low = {c: deque(maxlen=n) for c in CHANNELS}      # 아래 칸 값 (mmHg 또는 Δ mV)
        self.smooth = {c: deque(maxlen=n) for c in CHANNELS}
        self.fault = {c: 0 for c in CHANNELS}
        self.oor = {c: False for c in CHANNELS}
        self.present: set[str] = set()
        self.dev_fs = "-"
        self.paused = False
        cols = []
        for c in CHANNELS:
            cols += [f"{c}_ma_mv", f"{c}_delta_mv", f"{c}_g", f"{c}_mmhg", f"{c}_smooth"]
        self.logger = (CsvLogger(DATA_DIR / f"compression_{timestamp()}.csv", src.info,
                                 extra_cols=cols, note="compression-sensor") if log_csv else None)

    def drain(self) -> None:
        while True:
            try:
                f = self.src.q.get_nowait()
            except queue.Empty:
                break
            t, _gap, _reset = self.tl.update(f)
            self.t.append(t)
            extra = []
            for c in CHANNELS:
                v = f.values.get(c, math.nan)
                if v == v:
                    self.present.add(c)
                self.fault[c] = f.flag(c)
                st = self.chain[c].update(t, v, self.fault[c])
                self.oor[c] = st.out_of_range
                self.raw[c].append(v); self.ma[c].append(st.ma)
                self.low[c].append(st.mmhg if c in self.cal else st.delta)
                self.smooth[c].append(st.smooth)
                extra += [_fmt(st.ma, 1), _fmt(st.delta, 1), _fmt(st.grams, 1), _fmt(st.mmhg), _fmt(st.smooth)]
            if self.logger:
                self.logger.write(f, t, extra)
        while True:
            try:
                d = self.src.diag.get_nowait()
            except queue.Empty:
                break
            if d.startswith("# fs="):
                self.dev_fs = d.split()[1].split("=")[1]
            elif d.startswith(("# WARN", "# INFO")) and ("chest" in d or "back" in d):
                print(d)

    def run(self) -> None:
        plt = setup_matplotlib()
        from matplotlib.animation import FuncAnimation

        has_cal = bool(self.cal)
        fig, (ax, ax_p) = plt.subplots(2, 1, figsize=(11, 7), sharex=True)
        fig.canvas.manager.set_window_title("EMBRACE 압박 (가슴/등)")
        rl = {c: ax.plot([], [], color=COLORS[c], lw=0.7, alpha=0.35)[0] for c in CHANNELS}
        ml = {c: ax.plot([], [], color=COLORS[c], lw=1.4, label=f"{CHANNEL_KO[c]} (이동평균)")[0]
              for c in CHANNELS}
        pl = {c: ax_p.plot([], [], color=COLORS[c], lw=0.8, alpha=0.45)[0] for c in CHANNELS}
        sl = {c: ax_p.plot([], [], color=COLORS[c], lw=1.8, label=CHANNEL_KO[c])[0] for c in CHANNELS}
        if has_cal:
            ax_p.axhspan(*TARGET_MMHG, color="#999", alpha=0.15, lw=0, label="목표 20~30 mmHg")
            ax_p.set_ylabel("mmHg")
            ax_p.set_title(f"② 등가 압력 (감지부 면적 기준)  ·  {CHAIN_TEXT}", loc="left", fontsize=10)
        else:
            ax_p.axhline(0, color="#999", lw=0.6)
            ax_p.set_ylabel("Δ mV")
            ax_p.set_title("② 영점 기준 변화 Δ mV (보정 파일 없음 — calibrate.py 를 먼저 실행)",
                           loc="left", fontsize=10)
        ax.set_title("① 측정값 (mV) — 옅은 선: 기기 원값, 진한 선: 이동평균 0.2 s", loc="left", fontsize=10.5)
        ax.set_ylabel("mV"); ax_p.set_xlabel("시간 (s)")
        ax.legend(loc="upper left", ncol=2)
        ax_p.legend(loc="upper left", ncol=3)
        status = ax.text(0.995, 1.02, "", transform=ax.transAxes, ha="right", va="bottom",
                         fontsize=9, color="#555")
        msg = ax.text(0.5, 0.95, "", transform=ax.transAxes, ha="center", va="top",
                      fontsize=11, color="#c0392b", fontweight="bold")
        now_txt = ax_p.text(0.995, 1.02, "", transform=ax_p.transAxes, ha="right", va="bottom",
                            fontsize=10, color="#333")
        fig.suptitle(f"가슴/등 압박 (ZD10-100, 집중판 없음)  ·  {self.src.label}",
                     x=0.01, ha="left", fontsize=11.5)
        stamp_demo(fig, self.is_demo)
        fig.tight_layout()

        def save(tag: str = "") -> None:
            IMG_DIR.mkdir(exist_ok=True)
            path = IMG_DIR / f"compression_live_{timestamp()}{tag}.png"
            fig.savefig(path, bbox_inches="tight")
            print(f"[저장] {path}")

        def on_key(ev) -> None:
            if ev.key == "s":
                save()
            elif ev.key == "p":
                self.paused = not self.paused
            elif ev.key == "t" and self.t:
                t_end = self.t[-1]
                for c in self.present:
                    xs = [v for tt, v in zip(self.t, self.ma[c]) if tt >= t_end - 1.0 and v == v]
                    if xs:
                        self.chain[c].set_tare(mean(xs))
                print("[영점] " + ", ".join(f"{CHANNEL_KO[c]} {self.chain[c].tare:.0f} mV"
                                          for c in sorted(self.present) if self.chain[c].tare is not None))

        def update(_frame):
            self.drain()
            status.set_text(f"기기 fs {self.dev_fs} | 샘플 {self.tl.received} | 유실 {self.tl.dropped}"
                            + ("  [일시정지]" if self.paused else ""))
            bad = [f"{CHANNEL_KO[c]} {'단선' if self.fault[c] == 1 else '단락'}"
                   for c in CHANNELS if self.fault[c]]
            if self.src.error:
                msg.set_text(self.src.error)
            elif self.tl.received and not self.present:
                msg.set_text("chest / back 채널이 꺼져 있음 (config.h 의 ENABLE_CHEST/BACK 확인)")
            elif bad:
                msg.set_text("센서 이상 — 이 값으로 가압하면 안 됨: " + ", ".join(bad))
            elif self.t and self.t[-1] < TARE_S:
                msg.set_text(f"영점 측정 중… 에어백을 뺀 상태로 기다리세요 ({TARE_S - self.t[-1]:.0f}초)")
            elif any(self.oor[c] for c in self.cal):
                msg.set_text("보정 범위 밖 (환산값은 외삽) — 더 넓은 하중 범위로 다시 보정할 것")
            elif not self.t:
                msg.set_text("기기 헤더 기다리는 중… (최대 5초)")
            else:
                msg.set_text("")
            if self.paused or not self.t:
                return ()
            t_end = self.t[-1]
            t0 = max(0.0, t_end - self.window_s)
            vis, pvis = [], []
            for c in CHANNELS:
                rl[c].set_data(self.t, self.raw[c]); ml[c].set_data(self.t, self.ma[c])
                pl[c].set_data(self.t, self.low[c]); sl[c].set_data(self.t, self.smooth[c])
                vis += [v for tt, v in zip(self.t, self.raw[c]) if tt >= t0 and v == v]
                pvis += [v for tt, v in zip(self.t, self.low[c]) if tt >= t0 and v == v]
            ax.set_xlim(t0, max(t0 + self.window_s, t_end))
            if vis:
                lo, hi = min(vis), max(vis)
                pad = max((hi - lo) * 0.12, 30)
                ax.set_ylim(lo - pad, hi + pad * 2.5)          # 위쪽에 범례 자리
            if pvis:
                lo, hi = min(pvis + [0.0]), max(pvis + [40.0 if has_cal else 20.0])
                ax_p.set_ylim(lo - 0.05 * (hi - lo), hi * 1.25)
            unit = "mmHg" if has_cal else "mV"
            now_txt.set_text("   ".join(
                f"{CHANNEL_KO[c]} {self.smooth[c][-1]:.1f} {unit}" for c in CHANNELS
                if c in self.present and self.smooth[c] and self.smooth[c][-1] == self.smooth[c][-1]))
            return ()

        fig.canvas.mpl_connect("key_press_event", on_key)
        self.anim = FuncAnimation(fig, update, interval=50, blit=False, cache_frame_data=False)
        print("단축키: t=영점, s=PNG 저장, p=일시정지, q=종료")
        self.src.start()
        try:
            plt.show()
        finally:
            if self.t:
                save("_final")
            self.src.stop(); self.src.join(timeout=2)
            if self.logger:
                self.logger.close()
                if self.logger.rows:
                    print(f"[CSV]  {self.logger.path}  ({self.logger.rows} 샘플, 유실 {self.tl.dropped})")


# =============================================================================
#  재생 모드 — CSV → PNG + 통계 (필터 효과 정량화 포함)
# =============================================================================
def run_replay(path: Path, start: float, duration: float | None, show: bool) -> list[Path]:
    explicit = start > 0 or duration is not None     # 사용자가 구간을 골랐을 때만 크리프 계산
    rec = read_csv(path)
    names = [c for c in CHANNELS if rec.has(c)]
    if not names:
        sys.exit(f"[오류] {path} 에 chest / back 열이 없습니다.")
    is_demo = rec.info.header.get("board") == "DEMO" or "DEMO" in path.name.upper()
    cal = load_calibs(names, demo=is_demo)
    t_all = rec.col("elapsed_s")
    # 체인은 파일 처음부터 돌린다 (영점·이동평균이 구간 시작 전에 자리를 잡도록)
    stages = {}
    for c in names:
        ch = CompressionChain(c, cal.get(c))
        stages[c] = [ch.update(tt, v, f) for tt, v, f in zip(t_all, rec.col(c), rec.flags(c))]
    end = start + duration if duration else t_all[-1]
    keep = [i for i, tt in enumerate(t_all) if start <= tt <= end]
    if not keep:
        sys.exit(f"[오류] {start}~{end}s 구간에 데이터가 없습니다.")
    t = [t_all[i] for i in keep]
    flg = {c: [rec.flags(c)[i] for i in keep] for c in names}
    st = {c: [stages[c][i] for i in keep] for c in names}

    print(f"\n[압박 구간 통계] {t[0]:.1f}~{t[-1]:.1f} s (센서 이상 구간 제외)")
    for c in names:
        raw = [s.raw for s, f in zip(st[c], flg[c]) if not f and s.raw == s.raw]
        ma = [s.ma for s, f in zip(st[c], flg[c]) if not f and s.ma == s.ma]
        sr, sm = stdev(raw), stdev(ma)
        print(f"  {CHANNEL_KO[c]:<5} 평균 {mean(raw):7.1f} mV, 범위 {min(raw, default=math.nan):.0f}~"
              f"{max(raw, default=math.nan):.0f} mV, 센서 이상 {100 * sum(1 for f in flg[c] if f) / len(flg[c]):.1f}%")
        print(f"         잡음(표준편차): 기기 원값 {sr:.2f} mV → 이동평균 {sm:.2f} mV "
              f"(÷{sr / sm if sm else math.nan:.1f})" + ("" if explicit else
              "  ※ 전체 구간은 신호 변화가 섞여 의미 없음 — --start/--duration 으로 정지 구간을 고를 것"))
        if explicit and t[-1] - t[0] >= 30:
            a = mean(s.ma for tt, s in zip(t, st[c]) if tt < t[0] + 10)
            b = mean(s.ma for tt, s in zip(t, st[c]) if tt > t[-1] - 10)
            tare = next((x.ma - x.delta for x in st[c] if x.delta == x.delta), math.nan)
            sig = a - tare if tare == tare else a
            if sig == sig and abs(sig) > 10 * (sm if sm == sm else 1.0):
                print(f"         크리프(처음 10 s→마지막 10 s, 영점 제외) {100 * (b - a) / sig:+.1f}%")
            else:
                print("         크리프: 이 구간은 영점과 거의 같아 계산하지 않음 (가압 유지 구간을 지정할 것)")
        if c in cal:
            ps = [s.mmhg for s in st[c] if s.mmhg == s.mmhg]
            if ps:
                print(f"         등가 압력 평균 {mean(ps):.1f} mmHg (최대 {max(ps):.1f})")

    plt = setup_matplotlib(headless=not show)
    fig, (ax, ax_p) = plt.subplots(2, 1, figsize=(11, 7.2), sharex=True)
    for c in names:
        ax.plot(t, [s.raw for s in st[c]], color=COLORS[c], lw=0.6, alpha=0.35)
        ax.plot(t, [s.ma for s in st[c]], color=COLORS[c], lw=1.2, label=f"{CHANNEL_KO[c]} (이동평균)")
        shade_faults(ax, t, flg[c], label=(c == names[0]))
        low = [s.mmhg if c in cal else s.delta for s in st[c]]
        ax_p.plot(t, low, color=COLORS[c], lw=0.7, alpha=0.45)
        ax_p.plot(t, [s.smooth for s in st[c]], color=COLORS[c], lw=1.6, label=CHANNEL_KO[c])
    ax.set_ylabel("mV")
    ax.set_title("① 측정값 (mV) — 옅은 선: 기기 원값, 진한 선: 이동평균 0.2 s", loc="left", fontsize=10.5)
    lo, hi = ax.get_ylim()
    ax.set_ylim(lo, hi + (hi - lo) * 0.25)
    ax.legend(loc="upper left", ncol=3)
    if cal:
        ax_p.axhspan(*TARGET_MMHG, color="#999", alpha=0.15, lw=0, label="목표 20~30 mmHg")
        ax_p.set_ylabel("mmHg")
        ax_p.set_title(f"② 등가 압력 (감지부 면적 기준)  ·  {CHAIN_TEXT}", loc="left", fontsize=10)
    else:
        ax_p.axhline(0, color="#999", lw=0.6)
        ax_p.set_ylabel("Δ mV")
        ax_p.set_title("② 영점 기준 변화 Δ mV (보정 파일 없음)", loc="left", fontsize=10)
    lo, hi = ax_p.get_ylim()
    ax_p.set_ylim(lo, hi + (hi - lo) * 0.25)
    ax_p.legend(loc="upper left", ncol=3)
    ax_p.set_xlabel("시간 (s)")
    ax_p.set_xlim(t[0], t[-1])
    fig.suptitle(f"가슴/등 압박 (집중판 없음)  ·  {path.name}  ({t[0]:.0f}–{t[-1]:.0f} s)",
                 x=0.01, ha="left", fontsize=11)
    stamp_demo(fig, is_demo)
    fig.tight_layout()
    IMG_DIR.mkdir(exist_ok=True)
    out = IMG_DIR / f"{path.stem}_{t[0]:.0f}-{t[-1]:.0f}s_compression.png"
    fig.savefig(out, bbox_inches="tight")
    print(f"[저장] {out}")
    if show:
        plt.show()
    plt.close(fig)
    return [out]


def main() -> None:
    ap = argparse.ArgumentParser(description="EMBRACE 가슴/등 압박 시각화 (집중판 없음)")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--port", help="시리얼 포트 (예: COM5)")
    g.add_argument("--list", action="store_true", help="사용 가능한 포트 목록")
    g.add_argument("--demo", action="store_true", help="보드 없이 모의 데이터로 실행")
    g.add_argument("--replay", type=Path, help="저장된 CSV 로 PNG + 통계")
    ap.add_argument("--baud", type=int, default=DEFAULT_BAUD)
    ap.add_argument("--window", type=float, default=DEFAULT_WINDOW_S, help="화면 시간 폭(s)")
    ap.add_argument("--no-log", action="store_true", help="CSV 기록 끄기")
    ap.add_argument("--start", type=float, default=0.0, help="[replay] 시작 시각(s)")
    ap.add_argument("--duration", type=float, default=None, help="[replay] 구간 길이(s)")
    ap.add_argument("--no-show", action="store_true", help="[replay] 창 없이 PNG 만 저장")
    args = ap.parse_args()

    if args.list:
        list_ports()
        return
    if args.replay:
        run_replay(args.replay, args.start, args.duration, not args.no_show)
        return
    src = open_source(args.port, args.baud, demo=args.demo, device=DemoDevice(channels=CHANNELS))
    LiveApp(src, args.window, not args.no_log, args.demo).run()


if __name__ == "__main__":
    main()
