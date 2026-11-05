"""압박 채널 보정 파일 (calib_<채널>_latest.json) 읽기와 mV → g → mmHg 환산.

calibrate.py 가 만들고 visualizer.py 가 읽는다. 환산은 '오름차순 하중 단계의 평균 mV' 표를
구간 선형 보간한다. 보정 범위를 벗어나면 끝 구간을 그대로 늘려 쓰되 out_of_range 로 알린다.
"""

from __future__ import annotations

import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT.parent / "common"))
from embrace_link import grams_to_mmhg  # noqa: E402

DATA_DIR = ROOT / "data"


def latest_path(channel: str, demo: bool = False) -> Path:
    """visualizer 가 읽는 최신 보정 파일. 모의(--demo) 보정은 DEMO_ 접두어로 따로 둔다."""
    return DATA_DIR / f"{'DEMO_' if demo else ''}calib_{channel}_latest.json"


@dataclass
class Calibration:
    channel: str
    grams: list[float]
    mv: list[float]
    sense_mm: float          # 감지부 지름 (집중판 없음 — 하중은 감지부에만 걸린다고 본다)
    source: str = ""

    @classmethod
    def load(cls, channel: str, path: Path | None = None, demo: bool = False) -> "Calibration | None":
        p = path or latest_path(channel, demo)
        try:
            d = json.loads(Path(p).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        pts = sorted(zip(d["table"]["mv_mean"], d["table"]["grams"]))
        # 환산표는 mV 가 증가해야 한다. 같거나 역전된 점은 버린다 (FSR 은 단조 증가)
        mv, g = [], []
        for x, y in pts:
            if not mv or (x > mv[-1] and y >= g[-1]):
                mv.append(x); g.append(y)
        if len(mv) < 2:
            return None
        sense = d.get("sense_mm", d.get("disc_mm", 10.0))   # disc_mm = 집중판 시절(구버전) 파일
        return cls(channel, g, mv, float(sense), str(p))

    def to_grams(self, mv: float) -> tuple[float, bool]:
        """(g, out_of_range)"""
        xs, ys = self.mv, self.grams
        if math.isnan(mv):
            return math.nan, False
        out = mv < xs[0] or mv > xs[-1]
        i = 1
        while i < len(xs) - 1 and mv > xs[i]:
            i += 1
        x0, x1, y0, y1 = xs[i - 1], xs[i], ys[i - 1], ys[i]
        g = y0 + (y1 - y0) * (mv - x0) / (x1 - x0)
        return max(g, 0.0), out

    def to_mmhg(self, mv: float) -> tuple[float, bool]:
        g, out = self.to_grams(mv)
        return grams_to_mmhg(g, self.sense_mm), out
