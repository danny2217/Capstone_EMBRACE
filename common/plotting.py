"""EMBRACE 공통 그래프 스타일 (보고서 품질) — matplotlib 이 필요한 프로그램만 import 한다."""

from __future__ import annotations

COLORS = {
    "breath": "#1f5fbf",
    "grip_l": "#d9822b",
    "grip_r": "#7a4fbf",
    "chest": "#c0392b",
    "back": "#2e8b57",
    "raw": "#7f8a99",
    "fault": "#e74c3c",
}

DEMO_STAMP = "모의 데이터 (실측 아님)"


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
        # 범례 뒤로 데이터 선이 지나가도 글자가 읽히도록 반투명 흰 배경
        "legend.frameon": True,
        "legend.framealpha": 0.85,
        "legend.edgecolor": "none",
        "legend.facecolor": "white",
    })
    return plt


def shade_faults(ax, t, codes, label: bool = True) -> None:
    """flags 가 0 이 아닌 구간을 빨간 띠로 표시 (센서 단선/단락 구간 = 신뢰 불가)"""
    start = None
    first = True
    for i, c in enumerate(codes):
        if c and start is None:
            start = t[i]
        if (not c or i == len(codes) - 1) and start is not None:
            ax.axvspan(start, t[i], color=COLORS["fault"], alpha=0.12, lw=0,
                       label="센서 이상 구간" if (label and first) else None)
            first = False
            start = None


def stamp_demo(fig, is_demo: bool) -> None:
    if is_demo:
        fig.text(0.99, 0.005, DEMO_STAMP, ha="right", va="bottom", fontsize=9,
                 color="#c0392b", fontweight="bold")
