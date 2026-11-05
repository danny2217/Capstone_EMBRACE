# compression-sensor — 가슴/등 압박 (ZD10-100 × 2, 집중판 없음)

에어백과 몸 사이에 ZD10-100 을 하나씩 넣어 가슴·등에 걸리는 압박을 잰다.
펌웨어는 루트 `firmware/embrace_node/` 의 `chest`(GPIO7), `back`(GPIO8) 열이다.

> **2026-11-02 팀 결정: 힘 집중판 없이 감지부만 하중을 받는다고 가정한다.**
> 20~30 mmHg 는 감지부(Ø10 mm 가정) 힘 약 21~32 g 으로, ZD10-100 응답 하한(20 g 미만) 바로 위다.
> 11/6(금) `calibrate.py` 판정(TC-C2)에서 실패하면 XGZP6847A010KPG33 공압 센서로 전환한다
> (`config.h` 의 `COMPRESSION_SOURCE` 한 줄).

## 회로

`3V3 ─[ZD10-100 ∥ R_bias 4.7 MΩ]─ GPIO7/8 ─[R_ref 470 kΩ]─ GND`, 노드에 **100 nF 필수**. 무부하 기준 약 300 mV.
R_ref 를 크게 잡아 회로에서 신호를 약 10배 키운다 (10 kΩ 대비, 예시값). 근거와 그림: `docs/wiring.md`.

## 실행 (저장소 루트에서 `pip install -r requirements.txt` 한 번)

```bash
cd compression-sensor/software

# 1) 보정 (TC-C2) — 누름 돌기 + 500원 동전 0/2/3/4/6개, 약 15분
python calibrate.py --port COM5 --channel chest --pusher-g 0.4
python calibrate.py --port COM5 --channel back  --pusher-g 0.4
# 2) 크리프 — 동전 4개 5분
python calibrate.py --port COM5 --channel chest --creep --hold 300
# 3) 실시간 (처음 3초는 에어백을 뺀 상태로 → 영점)
python visualizer.py --port COM5
# 4) 재생: 정지 구간을 고르면 필터 전후 잡음 비, 유지 구간을 고르면 크리프를 찍는다
python visualizer.py --replay ../data/compression_YYYYMMDD_HHMMSS.csv --start 20 --duration 30 --no-show

# 보드 없이 절차 연습
python calibrate.py --demo --channel chest --yes --repeat 2 --settle 0.5 --measure 1
python visualizer.py --demo
```

실시간 단축키: `t` 영점(앞 1초 평균) · `s` PNG · `p` 일시정지 · `q` 종료

## 신호 처리 체인 (`CompressionChain`, 실시간·재생 공통)

| 단계 | 위치 | 신호 | 잡음 | 지연 |
|---|---|---|---|---|
| 1. 분압 R_ref 470 kΩ | 회로 | **약 10배** (진짜 증폭은 여기뿐) | — | 0 |
| 2. RC 저역통과 ×100 nF | 회로 | 그대로 | 약 7.6 Hz 위 감쇠 | 약 21 ms |
| 3. 버림 1 + 오버샘플 8 | 펌웨어 | 그대로 | ÷2.8 | 약 0.5 ms |
| 4. 이동평균 10샘플 | PC | 그대로 | ÷3.2 | 약 90 ms |
| 5. 영점 (tare) | PC | 오프셋 제거 | 드리프트 제거 | 0 |
| 6. 보정표 mV→g→mmHg | PC | 단위 변환 | — | 0 |
| 7. 표시 평활 τ 0.5 s | PC 화면 | 그대로 | 추가 감소 | 약 0.5 s (제어 경로 아님) |

필터는 잡음을 줄일 뿐 신호를 키우지 않는다. 감지부 힘이 응답 하한 아래면 출력이 0 이고 어떤 필터도 0 을 키우지 못한다.

## 보정 파일

- `data/calib_<채널>_latest.json` — visualizer 가 읽는 최신 보정 (실측, 커밋 안 됨)
- `data/DEMO_calib_<채널>_latest.json` — `--demo` 용 모의 보정 (커밋됨)
- PNG: `report_images/calib_*.png` (보정곡선 · 판정값), `report_images/creep_*.png`

⚠ 이 채널 값은 "에어백 압력" 이 아니라 "감지부가 받은 힘을 감지부 면적으로 나눈 등가 압력" 이다.
센서 이상(flags) 구간은 빨간 띠로 표시되며, 그 구간 값으로 가압 판단을 하면 안 된다.
