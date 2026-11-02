#!/usr/bin/env python3
"""EMBRACE 압박 채널 보정 · 전환 판정 (TC-C2) — ZD10-100, 집중판 없이 감지부 단독 하중

감지부에 하중을 올려 'mV ↔ g' 표를 만들고, 설계 계획서의 전환 기준을 자동으로 판정한다.
결과는 data/calib_<채널>_latest.json (visualizer 가 읽음) 과 PNG 로 남는다.

보정 지그 (보정할 때만 쓰고 조끼에는 넣지 않는다)
  감지부 중앙에 Ø8 mm 누름 돌기(가구용 투명 실리콘 범퍼)를 얹고, 그 위에 500원 동전(개당 7.7 g)을
  쌓는다. 돌기 무게는 주방 저울로 재서 --pusher-g 로 넣는다 (모든 단계에 자동으로 더해짐).
  기본 단계 0 / 15.4 / 23.1 / 30.8 / 46.2 g = 동전 0 / 2 / 3 / 4 / 6 개.

전환 기준 (하나라도 실패하면 XGZP6847A010KPG33 공압 센서로 전환)
  반복성 : 같은 하중 5회 반복 편차 ±10% 이내               (정적 보정 모드)
  분해능 : 20 mmHg 와 30 mmHg 상당 하중의 출력 차 ≥ 노이즈 표준편차 × 3  (정적 보정 모드)
  크리프 : 동전 4개(30.8 g)를 5분 유지했을 때 출력 변화 10% 이내 (--creep 모드)

등가 하중 (감지부 Ø10 mm 가정): 20 mmHg ≈ 21.3 g, 30 mmHg ≈ 32.0 g → 동전 3개(23.1 g)와 4개(30.8 g).
감지부 지름은 펌웨어 헤더(sense_mm) 값을 쓴다. 실측 지름이 다르면 config.h 를 고치거나 --sense-mm.

실행 예
  정적 보정 (약 15분)
    python calibrate.py --port COM5 --channel chest
    python calibrate.py --port COM5 --channel chest --pusher-g 0.4 --repeat 5
  크리프 (5분, 동전 4개)
    python calibrate.py --port COM5 --channel chest --creep --load 30.8 --hold 300
  보드 없이 절차 연습
    python calibrate.py --demo --channel chest --yes --repeat 2 --settle 0.5 --measure 1

진행 방법: 화면 안내대로 추를 올리고 Enter. 각 단계는 settle 초 기다린 뒤 measure 초 평균을 낸다.
오름차순(0→46 g)과 내림차순(46→0 g)을 모두 재서 이력(hysteresis)도 함께 구한다.
"""

from __future__ import annotations

import argparse
import json
import math
import queue
import random
import sys
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT.parent / "common"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from calib_model import DATA_DIR, latest_path  # noqa: E402
from embrace_link import (CHANNEL_KO, DEFAULT_BAUD, CsvLogger, DemoDevice, DemoSource,  # noqa: E402
                          Timeline, grams_to_mmhg, list_ports, mean, mmhg_to_grams, open_source,
                          stdev, timestamp)

IMG_DIR = ROOT / "report_images"
COIN_G = 7.7            # 500원 동전 1개 무게 (g)


def load_label(g: float, pusher: float) -> str:
    """'500원 동전 3개' 처럼 사람이 올릴 물건으로 안내한다"""
    coins = (g - pusher) / COIN_G
    if g - pusher <= 0.05:
        return "동전을 모두 내리세요 (누름 돌기만 남김)" if pusher else "하중을 모두 내리세요"
    if abs(coins - round(coins)) < 0.05:
        return f"500원 동전 {round(coins)}개를 올리세요 (총 {g:.1f} g)"
    return f"감지부에 {g:.1f} g 을 올리세요"

# 전환 기준 (설계 계획서 '대체 센서와 전환 기준' 절)
MAX_REPEAT_PCT = 10.0
MIN_RESOLUTION = 3.0
MAX_CREEP_PCT = 10.0


# =============================================================================
#  측정 세션 — 프레임을 계속 받아 두고, 요청한 구간의 평균을 낸다
# =============================================================================
class Session:
    def __init__(self, src, channel: str, log_path: Path) -> None:
        self.src = src
        self.ch = channel
        self.tl = Timeline()
        self.t = 0.0
        self.buf: list[tuple[float, float, int]] = []     # (t, mV, flag)
        self.marker = ""
        self.logger = CsvLogger(log_path, src.info, extra_cols=["marker"], note=f"calibrate {channel}")
        self.on_tick = None                                # 데모용 (크리프 흉내)

    def pump(self) -> None:
        while True:
            try:
                f = self.src.q.get_nowait()
            except queue.Empty:
                break
            self.t, _gap, _reset = self.tl.update(f)
            v = f.values.get(self.ch)
            if v is not None:
                self.buf.append((self.t, v, f.flag(self.ch)))
            self.logger.write(f, self.t, [self.marker])
        while True:
            try:
                d = self.src.diag.get_nowait()
            except queue.Empty:
                break
            if d.startswith(("# WARN", "# INFO")) and self.ch in d:
                print("   " + d)
        if self.src.error:
            sys.exit(f"[오류] {self.src.error}")

    def wait(self, seconds: float) -> None:
        t_end = time.monotonic() + seconds
        while time.monotonic() < t_end:
            self.pump()
            if self.on_tick:
                self.on_tick(self.t)
            time.sleep(0.02)
        self.pump()

    def measure(self, settle: float, seconds: float) -> dict:
        self.wait(settle)
        t0 = self.t
        self.wait(seconds)
        pts = [(v, f) for t, v, f in self.buf if t > t0]
        vals = [v for v, f in pts if not f]
        faults = sum(1 for _, f in pts if f)
        return {"mean": mean(vals), "std": stdev(vals), "n": len(vals), "faults": faults}

    def close(self) -> None:
        self.logger.close()


def ask(msg: str, auto: bool) -> None:
    if auto:
        print(msg + "  (자동 진행)")
        return
    try:
        input(msg + "  → 준비되면 Enter ")
    except EOFError:
        pass


# =============================================================================
#  정적 보정
# =============================================================================
def run_static(ses: Session, steps: list[float], repeat: int, settle: float, meas: float,
               auto: bool, demo: DemoDevice | None, sense_mm: float, pusher: float) -> dict:
    rng = random.Random(3)
    seq_up = steps
    seq_down = list(reversed(steps[:-1]))
    readings = []
    for r in range(1, repeat + 1):
        for direction, seq in (("up", seq_up), ("down", seq_down)):
            for g in seq:
                what = load_label(g, pusher)
                ask(f"[{r}/{repeat} {'올림' if direction == 'up' else '내림'}] {what}", auto)
                if demo:   # 모의: 반복마다 ±3% 흔들림, 내릴 때 +5% 이력
                    k = (1 + rng.gauss(0, 0.03)) * (1.05 if direction == "down" and g else 1.0)
                    demo.set_load(ses.ch, g * k)
                ses.marker = f"r{r}_{direction}_{g:.1f}g"
                m = ses.measure(settle, meas)
                ses.marker = ""
                flag = "  ⚠ 센서 이상 샘플 포함 — 접촉 확인 후 다시 재는 것을 권장" if m["faults"] else ""
                print(f"     {g:6.1f} g → {m['mean']:7.1f} mV (±{m['std']:.1f}, n={m['n']}){flag}")
                readings.append({"rep": r, "dir": direction, "grams": g, **m})

    # --- 분석 -----------------------------------------------------------------
    up = {g: [x["mean"] for x in readings if x["dir"] == "up" and x["grams"] == g] for g in steps}
    down = {g: [x["mean"] for x in readings if x["dir"] == "down" and x["grams"] == g] for g in steps}
    up_mean = {g: mean(v) for g, v in up.items()}
    noise = mean(x["std"] for x in readings)
    span = up_mean[steps[-1]] - up_mean[steps[0]]

    repeat_pct = {}
    for g in steps:
        m = up_mean[g]
        if g > 0 and m and len(up[g]) >= 2:
            repeat_pct[g] = max(abs(x - m) for x in up[g]) / m * 100
    hyst_pct = {}
    for g in steps:
        if down.get(g) and span:
            hyst_pct[g] = (mean(down[g]) - up_mean[g]) / span * 100

    g20, g30 = mmhg_to_grams(20, sense_mm), mmhg_to_grams(30, sense_mm)
    near = lambda target: min(steps, key=lambda s: abs(s - target))     # noqa: E731
    s20, s30 = near(g20), near(g30)
    resolution = (up_mean[s30] - up_mean[s20]) / noise if noise and s30 != s20 else math.nan
    if s20 == s30 or abs(s20 - g20) > 0.15 * g20 or abs(s30 - g30) > 0.15 * g30:
        print(f"[참고] 20/30 mmHg 상당 하중은 {g20:.1f} g / {g30:.1f} g 이다. "
              f"--steps 에 그 근처 값을 넣어야 분해능 판정이 정확하다.")

    xs = steps
    ys = [up_mean[g] for g in steps]
    mx, my = mean(xs), mean(ys)
    sxx = sum((x - mx) ** 2 for x in xs)
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx if sxx else math.nan
    ss_res = sum((y - (my + slope * (x - mx))) ** 2 for x, y in zip(xs, ys))
    ss_tot = sum((y - my) ** 2 for y in ys)
    r2 = 1 - ss_res / ss_tot if ss_tot else math.nan
    monotonic = all(b > a for a, b in zip(ys, ys[1:]))

    criteria = {
        "repeatability": {"value_pct": max(repeat_pct.values()) if repeat_pct else math.nan,
                          "limit_pct": MAX_REPEAT_PCT},
        "resolution": {"value_x_noise": resolution, "limit_x_noise": MIN_RESOLUTION,
                       "grams_20mmhg": s20, "grams_30mmhg": s30},
    }
    criteria["repeatability"]["pass"] = criteria["repeatability"]["value_pct"] <= MAX_REPEAT_PCT
    criteria["resolution"]["pass"] = resolution >= MIN_RESOLUTION
    # 응답 하한 확인: 가장 작은 0 아닌 하중에서 무부하 대비 출력이 노이즈의 3배 이상 움직였는가
    first = next((g for g in steps if g > steps[0]), None)
    if first is not None and noise:
        lift = (up_mean[first] - up_mean[steps[0]]) / noise
        criteria["response_floor"] = {"grams": first, "lift_x_noise": lift, "pass": lift >= MIN_RESOLUTION}
    return {
        "readings": readings,
        "table": {"grams": steps, "mv_mean": ys,
                  "mv_sd_between_reps": [stdev(up[g]) for g in steps]},
        "metrics": {"noise_sd_mv": noise, "span_mv": span, "linear_r2": r2,
                    "slope_mv_per_g": slope, "monotonic": monotonic,
                    "repeatability_pct": {str(k): v for k, v in repeat_pct.items()},
                    "hysteresis_pct_of_span": {str(k): v for k, v in hyst_pct.items()}},
        "criteria": criteria,
    }


def plot_static(res: dict, ch: str, sense_mm: float, out: Path, is_demo: bool) -> None:
    from plotting import setup_matplotlib, stamp_demo
    plt = setup_matplotlib(headless=True)
    fig, ax = plt.subplots(figsize=(8, 5))
    for d, mk, color, label in (("up", "o", "#c0392b", "올림"), ("down", "s", "#2e6fbf", "내림")):
        pts = [(x["grams"], x["mean"]) for x in res["readings"] if x["dir"] == d]
        ax.plot([p[0] for p in pts], [p[1] for p in pts], ls="", marker=mk, ms=5, alpha=0.6,
                color=color, label=f"{label} (반복 측정)")
    tb = res["table"]
    ax.plot(tb["grams"], tb["mv_mean"], color="#333", lw=1.2, label="올림 평균 (환산표)")
    g20, g30 = mmhg_to_grams(20, sense_mm), mmhg_to_grams(30, sense_mm)
    ax.axvspan(g20, g30, color="#999", alpha=0.15, lw=0, label="20~30 mmHg 상당")
    ax.set_xlabel(f"감지부 하중 (g)  ·  감지부 Ø{sense_mm} mm, 집중판 없음")
    ax.set_ylabel("출력 (mV)")
    c = res["criteria"]
    ax.set_title(f"{CHANNEL_KO[ch]} 보정곡선 · 반복성 {c['repeatability']['value_pct']:.1f}% · "
                 f"분해능 ×{c['resolution']['value_x_noise']:.1f} · R² {res['metrics']['linear_r2']:.3f}",
                 loc="left", fontsize=10.5)
    sec = ax.secondary_xaxis("top", functions=(lambda g: grams_to_mmhg(g, sense_mm),
                                               lambda p: mmhg_to_grams(p, sense_mm)))
    sec.set_xlabel("등가 압력 (mmHg)")
    ax.legend(fontsize=9, loc="upper left")
    stamp_demo(fig, is_demo)
    fig.tight_layout()
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


# =============================================================================
#  크리프
# =============================================================================
def run_creep(ses: Session, load: float, hold: float, auto: bool, demo: DemoDevice | None) -> dict:
    ask("[크리프] 추를 모두 내리세요 (무부하 기준값)", auto)
    if demo:
        demo.set_load(ses.ch, 0)
    zero = ses.measure(2, 3)
    ask(f"[크리프] 감지부에 {load:.1f} g 을 올리세요 (500원 동전 {load / COIN_G:.0f}개 상당). "
        f"올린 순간부터 {hold:.0f} 초 동안 건드리지 마세요", auto)
    if demo:   # 모의: 유지 시간 동안 4% 감소
        demo.set_load(ses.ch, load)
        t_start = ses.t
        ses.on_tick = lambda t: demo.set_load(ses.ch, load * (1 - 0.04 * min((t - t_start) / hold, 1)))
    t0 = ses.t
    ses.marker = f"creep_{load:.0f}g"
    last_print = 0.0
    while ses.t - t0 < hold:
        ses.wait(0.5)
        if ses.t - t0 - last_print >= 30:
            last_print = ses.t - t0
            cur = [v for t, v, f in ses.buf if t > ses.t - 2 and not f]
            print(f"     {last_print:4.0f} s  {mean(cur):7.1f} mV")
    ses.marker = ""
    ses.on_tick = None
    pts = [(t - t0, v, f) for t, v, f in ses.buf if t >= t0]
    a = mean(v for t, v, f in pts if 10 <= t < 20 and not f)       # 올린 직후 손 떨림 10 s 제외
    b = mean(v for t, v, f in pts if t > hold - 10 and not f)
    pct = (b - a) / (a - zero["mean"]) * 100 if a and a != zero["mean"] else math.nan
    return {"load_g": load, "hold_s": hold, "zero_mv": zero["mean"], "start_mv": a, "end_mv": b,
            "change_pct_of_signal": pct, "limit_pct": MAX_CREEP_PCT,
            "pass": abs(pct) <= MAX_CREEP_PCT,
            "curve": [(round(t, 2), v) for t, v, f in pts[:: max(1, len(pts) // 600)] if not f]}


def plot_creep(res: dict, ch: str, out: Path, is_demo: bool) -> None:
    from plotting import setup_matplotlib, stamp_demo
    plt = setup_matplotlib(headless=True)
    fig, ax = plt.subplots(figsize=(9, 4))
    t = [p[0] for p in res["curve"]]; v = [p[1] for p in res["curve"]]
    ax.plot(t, v, color="#c0392b", lw=1)
    ax.axvspan(10, 20, color="#999", alpha=0.15, lw=0, label="시작 기준 (10~20 s)")
    ax.axvspan(res["hold_s"] - 10, res["hold_s"], color="#2e6fbf", alpha=0.12, lw=0,
               label="끝 (마지막 10 s)")
    ax.set_xlabel("유지 시간 (s)"); ax.set_ylabel("출력 (mV)")
    ax.set_title(f"{CHANNEL_KO[ch]} 크리프 · {res['load_g']:.0f} g · 변화 {res['change_pct_of_signal']:+.1f}% "
                 f"(기준 ±{MAX_CREEP_PCT:.0f}%)", loc="left", fontsize=10.5)
    ax.legend(fontsize=9)
    stamp_demo(fig, is_demo)
    fig.tight_layout()
    fig.savefig(out, bbox_inches="tight")
    plt.close(fig)


# =============================================================================
def jsonable(o):
    if isinstance(o, float) and math.isnan(o):
        return None
    if isinstance(o, dict):
        return {k: jsonable(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [jsonable(v) for v in o]
    return o


def main() -> None:
    ap = argparse.ArgumentParser(description="EMBRACE 압박 채널 보정 · 전환 판정 (TC-C2)")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--port", help="시리얼 포트 (예: COM5)")
    g.add_argument("--list", action="store_true", help="사용 가능한 포트 목록")
    g.add_argument("--demo", action="store_true", help="보드 없이 절차 연습")
    ap.add_argument("--baud", type=int, default=DEFAULT_BAUD)
    ap.add_argument("--channel", choices=["chest", "back"], default="chest")
    ap.add_argument("--steps", default="0,15.4,23.1,30.8,46.2",
                    help="동전 하중 단계 g (쉼표 구분, 0 포함). 기본 = 500원 동전 0/2/3/4/6개")
    ap.add_argument("--pusher-g", type=float, default=0.0,
                    help="누름 돌기 무게 g (모든 단계에 더해짐, 0 단계에도 얹혀 있음)")
    ap.add_argument("--repeat", type=int, default=5)
    ap.add_argument("--settle", type=float, default=3.0, help="추를 올린 뒤 기다리는 시간(s)")
    ap.add_argument("--measure", type=float, default=5.0, help="평균을 내는 시간(s)")
    ap.add_argument("--creep", action="store_true", help="크리프 시험 모드")
    ap.add_argument("--load", type=float, default=30.8, help="[creep] 하중 g (기본 동전 4개)")
    ap.add_argument("--hold", type=float, default=300.0, help="[creep] 유지 시간 s")
    ap.add_argument("--sense-mm", type=float, default=None, help="감지부 지름 mm (기본: 기기 헤더 값)")
    ap.add_argument("--yes", action="store_true", help="Enter 없이 자동 진행 (--demo 와 함께)")
    args = ap.parse_args()

    if args.list:
        list_ports()
        return
    ch = args.channel
    steps = sorted({float(x) + args.pusher_g for x in args.steps.split(",") if x.strip()})
    if len(steps) < 2:
        sys.exit("[오류] --steps 는 2개 이상이어야 한다")

    demo = DemoDevice(channels=[ch], scenario=False) if args.demo else None
    src = DemoSource(demo) if args.demo else open_source(args.port, args.baud)
    src.start()
    if not src.wait_header(8):
        sys.exit("[오류] 기기 헤더(열 이름)를 받지 못했습니다. 펌웨어 v2 가 올라가 있는지 확인")
    if ch not in src.info.value_columns:
        sys.exit(f"[오류] 기기가 {ch} 열을 보내지 않습니다 (config.h 의 ENABLE_* 확인)")
    sense = args.sense_mm or src.info.channel_num(ch, "sense_mm") or 10.0
    kind = src.info.channels.get(ch).kind if ch in src.info.channels else "?"
    rr = src.info.channel_num(ch, "r_ref") or 0
    print(f"\n{CHANNEL_KO[ch]} 채널 · 센서 {kind} · R_ref {rr / 1000:.0f} kΩ · 감지부 Ø{sense} mm (집중판 없음)"
          f"\n  20 mmHg ≈ {mmhg_to_grams(20, sense):.1f} g, 30 mmHg ≈ {mmhg_to_grams(30, sense):.1f} g"
          f"  (500원 동전 {mmhg_to_grams(20, sense) / COIN_G:.1f}개, {mmhg_to_grams(30, sense) / COIN_G:.1f}개)\n")

    ts = timestamp()
    tag = "DEMO_" if args.demo else ""
    ses = Session(src, ch, DATA_DIR / f"{tag}calib_raw_{ch}_{ts}.csv")
    IMG_DIR.mkdir(exist_ok=True)
    DATA_DIR.mkdir(exist_ok=True)
    common = {"channel": ch, "created": datetime.now().isoformat(timespec="seconds"),
              "sense_mm": sense, "pusher_g": args.pusher_g, "r_ref": rr, "sensor_kind": kind, "demo": bool(args.demo),
              "device_header": src.info.header_lines}
    try:
        if args.creep:
            res = run_creep(ses, args.load, args.hold, args.yes, demo)
            out_json = DATA_DIR / f"{tag}creep_{ch}_{ts}.json"
            out_png = IMG_DIR / f"{tag}creep_{ch}_{ts}.png"
            out_json.write_text(json.dumps(jsonable({**common, **res}), indent=2, ensure_ascii=False),
                                encoding="utf-8")
            plot_creep(res, ch, out_png, args.demo)
            print(f"\n[크리프] 시작 {res['start_mv']:.1f} mV → 끝 {res['end_mv']:.1f} mV "
                  f"(무부하 {res['zero_mv']:.1f} mV 제외 변화 {res['change_pct_of_signal']:+.1f}%) "
                  f"→ {'통과' if res['pass'] else '실패'} (기준 ±{MAX_CREEP_PCT:.0f}%)")
        else:
            res = run_static(ses, steps, args.repeat, args.settle, args.measure, args.yes, demo, sense,
                             args.pusher_g)
            out_json = DATA_DIR / f"{tag}calib_{ch}_{ts}.json"
            out_png = IMG_DIR / f"{tag}calib_{ch}_{ts}.png"
            doc = jsonable({**common, "steps_g": steps, "repeat": args.repeat,
                            "settle_s": args.settle, "measure_s": args.measure, **res})
            out_json.write_text(json.dumps(doc, indent=2, ensure_ascii=False), encoding="utf-8")
            latest_path(ch, args.demo).write_text(json.dumps(doc, indent=2, ensure_ascii=False),
                                                  encoding="utf-8")
            plot_static(res, ch, sense, out_png, args.demo)
            m, c = res["metrics"], res["criteria"]
            print("\n[보정 결과]")
            for gg, mv in zip(res["table"]["grams"], res["table"]["mv_mean"]):
                print(f"   {gg:6.1f} g ({grams_to_mmhg(gg, sense):5.1f} mmHg) → {mv:7.1f} mV")
            print(f"   노이즈 {m['noise_sd_mv']:.1f} mV · 선형 R² {m['linear_r2']:.3f} · "
                  f"단조 증가 {'예' if m['monotonic'] else '아니오 (집중판·돌기 정렬 확인)'}")
            hy = [v for v in m["hysteresis_pct_of_span"].values() if v is not None]
            if hy:
                print(f"   이력 (내림-올림, 출력 범위 대비) 최대 {max(hy, key=abs):+.1f}%")
            print("\n[전환 판정 — TC-C2]")
            r = c["repeatability"]
            print(f"   반복성 최대 {r['value_pct']:.1f}%  (기준 ≤ {MAX_REPEAT_PCT:.0f}%)  "
                  f"→ {'통과' if r['pass'] else '실패'}")
            s = c["resolution"]
            print(f"   분해능 {s['grams_20mmhg']:.1f}→{s['grams_30mmhg']:.1f} g 차이 = 노이즈 × "
                  f"{s['value_x_noise']:.1f}  (기준 ≥ {MIN_RESOLUTION:.0f})  → {'통과' if s['pass'] else '실패'}")
            fl = c.get("response_floor")
            if fl:
                print(f"   응답 하한: {fl['grams']:.1f} g 에서 무부하 대비 노이즈 × {fl['lift_x_noise']:.1f} "
                      f"→ {'반응함' if fl['pass'] else '반응 없음 (감지부 하중이 응답 하한 아래)'}")
            print("   크리프는 --creep 으로 따로 잰다.")
            if args.demo:
                print("\n(모의 보정은 DEMO_calib_*_latest.json 에 저장 — 실측 보정 파일은 건드리지 않음)")
        print(f"\n[저장] {out_json}\n[저장] {out_png}\n[저장] {ses.logger.path}")
    finally:
        ses.close()
        src.stop()


if __name__ == "__main__":
    main()
