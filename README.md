# Capstone_1_07class

# Capstone_EMBRACE — 호흡 인장 센서 모듈

ASD(자폐 스펙트럼) 아동용 스마트 압박 조끼(Deep Pressure Vest) 캡스톤 프로젝트(1-07반)의 저장소입니다.
현재 이 저장소에는 조끼의 입력 중 하나인 가슴 스트랩 전도성 고무 인장 센서로 호흡을 측정하고 흡기/호기를 판별하는 모듈(`breath-sensor/`)이 들어 있습니다.

```
전도성 고무 인장 센서 ─▶ ESP32 (50 Hz 측정) ─USB 시리얼─▶ PC (필터 + 흡기/호기 판별)
   가슴 스트랩            sensor_reader            visualizer.py : 그래프·CSV·PNG (개발/보고서용)
                                                   detector.py   : 흡기/호기만 판단 (제품 연동용)
```

이 모듈은 객담 배출 조끼 프로젝트 [Air-Rhythm](https://github.com/Rubi9981/Air-Rhythm) 의 코드를 참고해 만들었습니다.
아래 [Air-Rhythm 에서 가져온 부분](#air-rhythm-에서-가져온-부분)에서 우리 파일마다 원본의 어느 파일이 합쳐졌는지 정리했습니다.

---

## 목차
- [디렉토리 구조](#디렉토리-구조)
- [Air-Rhythm 에서 가져온 부분](#air-rhythm-에서-가져온-부분)
  - [한눈에 보기](#한눈에-보기)
  - [sensor_reader (펌웨어)](#1-firmwaresensor_reader--펌웨어)
  - [visualizer.py](#2-softwarevisualizerpy--실시간-그래프--csv--보고서-png)
  - [detector.py](#3-softwaredetectorpy--흡기호기-판별-제품-연동용)
  - [Air-Rhythm 과 달라진 점](#air-rhythm-과-달라진-점)
- [각 파일의 역할](#각-파일의-역할)
- [흡기/호기 판별 알고리즘](#흡기호기-판별-알고리즘)
- [빠른 시작](#빠른-시작)
- [detector.py 사용법](#detectorpy-사용법)
- [검증 결과](#검증-결과)

---

## 디렉토리 구조

```
Capstone_EMBRACE/
├── README.md                     ← 이 문서
├── LICENSE                       MIT
└── breath-sensor/
    ├── README.txt                하드웨어 연결·업로드·측정 절차 상세 (Windows 기준)
    ├── HANDOFF.md                작업 인수인계 메모 (진행 상황, 현재 문제)
    ├── firmware/
    │   └── sensor_reader/        ESP32 펌웨어 (Arduino IDE 로 이 폴더를 연다)
    │       ├── sensor_reader.ino   진입점 (setup/loop 만)
    │       ├── sensor_app.h        공개 함수 선언
    │       └── sensor_app.cpp      실제 구현 (설정값 cfg::)
    ├── software/
    │   ├── visualizer.py         실시간 그래프 + CSV 기록 + 보고서용 PNG
    │   ├── detector.py           흡기/호기 판별 전용 (그래프·CSV·이미지 없음)
    │   └── requirements.txt      pyserial, matplotlib, numpy
    ├── data/                     visualizer.py 가 CSV 를 저장하는 곳
    └── report_images/            visualizer.py 가 PNG 를 저장하는 곳
```

---

## Air-Rhythm 에서 가져온 부분

Air-Rhythm 은 ESP32-S3 에서 호흡을 검출하는 **펌웨어(C++)** 와, PC 에서 개발·분석하는 **파이썬 패키지 + 스크립트**로 나뉘어 있습니다.
우리 코드는 여러 파일에 흩어진 기능을 **역할별로 한 파일에 모았습니다.** (원본 경로는 Air-Rhythm 저장소 기준)

### 한눈에 보기

| 우리 파일 | 합쳐진 Air-Rhythm 파일 |
|---|---|
| `firmware/sensor_reader/` (`.ino` + `sensor_app.*`) | `firmware/conductive_rubber_cord.ino` + `firmware/prototype/task_sense.cpp` + `firmware/prototype/board_config.h` + `firmware/prototype/prototype.ino` |
| `software/visualizer.py` | `breath/filters.py` + `breath/detectors/slope.py` + `breath/detectors/base.py` + `breath/config.py` + `breath/io_serial.py` + `scripts/monitor_breath.py` + `scripts/log_serial.py` + `scripts/detect_slope.py` + `plotting/plotting.py` |
| `software/detector.py` | `breath/filters.py` + `breath/detectors/slope.py` + `breath/config.py` + `breath/io_serial.py` + `scripts/monitor_breath.py` (모두 visualizer.py 를 거쳐 들어옴) |

```
Air-Rhythm                                         Capstone_EMBRACE/breath-sensor
──────────────────────────────────────            ─────────────────────────────────
firmware/conductive_rubber_cord.ino  ─┐
firmware/prototype/task_sense.cpp    ─┼──────────▶  firmware/sensor_reader/
firmware/prototype/board_config.h    ─┤               sensor_reader.ino
firmware/prototype/prototype.ino     ─┘               sensor_app.h / sensor_app.cpp

breath/filters.py            ─┐
breath/detectors/slope.py    ─┤ (알고리즘) ──┬─────▶  software/visualizer.py
breath/detectors/base.py     ─┤              │
breath/config.py             ─┤              └─────▶  software/detector.py
breath/io_serial.py          ─┘ (시리얼 입력)
scripts/monitor_breath.py    ── (실시간 루프) ─────▶  visualizer.py, detector.py
scripts/log_serial.py        ── (CSV 기록)    ─────▶  visualizer.py
scripts/detect_slope.py      ── (CSV 재생)    ─────▶  visualizer.py (--replay)
plotting/plotting.py         ── (그래프)      ─────▶  visualizer.py

(가져오지 않음) firmware/breath_monitor/, firmware/prototype/breath_*.cpp (기기 내 검출 C 이식본),
               link_ble.*, link_cmd.*, link_msg.*, task_app.* (BLE·앱 연동),
               firmware/host_test/ (파이썬↔C 대조 검증), breath/detectors/amplitude.py,
               breath/io_csv.py, scripts/compare_filters.py, scripts/plot_signals.py
```

### 1. `firmware/sensor_reader/` — 펌웨어

| 우리 코드 | 가져온 원본 | 무엇을 가져왔나 / 어떻게 바꿨나 |
|---|---|---|
| `reportRate()` | `conductive_rubber_cord.ino` 의 `reportRate()` | 거의 그대로. 1초마다 `# fs=50.00Hz avg=… min=… max=…` 진단 줄 출력 |
| 50 Hz 주기 실행 (`vTaskDelayUntil`) | `conductive_rubber_cord.ino` 의 `loop()` | `delay()` 대신 절대 시각 기준 주기 (`PERIOD_MS = 20`) |
| `readMilliVolts()` 오버샘플링 | `conductive_rubber_cord.ino` 의 `readAveragedMv()`, `task_sense.cpp` 의 `read_averaged_mv()` | 평균 횟수 16 → **8** 회 |
| `senseTask()` + `xTaskCreatePinnedToCore` | `firmware/prototype/task_sense.cpp` 의 `sense_task()` / `sense_start()` | 센싱 전용 FreeRTOS 태스크 구조. 기준 시각을 태스크 진입부에서 잡는 방식 포함. 원본의 대역통과·검출(`sense_step`)과 큐(`q_sense`)는 **제외** |
| `cfg::SENSE_CORE / PRIO / STACK` | `firmware/prototype/board_config.h` | 같은 개념 (core 1, stack 4096). 우선순위 5 → 3 |
| `.ino` 는 `setup()/loop()` 만, `loop()` 는 `vTaskDelay(portMAX_DELAY)` | `firmware/prototype/prototype.ino` | 진입점과 구현 분리. 우리는 Arduino 자동 prototype 오류 회피용으로 `.cpp` 분리 |
| **새로 작성** | — | ESP32-WROOM-32 대응 (GPIO32, ADC 11 dB), **내부 풀업 + RTC 레지스터로 풀업 ON 직접 확인**, Arduino-ESP32 코어 2.x/3.x 분기, 이동 평균(`MovingAverage`, 10샘플), 단선/단락 감시(`checkHealth`), 출력 형식 `seq t_ms mv ma_mv` (원본은 `raw mv`) |

### 2. `software/visualizer.py` — 실시간 그래프 · CSV · 보고서 PNG

| 우리 코드 | 가져온 원본 | 무엇을 가져왔나 / 어떻게 바꿨나 |
|---|---|---|
| `Biquad`, `_butter2`, `BandpassButter2` | `breath/filters.py` 의 `Biquad`, `butterworth2_highpass/lowpass`, `BandpassButter2` | 계산식 동일. 고역/저역 계수 함수를 `_butter2(kind)` 하나로 합침. 1-pole 필터·EMA·배열 래퍼는 제외 |
| `HP_HZ`, `LP_HZ`, `INHALE_COLOR`, `EXHALE_COLOR` | `breath/config.py` | 값 그대로 (0.08 / 0.70 Hz, 빨강 / 초록) |
| `SlopeDetector` | `breath/detectors/slope.py` | 알고리즘·파라미터 동일. `MID_GATE=True`, `PROM_RATIO=0` 을 고정값으로 단순화. 이벤트 이름 `inhale_onset` → `inhale` |
| `det.delays` (검출 지연 기록) | `breath/detectors/base.py` 의 `run_detector()` | `지연 = 확정 시각 − 실제 골/마루 시각` 계산을 검출기 안으로 옮김 |
| `SerialSource`, `parse_sample`, `list_ports`, `host_rate()` | `breath/io_serial.py` 의 `open_port`, `parse_sample`, `show_ports`, `RateMeter` | 포트 연 뒤 1.5 s 대기 + 버퍼 비우기, `#` 진단 줄 분리, 3초 창 수신율. 파싱 형식을 4필드(`seq t_ms mv ma_mv`)로 변경 |
| `LiveApp.drain()` (수신 → 필터 → 검출) | `scripts/monitor_breath.py` 의 `run()` | 실시간 루프 구조. 터미널 출력 대신 matplotlib 그래프. 수신을 별도 스레드로 분리 |
| CSV 자동 기록 (`data/breath_YYYYMMDD_HHMMSS.csv`) | `scripts/log_serial.py`, `monitor_breath.py` 의 `open_csv()` | 파일 이름 규칙 동일. 열 구성은 우리 형식(`seq, t_ms, mv, ma_mv, bp_mv, phase, event`) |
| `--replay` (CSV → PNG) | `scripts/detect_slope.py` 의 `run()` | CSV 읽기 → 대역통과 → 검출 → 그리기 순서 동일 |
| 2단 그래프 (위 측정값 / 아래 필터 + ▲▼) | `plotting/plotting.py` 의 `plot_detection()`, `use_korean_font()` | 같은 배치. 흡기/호기 구간을 선 색으로 칠하는 방식으로 변경 |
| `print_detect_summary()` | `scripts/detect_slope.py`, `monitor_breath.py` 의 종료 요약 | 전환 횟수·호흡수·평균 지연 출력 |
| **새로 작성** | — | `--demo` 모의 신호, seq 기반 유실/리셋 감지, ADC 포화 경고, 's'/'p' 단축키, raw/filtered/both PNG 3종 |

### 3. `software/detector.py` — 흡기/호기 판별 (제품 연동용)

`visualizer.py` 에서 **그래프·CSV·PNG 기능을 모두 덜어내고 판별만 남긴 파일**입니다. 의존성은 `pyserial` 하나입니다.

| 우리 코드 | 가져온 곳 | 비고 |
|---|---|---|
| `Biquad`, `BandpassButter2` | `visualizer.py` ← `breath/filters.py` | 동일 |
| `SlopeDetector` | `visualizer.py` ← `breath/detectors/slope.py` | 동일 로직. 기록용 리스트를 길이 제한 `deque` 로 바꿔 장시간 실행에도 메모리가 늘지 않음. 호흡수 중앙값은 원본처럼 `statistics.median` 사용 |
| `parse_sample`, `serial_lines`, `list_ports` | `visualizer.py` ← `breath/io_serial.py` | 그래프용 스레드·큐 없이 단순 루프 |
| CLI 실행 루프 (`run`) | `scripts/monitor_breath.py` | 전환이 확정될 때만 한 줄 출력 |
| **새로 작성** | — | `BreathPhaseDetector` (제품 연동용 API), 센서 이상·데이터 끊김·대량 유실 시 **판정 보류(UNKNOWN)** 로 전환하는 안전 장치, `--plain` 출력 |

### Air-Rhythm 과 달라진 점

| 항목 | Air-Rhythm | 우리 (Capstone_EMBRACE) |
|---|---|---|
| 보드 | ESP32-S3 | **ESP32-WROOM-32** (DevKit, CP2102) |
| 센서 핀 / 회로 | GPIO4, ADC 6 dB (0~1750 mV) | **GPIO32**, ADC 11 dB (약 150~3100 mV), **내부 풀업** (외부 저항 없음) |
| 흡기/호기 판별 위치 | 최신판은 **기기 안**(C 이식본, `breath_slope.cpp`) | **PC**(파이썬). 펌웨어는 측정값만 보냄 |
| 시리얼 형식 | `raw<TAB>mv` | `seq<TAB>t_ms<TAB>mv<TAB>ma_mv` (유실 감지·기기 시계용) |
| 시간축 | 호스트 도착 시각 | 기기 시각 `t_ms` (USB 지터 영향 없음) |
| 용도 | 객담 배출 조끼 (호기에 타진) | 압박 조끼 (호흡 패턴 → 전조 감지 입력) |

---

## 각 파일의 역할

| 파일 | 역할 |
|---|---|
| `firmware/sensor_reader/sensor_reader.ino` | Arduino 진입점. `setup()` 에서 `sensorAppBegin()` 만 호출하고 `loop()` 는 잠재운다. 파일 상단 주석에 회로도·출력 형식 설명 |
| `firmware/sensor_reader/sensor_app.h` | `sensorAppBegin()` 선언 |
| `firmware/sensor_reader/sensor_app.cpp` | 실제 펌웨어. ① ADC·내부 풀업 설정 후 레지스터로 풀업 ON 확인 ② 50 Hz FreeRTOS 태스크에서 ADC 8회 평균 → 이동 평균 10샘플 ③ 단선(≥3000 mV)/단락(≤60 mV)이 0.5 s 지속되면 `# WARN` 출력 ④ 매 샘플 `seq t_ms mv ma_mv` 전송 ⑤ 1초마다 실측 표본화 주파수 보고. 핀·필터 길이 등 설정은 상단 `cfg::` |
| `software/visualizer.py` | 개발·보고서용. 실시간 2단 그래프(측정값 / 대역통과 + 흡기·호기 색 구분), CSV 자동 기록, `--replay` 로 CSV → 보고서 PNG 3장, `--demo` 모의 신호, 수신율·유실·포화 경고 표시 |
| `software/detector.py` | 제품 연동용. 시리얼 입력을 받아 **흡기 / 호기 / 판정 보류** 만 판단. 파일·이미지를 만들지 않음. 다른 파이썬 코드에서 `BreathPhaseDetector` 로 불러 쓸 수 있음 |
| `software/requirements.txt` | 파이썬 의존성 (detector.py 만 쓸 때는 `pyserial` 만 있으면 됨) |
| `README.txt` | 배선, 업로드, 측정 프로토콜, 풀업 저항 결정법, 문제 해결 |
| `HANDOFF.md` | 진행 상황과 현재 문제 (센서 포화 등) 기록 |

---

## 흡기/호기 판별 알고리즘

`visualizer.py` 와 `detector.py` 는 **같은 알고리즘**(Air-Rhythm `SlopeDetector`)을 씁니다.

1. **대역통과 필터** (2차 Butterworth 0.08~0.7 Hz, 인과적): 자세 변화로 인한 기저선 이동(드리프트)과 고주파 잡음을 지워 0 mV 중심으로 진동하는 곡선을 만든다.
2. **평활 기울기**: 필터 출력의 기울기를 시상수 0.08 s 로 평활한다.
3. **전환 확정**: 꼭대기를 "찾는" 것이 아니라, 기울기의 부호가 **확실히** 바뀐 순간을 전환으로 확정한다 (실시간에서는 미래를 볼 수 없으므로).
   - 상승으로 바뀜 → **흡기 시작**, 하강으로 바뀜 → **호기 시작** (`--invert` 로 반대로)
4. **오검출 방지 장치**
   - 적응형 데드밴드: |기울기| 가 최근 평균의 25 % (최소 1 mV/s) 를 넘어야 인정
   - 최소 위상 시간 1.2 s: 전환 직후 반대 전환 금지 → 꼭대기의 작은 요철 무시 (최대 약 25 회/분)
   - 중점 게이트: 흡기 전환은 파형 중간선 아래에서만, 호기 전환은 위에서만
   - 진폭 하한 5 mV: 너무 작으면 판정 보류 (숨 참기, 스트랩 풀림)
   - 안정화 12 s: 시작 직후에는 판정 보류
5. **호흡수**: 최근 흡기 간격(1.4~12 s) 5개의 중앙값

검출 지연(실제 골/마루 → 확정)은 Air-Rhythm 실측 데이터(전환 309회) 기준 **중앙값 140 ms, 90 % 가 380 ms 이하**입니다 (드물게 3 s 이상의 이상치 있음). 여기에 펌웨어 이동 평균 군지연 약 90 ms 는 포함되지 않았습니다 (`detector.py` 는 이동 평균 전 값 `mv` 를 쓰므로 해당 없음).

---

## 빠른 시작


1. **배선**: 센서 한쪽 → GPIO32, 다른 쪽 → GND (외부 저항 없음)
2. **펌웨어**: Arduino IDE 에서 보드 `ESP32 Dev Module` 선택 → `firmware/sensor_reader/sensor_reader.ino` 업로드 → 시리얼 모니터(115200)에서 `# pullup_register=ON` 확인 후 **시리얼 모니터 닫기**
3. **파이썬** (Python 3.9+)
   ```bash
   cd breath-sensor/software
   pip install -r requirements.txt
   python visualizer.py --list            # 포트 확인 (예: COM5)
   python visualizer.py --port COM5       # 그래프로 확인 (개발용)
   python detector.py --port COM5         # 흡기/호기만 판별 (제품 연동용)
   ```

---

## detector.py 사용법

### 명령줄

```bash
python detector.py --list                  # 포트 목록
python detector.py --port COM5             # 실시간 판별
python detector.py --port COM5 --invert    # 들이쉬면 전압이 내려가는 센서일 때
python detector.py --port COM5 --plain     # 다른 프로그램이 읽기 쉬운 TAB 형식
python detector.py --demo                  # 보드 없이 모의 신호 (약 100초)
python detector.py --demo --fast           # 모의 신호를 대기 없이 바로 끝까지
```

기본 출력 (전환이 확정될 때 / 판정 보류에 들어갈 때만 한 줄):
```
[    0.00s] ■ 판정 보류: 안정화 중
[   13.22s] ▼ 호기 시작  (지연 380 ms)
[   15.42s] ▲ 흡기 시작  (지연 160 ms)
[   19.42s] ▲ 흡기 시작  (지연 160 ms, 15.0 회/분)
[   70.48s] ■ 판정 보류: 센서 단선/분리
```

`--plain` 출력 (상태가 바뀔 때마다 `시각<TAB>상태<TAB>사유`):
```
0.000	UNKNOWN	settling
12.000	INHALE	ok
13.220	EXHALE	ok
```

### 판정 보류(UNKNOWN)와 사유

`UNKNOWN` 은 **"확실하지 않으니 아무 동작도 하지 말라"** 는 뜻입니다. 제품 쪽은 UNKNOWN 을 받으면 반드시 안전 상태(가압 금지 등)로 가야 합니다.

| reason | 의미 | 들어가는 조건 | 빠져나오는 조건 |
|---|---|---|---|
| `settling` | 안정화 중 | 시작, 재시작 직후 12 s | 12 s 경과 |
| `low_signal` | 신호 약함 | 진폭(p-p) < 5 mV (숨 참기, 스트랩 풀림) | 진폭 회복 후 첫 기울기 |
| `sensor_open` | 센서 단선/분리 | 0.5 s 동안 모든 샘플 ≥ 3000 mV | 정상 범위 복귀 → **처음부터 재안정화** |
| `sensor_short` | 센서 단락 | 0.5 s 동안 모든 샘플 ≤ 60 mV | 위와 같음 |
| `stale` | 데이터 끊김 | 0.5 s 동안 샘플이 하나도 안 옴 (USB 분리, 보드 정지) | 샘플 다시 수신 |

그 밖에 `seq` 가 0.5 s 이상 건너뛰거나(대량 유실) 거꾸로 가면(보드 리셋) 필터·검출기를 새로 시작합니다.

### 다른 파이썬 코드에서 쓰기

```python
import time
from detector import BreathPhaseDetector, Phase, parse_sample, serial_lines

def on_change(st):            # 상태가 바뀔 때마다 (판정 보류 진입 포함)
    if st.phase == Phase.UNKNOWN:
        print("안전 상태로:", st.reason)

def on_event(ev):             # 흡기/호기 시작이 확정될 때마다
    print(ev.phase.name, f"지연 {ev.delay_s*1000:.0f} ms", ev.bpm)

det = BreathPhaseDetector(on_event=on_event, on_change=on_change)
for line in serial_lines("COM5", 115200):
    s = parse_sample(line) if line else None
    if s:
        det.update(*s)        # (seq, t_ms, mv)
    det.check_stale()         # 샘플이 안 와도 주기적으로 호출해야 끊김을 잡는다
    current = det.phase       # Phase.INHALE / Phase.EXHALE / Phase.UNKNOWN
```

| API | 설명 |
|---|---|
| `BreathPhaseDetector(fs=50, polarity=+1, on_event=None, on_change=None)` | `polarity=-1` 은 `--invert` 와 같음 |
| `update(seq, t_ms, mv) -> BreathEvent \| None` | 샘플 하나 처리. 전환이 확정되면 이벤트 반환 |
| `check_stale(now=None) -> bool` | 데이터 끊김 검사. 수신 루프에서 주기적으로 호출 |
| `phase` | 현재 `Phase` |
| `state() -> BreathState` | `phase, reason, t, bpm, amp_mv` |
| `BreathEvent` | `phase (INHALE/EXHALE), t, delay_s, bpm` |

---

## 검증 결과

`detector.py` 가 원본과 같은 결과를 내는지, Air-Rhythm 저장소의 **실측 CSV 11개**(`data/breath_*.csv`, 약 39,000 샘플)를 세 구현에 똑같이 넣어 비교했습니다.

| 구현 | 확정된 전환 수 | 결과 |
|---|---|---|
| Air-Rhythm `breath/detectors/slope.py` (원본) | 309 | 기준 |
| `visualizer.py` 의 `SlopeDetector` | 309 | 11개 파일 모두 종류·시각 일치 |
| `detector.py` 의 `BreathPhaseDetector` | 309 | 11개 파일 모두 종류·시각·호흡수 일치 |

안전 장치는 `--demo` 신호와 단위 테스트로 확인했습니다: 센서 분리(3145 mV) → 0.48 s 후 `sensor_open`, 복구 시 재안정화 / 0.8 s 유실 → 재시작 / 0.5 s 무수신 → `stale` / 보드 리셋(seq 역행) → 재시작 / `millis()` 32비트 순환 시에도 시간축 정상.


---