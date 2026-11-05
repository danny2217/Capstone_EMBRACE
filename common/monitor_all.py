#!/usr/bin/env python3
"""EMBRACE 5채널 통합 모니터 — 통합 시험(TC-F1~F4) · 크로스토크 확인 · 데모용

켜진 채널을 모두 한 화면에 위아래로 그리고, 프레임 전체를 CSV 로 기록한다.
종료하면 시험 증빙용 요약(주기·작업 시간·유실·상태 경고 이력)을 화면과 텍스트 파일로 남긴다.

실행 (저장소 루트에서)
  python common/monitor_all.py --list
  python common/monitor_all.py --port COM5               실시간 (CSV: data/all_*.csv)
  python common/monitor_all.py --demo                    보드 없이 모의 데이터
  python common/monitor_all.py --replay data/all_20261104_101500.csv [--start 10 --duration 30]
                                                         → PNG + 채널별 통계 (TC-F2, TC-C1)

시험별 사용법
  TC-F1 주기   : 5채널 모두 켜고 10분 실행 후 q → 요약의 'fs 범위', '주기 최대', 'work 최대', '유실'
  TC-F2 크로스토크: 나머지 채널에 고정 저항, 한 채널만 0 V ↔ 3.3 V 로 바꾸며 기록 →
                 --replay 로 구간별 통계를 비교 (나머지 채널 평균 변화 20 mV 이하)
  TC-F3 단선·단락: 케이블을 뽑고/꽂으며 기록 → 요약의 '상태 경고 이력' (발생 시각 포함)
  TC-F4 늦은 연결: 보드를 켜고 30초 뒤에 실행 → 요약의 '헤더 수신까지 걸린 시간'

실시간 창 단축키:  s = PNG 저장,  p = 일시정지,  h = 헤더 다시 요청,  q = 종료
"""

from __future__ import annotations

import argparse
import math
import queue
import re
import sys
import time
from collections import deque
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from embrace_link import (CHANNEL_KO, CHANNEL_ORDER, DEFAULT_BAUD, DEFAULT_FS, HEALTH_KO,  # noqa: E402
                          REPO_ROOT, CsvLogger, SerialSource, Source, Timeline, list_ports, mean,
                          open_source, read_csv, stdev, timestamp)
from plotting import COLORS, setup_matplotlib, shade_faults, stamp_demo  # noqa: E402

DATA_DIR = REPO_ROOT / "data"
IMG_DIR = REPO_ROOT / "report_images"
FS_RE = re.compile(r"fs=([\d.]+)Hz avg=(\d+)us min=(\d+)us max=(\d+)us(?: work_max=(\d+)us)?(?: drop=(\d+))?")


class RunSummary:
    """시험 증빙용 집계 — 기기 '# fs' 줄, 상태 경고, 헤더 수신 시각"""

    def __init__(self) -> None:
        self.t_start = time.monotonic()
        self.header_after_s: float | None = None
        self.fs: list[float] = []
        self.p_min: list[int] = []
        self.p_max: list[int] = []
        self.work: list[int] = []
        self.dev_drop = 0
        self.events: list[str] = []

    def on_diag(self, line: str, t_dev: float) -> None:
        m = FS_RE.search(line)
        if m:
            self.fs.append(float(m.group(1)))
            self.p_min.append(int(m.group(3))); self.p_max.append(int(m.group(4)))
            if m.group(5):
                self.work.append(int(m.group(5)))
            if m.group(6):
                self.dev_drop = int(m.group(6))
        elif line.startswith(("# WARN", "# INFO")):
            self.events.append(f"{t_dev:8.2f} s  {line[2:]}")
        elif line.startswith("# columns") and self.header_after_s is None:
            self.header_after_s = time.monotonic() - self.t_start

    def text(self, tl: Timeline) -> str:
        out = ["[통합 모니터 요약]"]
        if self.header_after_s is not None:
            out.append(f"  헤더 수신까지 걸린 시간 : {self.header_after_s:.1f} s   (TC-F4 기준 5 s 이내)")
        out.append(f"  수신 프레임 {tl.received}, PC 가 센 유실 {tl.dropped}, 기기 큐 유실 {self.dev_drop}, "
                   f"보드 리셋 {tl.resets}")
        if self.fs:
            out.append(f"  fs 범위 {min(self.fs):.2f}~{max(self.fs):.2f} Hz ({len(self.fs)}초 분량)")
            out.append(f"  주기 최소 {min(self.p_min)} us / 최대 {max(self.p_max)} us   "
                       "(TC-F1 기준 평균 20000±50, 최대 편차 1000 us 이하)")
        if self.work:
            out.append(f"  ADC 작업 시간 최대 {max(self.work)} us   (TC-F1 기준 5000 us 이하)")
        out.append(f"  상태 경고 이력 ({len(self.events)}건)" + (":" if self.events else ""))
        out += [f"    {e}" for e in self.events]
        return "\n".join(out)


# =============================================================================
#  실시간 모드
# =============================================================================
def run_live(src: Source, window_s: float, log_csv: bool, is_demo: bool) -> None:
    summary = RunSummary()
    tl = Timeline(DEFAULT_FS)
    src.start()
    print("기기 헤더 기다리는 중… (최대 6초)")
    if not src.wait_header(6.5):
        sys.exit("[오류] 헤더(열 이름)를 받지 못했습니다. 펌웨어 v2(embrace_node) 가 올라가 있는지, "
                 "포트가 맞는지 확인하세요.")
    names = [c for c in src.info.value_columns]
    print("채널: " + ", ".join(f"{c}({CHANNEL_KO.get(c, c)})" for c in names))

    n = int(window_s * DEFAULT_FS * 1.2)
    t: deque[float] = deque(maxlen=n)
    vals = {c: deque(maxlen=n) for c in names}
    flags = {c: 0 for c in names}
    state = {"paused": False, "dev_fs": "-"}
    logger = CsvLogger(DATA_DIR / f"all_{timestamp()}.csv", src.info, note="monitor_all") if log_csv else None

    plt = setup_matplotlib()
    from matplotlib.animation import FuncAnimation
    fig, axes = plt.subplots(len(names), 1, figsize=(11, 1.9 * len(names) + 1.2), sharex=True,
                             squeeze=False)
    axes = [a[0] for a in axes]
    lines, labels = {}, {}
    for ax, c in zip(axes, names):
        lines[c] = ax.plot([], [], color=COLORS.get(c, "#333"), lw=1.0)[0]
        ax.set_ylabel("mV")
        ax.set_title(f"{CHANNEL_KO.get(c, c)} ({c}, GPIO{src.info.channels.get(c).params.get('pin', '?') if c in src.info.channels else '?'})",
                     loc="left", fontsize=10)
        labels[c] = ax.text(0.995, 1.02, "", transform=ax.transAxes, ha="right", va="bottom",
                            fontsize=9.5, fontweight="bold")
    axes[-1].set_xlabel("시간 (s)")
    status = fig.text(0.99, 0.995, "", ha="right", va="top", fontsize=9, color="#555")
    fig.suptitle(f"EMBRACE 5채널 통합 모니터  ·  {src.label}", x=0.01, ha="left", fontsize=11.5)
    stamp_demo(fig, is_demo)
    fig.tight_layout(rect=(0, 0, 1, 0.97))

    def drain() -> None:
        while True:
            try:
                f = src.q.get_nowait()
            except queue.Empty:
                break
            el, _g, _r = tl.update(f)
            t.append(el)
            for c in names:
                vals[c].append(f.values.get(c, math.nan))
                flags[c] = f.flag(c)
            if logger:
                logger.write(f, el)
        while True:
            try:
                d = src.diag.get_nowait()
            except queue.Empty:
                break
            summary.on_diag(d, tl.t)
            if d.startswith("# fs="):
                state["dev_fs"] = d.split()[1].split("=")[1]
            elif d.startswith(("# WARN", "# INFO")):
                print(f"[{tl.t:8.2f} s] {d}")

    def save(tag: str = "") -> None:
        IMG_DIR.mkdir(exist_ok=True)
        p = IMG_DIR / f"all_live_{timestamp()}{tag}.png"
        fig.savefig(p, bbox_inches="tight")
        print(f"[저장] {p}")

    def on_key(ev) -> None:
        if ev.key == "s":
            save()
        elif ev.key == "p":
            state["paused"] = not state["paused"]
        elif ev.key == "h" and isinstance(src, SerialSource):
            src.request_header()

    def update(_frame):
        drain()
        status.set_text(f"기기 fs {state['dev_fs']} | 프레임 {tl.received} | 유실 {tl.dropped}"
                        + (f" | {src.error}" if src.error else "")
                        + ("  [일시정지]" if state["paused"] else ""))
        if state["paused"] or not t:
            return ()
        t_end = t[-1]
        t0 = max(0.0, t_end - window_s)
        for ax, c in zip(axes, names):
            lines[c].set_data(t, vals[c])
            vis = [v for tt, v in zip(t, vals[c]) if tt >= t0 and v == v]
            if vis:
                lo, hi = min(vis), max(vis)
                pad = max((hi - lo) * 0.15, 25)
                ax.set_ylim(lo - pad, hi + pad)
                code = flags[c]
                labels[c].set_text(f"{vis[-1]:.0f} mV · {HEALTH_KO.get(code, code)}")
                labels[c].set_color("#c0392b" if code else "#2e7d32")
                ax.set_facecolor("#fdecea" if code else "white")
        axes[0].set_xlim(t0, max(t0 + window_s, t_end))
        return ()

    fig.canvas.mpl_connect("key_press_event", on_key)
    fig._embrace_anim = FuncAnimation(fig, update, interval=50, blit=False, cache_frame_data=False)  # 참조 유지
    print("단축키: s=PNG 저장, p=일시정지, h=헤더 요청, q=종료")
    try:
        plt.show()
    finally:
        drain()
        if t:
            save("_final")
        src.stop(); src.join(timeout=2)
        text = summary.text(tl)
        print("\n" + text)
        if logger:
            logger.close()
            if logger.rows:
                sp = logger.path.with_name(logger.path.stem + "_summary.txt")
                sp.write_text(text + "\n", encoding="utf-8")
                print(f"\n[CSV]  {logger.path}\n[요약] {sp}")


# =============================================================================
#  재생 모드 — CSV → PNG + 채널별 통계
# =============================================================================
def run_replay(path: Path, start: float, duration: float | None, show: bool) -> Path:
    rec = read_csv(path)
    names = [c for c in CHANNEL_ORDER if rec.has(c)]
    if not names:
        sys.exit(f"[오류] {path} 에서 채널 열을 찾지 못했습니다.")
    t_all = rec.col("elapsed_s")
    end = start + duration if duration else t_all[-1]
    keep = [i for i, tt in enumerate(t_all) if start <= tt <= end]
    if not keep:
        sys.exit(f"[오류] {start}~{end}s 구간에 데이터가 없습니다.")
    t = [t_all[i] for i in keep]

    print(f"\n[채널별 통계] {t[0]:.1f}~{t[-1]:.1f} s · {path.name}")
    print(f"  {'채널':<8}{'평균 mV':>9}{'표준편차':>9}{'최소':>7}{'최대':>7}{'이상%':>7}")
    data = {}
    for c in names:
        v_all, f_all = rec.col(c), rec.flags(c)
        v = [v_all[i] for i in keep]; fl = [f_all[i] for i in keep]
        ok = [x for x, f in zip(v, fl) if not f and x == x]
        data[c] = (v, fl)
        print(f"  {CHANNEL_KO[c]:<8}{mean(ok):9.1f}{stdev(ok):9.1f}{min(ok, default=math.nan):7.0f}"
              f"{max(ok, default=math.nan):7.0f}{100 * sum(1 for f in fl if f) / len(fl):7.1f}")

    is_demo = rec.info.header.get("board") == "DEMO" or "DEMO" in path.name.upper()
    plt = setup_matplotlib(headless=not show)
    fig, axes = plt.subplots(len(names), 1, figsize=(11, 1.9 * len(names) + 1.2), sharex=True,
                             squeeze=False)
    for (ax,), c in zip(axes, names):
        v, fl = data[c]
        ax.plot(t, v, color=COLORS.get(c, "#333"), lw=0.9)
        shade_faults(ax, t, fl, label=False)
        ax.set_ylabel("mV")
        ax.set_title(f"{CHANNEL_KO[c]} ({c})", loc="left", fontsize=10)
    axes[-1][0].set_xlabel("시간 (s)")
    axes[-1][0].set_xlim(t[0], t[-1])
    fig.suptitle(f"EMBRACE 5채널  ·  {path.name}  ({t[0]:.0f}–{t[-1]:.0f} s)", x=0.01, ha="left",
                 fontsize=11)
    stamp_demo(fig, is_demo)
    fig.tight_layout()
    IMG_DIR.mkdir(exist_ok=True)
    out = IMG_DIR / f"{path.stem}_{t[0]:.0f}-{t[-1]:.0f}s_all.png"
    fig.savefig(out, bbox_inches="tight")
    print(f"[저장] {out}")
    if show:
        plt.show()
    plt.close(fig)
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description="EMBRACE 5채널 통합 모니터")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--port", help="시리얼 포트 (예: COM5)")
    g.add_argument("--list", action="store_true", help="사용 가능한 포트 목록")
    g.add_argument("--demo", action="store_true", help="보드 없이 모의 데이터로 실행")
    g.add_argument("--replay", type=Path, help="저장된 CSV 로 PNG + 채널별 통계")
    ap.add_argument("--baud", type=int, default=DEFAULT_BAUD)
    ap.add_argument("--window", type=float, default=20.0, help="화면 시간 폭(s)")
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
    run_live(open_source(args.port, args.baud, demo=args.demo), args.window, not args.no_log, args.demo)


if __name__ == "__main__":
    main()
