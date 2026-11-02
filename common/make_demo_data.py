#!/usr/bin/env python3
"""모의 데이터(DEMO_*) 재생성 — 보드 없이 그래프·보고서 양식을 점검하거나 팀원에게 보여 줄 때

각 센서 폴더의 data/ 에 DEMO_*.csv, report_images/ 에 DEMO_*.png 를 만든다.
모든 그림에는 '모의 데이터 (실측 아님)' 표기가 찍힌다. 실측 결과와 섞지 말 것.

실행 (저장소 루트에서):  python common/make_demo_data.py
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from embrace_link import REPO_ROOT, DemoDevice, DemoSource  # noqa: E402


def load(name: str, rel: str):
    spec = importlib.util.spec_from_file_location(name, REPO_ROOT / rel)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def record(app, src, path: Path) -> Path:
    """LiveApp 의 수신·기록 경로를 그대로 쓰되, 창 없이 끝까지 돌린다."""
    app.logger.path = path
    src.start(); src.join()
    app.drain()
    app.logger.close()
    print(f"[CSV] {path}  ({app.logger.rows} 행)")
    return path


def main() -> None:
    # 호흡
    bv = load("breath_vis", "breath-sensor/software/visualizer.py")
    src = DemoSource(DemoDevice(channels=["breath"], duration_s=75), realtime=False)
    app = bv.LiveApp(src, 20, bv.DEFAULT_FS, log_csv=True, is_demo=True)
    p = record(app, src, REPO_ROOT / "breath-sensor/data/DEMO_breath.csv")
    bv.run_replay(p, 10, 40, show=False)

    # 악력
    gv = load("grip_vis", "grip-sensor/software/visualizer.py")
    src = DemoSource(DemoDevice(channels=gv.CHANNELS, duration_s=48), realtime=False)
    app = gv.LiveApp(src, 20, True, False, True)
    p = record(app, src, REPO_ROOT / "grip-sensor/data/DEMO_grip.csv")
    gv.run_replay(p, 0, None, False, False)

    # 압박 (DEMO 보정 파일이 있으면 mmHg 칸도 그린다: calibrate.py --demo --yes 로 생성)
    cv = load("comp_vis", "compression-sensor/software/visualizer.py")
    src = DemoSource(DemoDevice(channels=cv.CHANNELS, duration_s=70), realtime=False)
    app = cv.LiveApp(src, 60, True, True)
    p = record(app, src, REPO_ROOT / "compression-sensor/data/DEMO_compression.csv")
    cv.run_replay(p, 0, None, False)

    # 5채널 통합
    mv = load("monitor_all", "common/monitor_all.py")
    from embrace_link import CsvLogger, Timeline
    src = DemoSource(DemoDevice(duration_s=75), realtime=False)
    src.start(); src.join()
    tl = Timeline()
    lg = CsvLogger(REPO_ROOT / "data/DEMO_all.csv", src.info, note="monitor_all demo")
    while not src.q.empty():
        f = src.q.get()
        lg.write(f, tl.update(f)[0])
    lg.close()
    print(f"[CSV] {lg.path}  ({lg.rows} 행)")
    mv.run_replay(lg.path, 0, None, False)


if __name__ == "__main__":
    main()
