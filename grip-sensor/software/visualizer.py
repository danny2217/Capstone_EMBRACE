#!/usr/bin/env python3
"""EMBRACE 악력 시각화 (grip-sensor) — 가방끈 좌/우 ZD10-100

ESP32-S3 펌웨어(firmware/embrace_node)의 grip_l, grip_r 열을 받아 실시간으로 그리고,
프레임 전체를 CSV 로 기록한다. 수신·파싱·CSV 는 common/embrace_link.py 가 맡는다.

무엇을 보여 주는가 — "몇 kgf" 가 아니라 "쥐었는가, 얼마나 세게(상대값), 얼마나 오래"
  ① 측정값 (mV)         : 분압 회로 출력. 세게 쥘수록 올라간다
  ② 쥠 지수 (0~1)       : (현재 - 기준선) / (최대 쥠 - 기준선). 착용자별 상대값
                           기준선 = 손을 뗀 상태, 최대 쥠 = 그 아이가 꽉 쥔 상태
  ③ 쥠 구간 (색 띠)      : 지수가 0.30 을 넘으면 시작, 0.15 아래로 내려가면 끝 (히스테리시스)
                           0.2 s 보다 짧은 구간은 버린다
  ※ ③은 보고서·관찰용 표시일 뿐 '전조 검출 알고리즘' 이 아니다 (TC-G2 근거 수집용).

ZD10-100 은 정격 500 g 이다. 아이가 세게 쥐면 감지부 하중이 정격을 넘어 값이 평평해질 수 있다.
그래도 쥠 여부·지속 시간은 남는다. 보고서에는 절대 악력(kgf)을 쓰지 않는다.

실행
  python visualizer.py --list
  python visualizer.py --port COM5               실시간 (처음 3초는 손을 떼고 기다린다 → 기준선)
  python visualizer.py --demo                    보드 없이 모의 데이터
  python visualizer.py --replay ../data/grip_20261105_101500.csv [--start 0 --duration 60] [--no-show]
                                                 → PNG + 구간 통계 (TC-G1, TC-G2)

실시간 창 단축키
  b : 지금부터 앞 2초 평균을 기준선으로 (손을 뗀 상태에서 누른다)
  m : 앞 3초의 최대값을 '최대 쥠' 으로 (꽉 쥐었다 놓은 직후 누른다) → data/grip_calib.json 저장
  s : PNG 저장     p : 일시정지     q : 종료
"""

from __future__ import annotations

import argparse
import json
import math
import queue
import sys
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT.parent / "common"))
from embrace_link import (CHANNEL_KO, DEFAULT_BAUD, DEFAULT_FS, CsvLogger, DemoDevice,  # noqa: E402
                          Source, Timeline, list_ports, mean, open_source, read_csv, stdev,
                          timestamp)
from plotting import COLORS, setup_matplotlib, shade_faults, stamp_demo  # noqa: E402

DATA_DIR = ROOT / "data"
IMG_DIR = ROOT / "report_images"
CALIB_PATH = DATA_DIR / "grip_calib.json"
CHANNELS = ("grip_l", "grip_r")

BASELINE_S = 3.0        # 시작 후 기준선 자동 측정 시간
ON_IDX = 0.30           # 쥠 시작 문턱 (지수)
OFF_IDX = 0.15          # 쥠 끝 문턱
MIN_GRIP_S = 0.2        # 이보다 짧은 쥠은 무시
MIN_SPAN_MV = 150.0     # 최대 쥠을 아직 모를 때 쓰는 최소 분모 (mV)
BASE_DOWN_RATE = 0.05   # 기준선 하향 추적 속도 (샘플당 비율, 50 Hz 에서 약 0.4 s 시상수)
DEFAULT_WINDOW_S = 20.0


# =============================================================================
#  채널 하나의 쥠 분석 — 실시간과 재생이 같은 클래스를 쓴다
# =============================================================================
@dataclass
class Grip:
    start: float
    end: float
    peak_idx: float
    peak_mv: float

    @property
    def dur(self) -> float:
        return self.end - self.start


@dataclass
class GripAnalyzer:
    name: str
    baseline: float | None = None
    max_mv: float | None = None          # 최대 쥠 (보정값). 없으면 지금까지의 최고값을 쓴다
    _base_buf: list = field(default_factory=list)
    _seen_max: float = 0.0
    on: bool = False
    _cur: Grip | None = None
    grips: list = field(default_factory=list)

    def ref_max(self) -> float:
        base = self.baseline or 0.0
        top = self.max_mv if self.max_mv is not None else self._seen_max
        return max(top, base + MIN_SPAN_MV)

    def index(self, mv: float) -> float:
        if self.baseline is None:
            return 0.0
        return min(max((mv - self.baseline) / (self.ref_max() - self.baseline), 0.0), 1.0)

    def update(self, t: float, mv: float, fault: int) -> float:
        """샘플 하나 반영 → 쥠 지수. 센서 이상(fault) 중에는 쥠 판정을 멈춘다."""
        if self.baseline is None:
            if not fault:
                self._base_buf.append(mv)
            if t >= BASELINE_S and self._base_buf:
                # 하위 10% 값 — 측정 중 아이가 잠깐 쥐어도 기준선이 끌려 올라가지 않는다
                self.baseline = sorted(self._base_buf)[len(self._base_buf) // 10]
            return 0.0
        if not fault:
            self._seen_max = max(self._seen_max, mv)
            if not self.on and mv < self.baseline:
                # 기준선은 아래로만 따라간다 (처음에 쥔 채로 시작했어도 손을 놓으면 바로잡힘)
                self.baseline += (mv - self.baseline) * BASE_DOWN_RATE
        idx = 0.0 if fault else self.index(mv)
        if not self.on and idx >= ON_IDX:
            self.on, self._cur = True, Grip(t, t, idx, mv)
        elif self.on:
            self._cur.end = t
            self._cur.peak_idx = max(self._cur.peak_idx, idx)
            self._cur.peak_mv = max(self._cur.peak_mv, mv)
            if idx <= OFF_IDX or fault:
                self.on = False
                if self._cur.dur >= MIN_GRIP_S:
                    self.grips.append(self._cur)
                self._cur = None
        return idx


def load_calib() -> dict:
    try:
        return json.loads(CALIB_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_calib(an: dict[str, GripAnalyzer]) -> None:
    DATA_DIR.mkdir(exist_ok=True)
    data = {k: {"baseline_mv": a.baseline, "max_mv": a.max_mv} for k, a in an.items()}
    data["saved"] = timestamp()
    CALIB_PATH.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[보정 저장] {CALIB_PATH}")


def make_analyzers(names, use_calib: bool) -> dict[str, GripAnalyzer]:
    calib = load_calib() if use_calib else {}
    out = {}
    for n in names:
        a = GripAnalyzer(n)
        c = calib.get(n) or {}
        if c.get("max_mv"):
            a.max_mv = float(c["max_mv"])
        out[n] = a
    if calib:
        print(f"[보정] {CALIB_PATH.name} 의 최대 쥠 값을 씁니다 (기준선은 매번 새로 잰다)")
    return out


def print_summary(an: dict[str, GripAnalyzer], rec_vals: dict[str, list[float]] | None = None,
                  faults: dict[str, list[int]] | None = None,
                  idx: dict[str, list[float]] | None = None) -> None:
    print("\n[악력 요약]")
    for n, a in an.items():
        durs = [g.dur for g in a.grips]
        line = (f"  {CHANNEL_KO[n]:<5} 기준선 {a.baseline or float('nan'):7.0f} mV | "
                f"최대 쥠 기준 {a.ref_max():6.0f} mV | 쥠 {len(a.grips)}회")
        if durs:
            line += (f", 평균 {mean(durs):.2f} s (최대 {max(durs):.2f} s), "
                     f"최고 {max(g.peak_mv for g in a.grips):.0f} mV")
        print(line)
        if rec_vals and n in rec_vals:
            v = rec_vals[n]
            fl = faults.get(n, []) if faults else [0] * len(v)
            ix = idx.get(n, []) if idx else []
            # 무부하 노이즈 = 쥐지 않았고(지수 0.05 미만) 센서도 정상인 구간의 표준편차 (기준선 측정 구간 제외)
            skip = int(BASELINE_S * DEFAULT_FS)
            rest = [x for k, (x, i, f) in enumerate(zip(v, ix, fl)) if k >= skip and i < 0.05 and not f]
            print(f"         무부하 노이즈(표준편차) {stdev(rest):.1f} mV, 최대 {max(v):.0f} mV, "
                  f"센서 이상 {100 * sum(1 for x in fl if x) / max(len(fl), 1):.1f}%")


# =============================================================================
#  실시간 모드
# =============================================================================
class LiveApp:
    def __init__(self, src: Source, window_s: float, log_csv: bool, use_calib: bool,
                 is_demo: bool) -> None:
        self.src = src
        self.window_s = window_s
        self.is_demo = is_demo
        self.tl = Timeline(DEFAULT_FS)
        self.an = make_analyzers(CHANNELS, use_calib)
        n = int(window_s * DEFAULT_FS * 1.2)
        self.t: deque[float] = deque(maxlen=n)
        self.mv = {c: deque(maxlen=n) for c in CHANNELS}
        self.idx = {c: deque(maxlen=n) for c in CHANNELS}
        self.fault = {c: 0 for c in CHANNELS}
        self.present: set[str] = set()
        self.dev_fs = "-"
        self.paused = False
        self.logger = (CsvLogger(DATA_DIR / f"grip_{timestamp()}.csv", src.info,
                                 extra_cols=["idx_l", "idx_r", "grip_l_on", "grip_r_on"],
                                 note="grip-sensor") if log_csv else None)

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
                v = f.values.get(c)
                if v is None:
                    self.mv[c].append(math.nan); self.idx[c].append(math.nan)
                    extra.append("")
                    continue
                self.present.add(c)
                self.fault[c] = f.flag(c)
                i = self.an[c].update(t, v, self.fault[c])
                self.mv[c].append(v); self.idx[c].append(i)
                extra.append(f"{i:.3f}")
            extra += [int(self.an[c].on) for c in CHANNELS]
            if self.logger:
                self.logger.write(f, t, extra)
        while True:
            try:
                d = self.src.diag.get_nowait()
            except queue.Empty:
                break
            if d.startswith("# fs="):
                self.dev_fs = d.split()[1].split("=")[1]
            elif d.startswith(("# WARN", "# INFO")) and ("grip" in d):
                print(d)

    def run(self) -> None:
        plt = setup_matplotlib()
        from matplotlib.animation import FuncAnimation

        fig, (ax, ax_i) = plt.subplots(2, 1, figsize=(11, 7), sharex=True)
        fig.canvas.manager.set_window_title("EMBRACE 악력 (가방끈)")
        lines = {c: ax.plot([], [], color=COLORS[c], lw=1.1, label=CHANNEL_KO[c])[0] for c in CHANNELS}
        ilines = {c: ax_i.plot([], [], color=COLORS[c], lw=1.4, label=CHANNEL_KO[c])[0]
                  for c in CHANNELS}
        ax.set_title("① 측정값 (mV) — 세게 쥘수록 올라감", loc="left", fontsize=10.5)
        ax_i.set_title(f"② 쥠 지수 (0~1, 시작 {ON_IDX} / 끝 {OFF_IDX})", loc="left", fontsize=10.5)
        ax.set_ylabel("mV"); ax_i.set_ylabel("쥠 지수"); ax_i.set_xlabel("시간 (s)")
        ax_i.set_ylim(-0.05, 1.08)
        ax_i.axhline(ON_IDX, color="#999", lw=0.7, ls="--")
        ax_i.axhline(OFF_IDX, color="#bbb", lw=0.7, ls=":")
        ax.legend(loc="upper left", ncol=2)
        status = ax.text(0.995, 1.02, "", transform=ax.transAxes, ha="right", va="bottom",
                         fontsize=9, color="#555")
        msg = ax.text(0.5, 0.95, "", transform=ax.transAxes, ha="center", va="top",
                      fontsize=11, color="#c0392b", fontweight="bold")
        state = ax_i.text(0.995, 1.02, "", transform=ax_i.transAxes, ha="right", va="bottom",
                          fontsize=10, color="#333")
        fig.suptitle(f"가방끈 악력 (ZD10-100)  ·  {self.src.label}", x=0.01, ha="left", fontsize=11.5)
        stamp_demo(fig, self.is_demo)
        fig.tight_layout()
        spans: list = []

        def save(tag: str = "") -> None:
            IMG_DIR.mkdir(exist_ok=True)
            path = IMG_DIR / f"grip_live_{timestamp()}{tag}.png"
            fig.savefig(path, bbox_inches="tight")
            print(f"[저장] {path}")

        def recent(c: str, seconds: float) -> list[float]:
            if not self.t:
                return []
            t_end = self.t[-1]
            return [v for tt, v in zip(self.t, self.mv[c]) if tt >= t_end - seconds and v == v]

        def on_key(ev) -> None:
            if ev.key == "s":
                save()
            elif ev.key == "p":
                self.paused = not self.paused
            elif ev.key == "b":
                for c in self.present:
                    xs = recent(c, 2.0)
                    if xs:
                        self.an[c].baseline = mean(xs)
                print("[기준선] " + ", ".join(f"{CHANNEL_KO[c]} {self.an[c].baseline:.0f} mV"
                                            for c in sorted(self.present)))
            elif ev.key == "m":
                for c in self.present:
                    xs = recent(c, 3.0)
                    if xs:
                        self.an[c].max_mv = max(xs)
                print("[최대 쥠] " + ", ".join(f"{CHANNEL_KO[c]} {self.an[c].max_mv:.0f} mV"
                                             for c in sorted(self.present)))
                save_calib(self.an)

        def update(_frame):
            self.drain()
            status.set_text(f"기기 fs {self.dev_fs} | 샘플 {self.tl.received} | 유실 {self.tl.dropped}"
                            + ("  [일시정지]" if self.paused else ""))
            if self.src.error:
                msg.set_text(self.src.error)
            elif self.tl.received and not self.present:
                msg.set_text("grip_l / grip_r 채널이 꺼져 있음 (config.h 의 ENABLE_GRIP_* 확인)")
            elif any(self.fault[c] for c in self.present):
                bad = [f"{CHANNEL_KO[c]} {'단선' if self.fault[c] == 1 else '단락'}"
                       for c in sorted(self.present) if self.fault[c]]
                msg.set_text("센서 이상: " + ", ".join(bad))
            elif self.t and self.t[-1] < BASELINE_S:
                msg.set_text(f"기준선 측정 중… 손을 떼고 기다리세요 ({BASELINE_S - self.t[-1]:.0f}초)")
            elif not self.t:
                msg.set_text("기기 헤더 기다리는 중… (최대 5초)")
            else:
                msg.set_text("")
            if self.paused or not self.t:
                return ()
            t_end = self.t[-1]
            t0 = max(0.0, t_end - self.window_s)
            vis = []
            for c in CHANNELS:
                lines[c].set_data(self.t, self.mv[c]); ilines[c].set_data(self.t, self.idx[c])
                vis += [v for tt, v in zip(self.t, self.mv[c]) if tt >= t0 and v == v]
            for s in spans:
                s.remove()
            spans.clear()
            for c in CHANNELS:
                a = self.an[c]
                segs = [(g.start, g.end) for g in a.grips if g.end >= t0]
                if a.on and a._cur:
                    segs.append((a._cur.start, t_end))
                for s0, s1 in segs:
                    spans.append(ax_i.axvspan(s0, s1, color=COLORS[c], alpha=0.10, lw=0))
            ax.set_xlim(t0, max(t0 + self.window_s, t_end))
            if vis:
                lo, hi = min(vis), max(vis)
                pad = max((hi - lo) * 0.1, 30)
                ax.set_ylim(lo - pad, hi + pad)
            state.set_text("   ".join(
                f"{CHANNEL_KO[c]}: {'쥠' if self.an[c].on else '-'} ({len(self.an[c].grips)}회)"
                for c in CHANNELS if c in self.present))
            return ()

        fig.canvas.mpl_connect("key_press_event", on_key)
        self.anim = FuncAnimation(fig, update, interval=50, blit=False, cache_frame_data=False)
        print("단축키: b=기준선, m=최대 쥠 저장, s=PNG, p=일시정지, q=종료")
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
            print_summary({c: a for c, a in self.an.items() if c in self.present})


# =============================================================================
#  재생 모드 — CSV → PNG + 통계
# =============================================================================
def run_replay(path: Path, start: float, duration: float | None, show: bool,
               use_calib: bool) -> list[Path]:
    rec = read_csv(path)
    names = [c for c in CHANNELS if rec.has(c)]
    if not names:
        sys.exit(f"[오류] {path} 에 grip_l / grip_r 열이 없습니다.")
    t = rec.col("elapsed_s")
    vals = {c: rec.col(c) for c in names}
    faults = {c: rec.flags(c) for c in names}
    an = make_analyzers(names, use_calib)
    idx = {c: [an[c].update(tt, v, fl) for tt, v, fl in zip(t, vals[c], faults[c])] for c in names}
    print_summary(an, vals, faults, idx)

    end = start + duration if duration else t[-1]
    keep = [i for i, tt in enumerate(t) if start <= tt <= end]
    if not keep:
        sys.exit(f"[오류] {start}~{end}s 구간에 데이터가 없습니다.")
    tk = [t[i] for i in keep]
    is_demo = rec.info.header.get("board") == "DEMO" or "DEMO" in path.name.upper()

    plt = setup_matplotlib(headless=not show)
    fig, (ax, ax_i) = plt.subplots(2, 1, figsize=(11, 6.5), sharex=True)
    for c in names:
        ax.plot(tk, [vals[c][i] for i in keep], color=COLORS[c], lw=0.9, label=CHANNEL_KO[c])
        ax_i.plot(tk, [idx[c][i] for i in keep], color=COLORS[c], lw=1.3, label=CHANNEL_KO[c])
        for g in an[c].grips:
            if g.end >= tk[0] and g.start <= tk[-1]:
                ax_i.axvspan(g.start, g.end, color=COLORS[c], alpha=0.12, lw=0)
        shade_faults(ax, tk, [faults[c][i] for i in keep], label=(c == names[0]))
    ax.set_title("① 측정값 (mV)", loc="left", fontsize=10.5)
    ax_i.set_title(f"② 쥠 지수와 쥠 구간 (시작 {ON_IDX} / 끝 {OFF_IDX}, 최소 {MIN_GRIP_S} s)",
                   loc="left", fontsize=10.5)
    ax_i.axhline(ON_IDX, color="#999", lw=0.7, ls="--")
    ax_i.set_ylim(-0.05, 1.08)
    ax.set_ylabel("mV"); ax_i.set_ylabel("쥠 지수"); ax_i.set_xlabel("시간 (s)")
    ax.legend(loc="upper left", ncol=3)
    ax_i.set_xlim(tk[0], tk[-1])
    counts = ", ".join(f"{CHANNEL_KO[c]} {sum(1 for g in an[c].grips if tk[0] <= g.start <= tk[-1])}회"
                       for c in names)
    fig.suptitle(f"가방끈 악력  ·  {path.name}  ({tk[0]:.0f}–{tk[-1]:.0f} s)  ·  쥠 {counts}",
                 x=0.01, ha="left", fontsize=11)
    stamp_demo(fig, is_demo)
    fig.tight_layout()
    IMG_DIR.mkdir(exist_ok=True)
    out = IMG_DIR / f"{path.stem}_{tk[0]:.0f}-{tk[-1]:.0f}s_grip.png"
    fig.savefig(out, bbox_inches="tight")
    print(f"[저장] {out}")
    if show:
        plt.show()
    plt.close(fig)
    return [out]


def main() -> None:
    ap = argparse.ArgumentParser(description="EMBRACE 가방끈 악력 시각화")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--port", help="시리얼 포트 (예: COM5)")
    g.add_argument("--list", action="store_true", help="사용 가능한 포트 목록")
    g.add_argument("--demo", action="store_true", help="보드 없이 모의 데이터로 실행")
    g.add_argument("--replay", type=Path, help="저장된 CSV 로 PNG + 통계")
    ap.add_argument("--baud", type=int, default=DEFAULT_BAUD)
    ap.add_argument("--window", type=float, default=DEFAULT_WINDOW_S, help="화면 시간 폭(s)")
    ap.add_argument("--no-log", action="store_true", help="CSV 기록 끄기")
    ap.add_argument("--no-calib", action="store_true", help="grip_calib.json 을 쓰지 않는다")
    ap.add_argument("--start", type=float, default=0.0, help="[replay] 시작 시각(s)")
    ap.add_argument("--duration", type=float, default=None, help="[replay] 구간 길이(s)")
    ap.add_argument("--no-show", action="store_true", help="[replay] 창 없이 PNG 만 저장")
    args = ap.parse_args()

    if args.list:
        list_ports()
        return
    if args.replay:
        run_replay(args.replay, args.start, args.duration, not args.no_show, not args.no_calib)
        return
    src = open_source(args.port, args.baud, demo=args.demo, device=DemoDevice(channels=CHANNELS))
    LiveApp(src, args.window, not args.no_log, not args.no_calib, args.demo).run()


if __name__ == "__main__":
    main()
