# Capstone_EMBRACE

ASD(자폐 스펙트럼) 아동용 스마트 압박 조끼(Deep Pressure Vest) 캡스톤 프로젝트(1-07반)의 센서 모듈 저장소입니다.
ESP32-S3 한 대가 **호흡 1 + 악력 2 + 압박 2 = 5채널**을 50 Hz 로 **같은 주기 안에서** 읽어 PC 로 보내고,
PC 에서 센서별로 그래프 · CSV · 보고서 그림을 만듭니다.

```
 호흡 인장 센서 ─┐                                         ┌▶ breath-sensor/software       흡기·호기
 악력 FSR ×2   ─┼▶ ESP32-S3 firmware/embrace_node ─USB─▶ common/embrace_link.py ─┼▶ grip-sensor/software         쥠 지수
 압박 FSR ×2   ─┘   (senseTask 50 Hz → 큐 → txTask)        (수신·파싱·CSV 공통)     ├▶ compression-sensor/software  등가 압력·보정
                                                                                └▶ common/monitor_all.py        5채널 통합 시험
```

> 설계 근거 · 판정 기준 · 일정은 설계 계획서(Claude 문서)와 `docs/` 에 있습니다.
> **압박 센서는 힘 집중판 없이 감지부만 하중을 받는다고 가정합니다 (2026-11-02 팀 결정).**
> 11/6(금) TC-C2 판정 결과에 따라 공압 센서(XGZP6847A010KPG33)로 바꿀 수 있습니다.

## 디렉토리 구조

```
Capstone_EMBRACE/
├── README.md                  ← 이 문서
├── LICENSE                    MIT
├── requirements.txt           pyserial, matplotlib, numpy (모든 PC 프로그램 공통)
├── .gitignore                 실측 CSV/JSON 은 커밋하지 않음, DEMO_* 만 커밋
├── firmware/
│   └── embrace_node/          ★ 보드에 올리는 유일한 펌웨어 (Arduino IDE 로 이 폴더를 연다)
│       ├── embrace_node.ino     진입점 (setup/loop 만)
│       ├── config.h             핀 · 채널 ON/OFF · 저항값 · 감지부 지름 — 학생이 고치는 유일한 파일
│       └── src/                 adc_reader, channel, moving_average, sense_task, frame_out, embrace_app
├── common/
│   ├── embrace_link.py        시리얼 프로토콜 v2 파싱 · 입력 소스 · 모의 장치 · CSV 기록/읽기
│   ├── plotting.py            그래프 스타일 · 한글 폰트 · 센서 이상 구간 표시
│   ├── monitor_all.py         5채널 통합 모니터 (TC-F1~F4 요약 자동 생성)
│   └── make_demo_data.py      모의 데이터(DEMO_*) 재생성
├── breath-sensor/             호흡 — visualizer.py, detector.py (README 에 Air-Rhythm 출처 정리)
├── grip-sensor/               악력 — visualizer.py
├── compression-sensor/        압박 — visualizer.py, calibrate.py, calib_model.py
│   (각 폴더: software/ · data/ · report_images/ · README.md)
├── data/  report_images/      통합 모니터(monitor_all) 기록
└── docs/
    ├── wiring.md              핀맵 · 회로 · 압박 신호 처리 체인과 증폭
    ├── sensor_specs.md        ZD10-100 사양 · 저항 실측 기록표 · 대체 센서
    ├── test_log.md            TC 기록표 · TC-C2 판정표
    └── HANDOFF.md             인수인계 메모 (다음 대화용)
```

v1(ESP32-WROOM-32, 호흡 단독) 펌웨어는 git 태그 **`v1.0-wroom32`** 에 보존되어 있습니다.
`git checkout v1.0-wroom32` 로 언제든 볼 수 있습니다.

## 빠른 시작 (Windows)

### 1. 배선 (자세한 그림: `docs/wiring.md`)

| 채널 | 핀 | 회로 |
|---|---|---|
| 호흡 | GPIO4 | `3V3 ─[R_pu]─ GPIO4 ─[센서]─ GND` |
| 악력 L / R | GPIO5 / 6 | `3V3 ─[ZD10-100 ∥ 150 kΩ]─ GPIO ─[10 kΩ]─ GND` + 100 nF |
| 가슴 / 등 압박 | GPIO7 / 8 | `3V3 ─[ZD10-100 ∥ 4.7 MΩ]─ GPIO ─[470 kΩ]─ GND` + **100 nF 필수** |

### 2. 펌웨어 업로드 (Arduino IDE 2.x)

1. Boards Manager 에서 **esp32 (Espressif) 3.x** 설치 (3.3.4 로 컴파일 확인)
2. `firmware/embrace_node/embrace_node.ino` 열기 (폴더째 열림)
3. Tools → Board: **ESP32S3 Dev Module**
4. Tools → USB CDC On Boot: 보드의 **UART** 포트에 꽂으면 **Disabled**, **USB** 포트에 꽂으면 **Enabled**
5. Tools → Port: 장치 관리자에서 확인한 COM 번호 → 업로드
6. 시리얼 모니터(115200)에서 확인 후 **반드시 닫기** (열려 있으면 파이썬이 포트를 못 연다)
   ```
   # EMBRACE embrace_node v2.0.0 board=ESP32-S3 core=3.3.4 fs=50Hz ...
   # ch chest pin=7 kind=fsr r_ref=470000 r_bias=4700000 open_check=on sense_mm=10.0 ...
   # columns:	seq	t_ms	breath	grip_l	grip_r	chest	back	flags
   0	1532	1712	206	207	300	301	00000
   # fs=50.00Hz avg=20000us min=19991us max=20009us work_max=1300us drop=0
   ```

센서 하나만 시험할 때는 `config.h` 에서 해당 `ENABLE_*` 만 true 로 두고 다시 업로드합니다.

### 3. PC (Python 3.11)

```bash
pip install -r requirements.txt
python common/monitor_all.py --list                       # 포트 확인
python common/monitor_all.py --port COM5                  # 5채널 통합 확인
python breath-sensor/software/visualizer.py --port COM5   # 호흡
python grip-sensor/software/visualizer.py --port COM5     # 악력
python compression-sensor/software/calibrate.py --port COM5 --channel chest   # 압박 보정 (TC-C2)
python compression-sensor/software/visualizer.py --port COM5                  # 압박
```

모든 프로그램은 `--demo` (보드 없이 모의 데이터) 와 `--replay <CSV>` (보고서 PNG + 통계) 를 지원합니다.
**한 번에 한 프로그램만** 포트를 열 수 있습니다. 모든 CSV 에는 프레임 전체(5채널)가 기록되므로,
예를 들어 `monitor_all` 로 찍은 CSV 를 각 센서 visualizer 의 `--replay` 로 다시 볼 수 있습니다.

## 시리얼 프로토콜 v2

| 줄 | 예 | 언제 |
|---|---|---|
| 헤더 | `# EMBRACE embrace_node v2.0.0 board=ESP32-S3 ... fs=50Hz ...` | 부팅 · 5초마다 · PC 가 `h` 를 보낼 때 |
| 채널 정의 | `# ch chest pin=7 kind=fsr r_ref=470000 r_bias=4700000 sense_mm=10.0 ...` | 헤더와 함께 |
| 열 이름 | `# columns:<TAB>seq<TAB>t_ms<TAB>breath<TAB>...<TAB>flags` | 헤더와 함께 |
| 데이터 | `seq t_ms breath grip_l grip_r chest back flags` (TAB, mV) | 20 ms 마다 |
| 경고 | `# WARN ch=chest state=open mv=3` / `# INFO ch=chest state=ok mv=300` | 상태가 0.5 s 이상 바뀔 때 |
| 진단 | `# fs=50.00Hz avg=... max=... work_max=... drop=0` | 1초마다 |

- `flags`: 채널당 한 글자 (0 정상, 1 단선, 2 단락), 열 순서대로. 예) `00010` = 가슴 압박 단선
- 꺼진 채널은 열이 빠지고, `# columns` 가 실제 순서를 알려 줍니다.
- 헤더를 5초마다 다시 보내므로 PC 를 언제 연결해도 5초 안에 설정을 받습니다.

## 안전 규칙

압박 채널(`chest`, `back`) 중 하나라도 단선·단락이면, 이후 붙일 제어 태스크는 **자동 가압 금지 + 즉시 배기**로
동작해야 합니다. 현재 단계에서는 `flags` 와 `# WARN` 출력, 화면 경고까지 구현되어 있습니다.
detector.py 의 판정 보류(UNKNOWN)도 같은 원칙입니다 — "확실하지 않으면 아무 동작도 하지 않는다".

## 모의 데이터

`DEMO_*` 파일은 `python common/make_demo_data.py` (+ `calibrate.py --demo`) 로 만든 **모의 데이터**이며,
모든 그림에 "모의 데이터 (실측 아님)" 표기가 있습니다. 보고서의 실측 결과로 쓰면 안 됩니다.
모의 FSR 모델(`embrace_link.fsr_mv`)은 계획서 예시 저항값으로 만든 가정이라, 실제 센서보다 결과가 좋게 나올 수 있습니다.

## 출처

호흡 모듈은 객담 배출 조끼 프로젝트 [Air-Rhythm](https://github.com/Rubi9981/Air-Rhythm) 의 코드를 참고했습니다.
파일별 출처는 `breath-sensor/README.md` 에 정리되어 있습니다.
