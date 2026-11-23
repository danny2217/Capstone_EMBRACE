# EMBRACE 진행 현황 — 비주얼라이저 · 워치 앱 · 펌웨어 (2026-11-23 기준)

> 팀원이 하드웨어를 같이 보면서 코드를 고칠 수 있도록 정리한 문서다.
> **"검증됨"은 실제 보드·워치에서 돌려 본 것, "미검증"은 코드만 있고 실기에서 확인하지 않은 것이다.**
> 이 문서를 고친 사람은 맨 아래 변경 기록에 한 줄 남긴다.

---

## 1. 시스템 구조 (11/22 확정)

```
 [Galaxy Watch5]  ──① 심박·위치 (Wear OS Data Layer, 블루투스)──▶  [아동용 폰 앱]  ──④ 모든 정보 (인터넷)──▶  [보호자 앱 / 서버]
                                                                     │        ▲
                                                 ② 심박 (BLE write)  │        │ ③ 조끼 상태 (BLE notify, 1 s)
                                                                     ▼        │
                                                              [조끼 ESP32-S3]   ← Wi-Fi 없음
                                                    호흡·악력·압박 센서 / 펌프·밸브 (L298N)
```

- Wi-Fi(인터넷)는 **폰만** 쓴다. ESP 에는 Wi-Fi 코드가 없다.
- 워치는 조끼에 직접 연결하지 않는다 (v1 의 워치→조끼 BLE 직접 연결 구조는 폐기).
- 자세한 메시지 형식: `docs/ble_protocol.md` (v2). 옛 문서 `docs/ble_hr_protocol.md` 는 v1 이다.

| 링크 | 방식 | 담당 코드 | 상태 |
|---|---|---|---|
| ① 워치 → 폰 | Data Layer `MessageClient`, JSON `/embrace/hr`, `/embrace/loc` | 워치 `PhoneLink.kt` → 폰 `WatchReceiver.kt` | 미검증 |
| ② 폰 → 조끼 | BLE write `HR_IN` (4바이트, v1 형식 그대로) | 폰 앱 → ESP `hr_link.cpp` | 미검증 |
| ③ 조끼 → 폰 | BLE notify `STATUS` (UUID e3b70003, 20 B, 1 s) | ESP `hr_link.cpp` | 미검증 |
| ③' 폰 → 조끼 | BLE write `CMD` (UUID e3b70004, `d`/`x` 배기만 허용) | ESP `hr_link.cpp` | 미검증 |
| ④ 폰 → 보호자 | HTTP 또는 MQTT (미정) | 아동용·보호자 앱, 백엔드 | 미구현 |

---

## 2. 조끼 펌웨어 (`firmware/embrace_node`)

- 보드 **ESP32S3 Dev Module**, Arduino-ESP32 코어 **3.x**, 시리얼 115200 bps.
- 50 Hz 센싱 태스크 + 제어 태스크(FSM) 분리, `delay()` 미사용.
- 버전: v2.2.0(커밋됨, `feature/integration` 24d7102) → v2.3.0(성인 판별 규칙) → v2.4(폰 연동 BLE).

### 회로

| 채널 | 핀 | 배선 |
|---|---|---|
| 호흡 (전도성 고무) | GPIO4 | GPIO4 ─[센서]─ G (내부 풀업) |
| 악력 L / R (ZD10-100) | GPIO5 / 6 | 3V3 ─[센서]─ GPIO (내부 풀다운) |
| 가슴 / 등 압박 (ZD10-100) | GPIO7 / 8 | 3V3 ─[센서]─ GPIO (내부 풀다운) |
| 펌프 (L298N) | 15 / 16 | IN1 / IN2 → OUT1 / OUT2 |
| 밸브 (L298N, **NC**) | 17 / 18 | IN3 / IN4 → OUT3 / OUT4 (통전 = 열림) |
| 비상 정지 | GPIO0 | 보드 BOOT 버튼 |

- L298N GND ↔ ESP G ↔ 배터리 − 를 반드시 공통으로 묶는다.
- `FSR_HAS_BIAS = false` (바이어스 저항 없음 → FSR 단선 검출 불가).

### 상태 머신

`BootVent → Idle → Inflate → Hold → Deflate → Cooldown`, 이상 시 `Emergency → Lockout`.
워치독, BOOT 버튼 E-stop, 시리얼 명령 `i`(가압) / `d`(배기) / `x`(비상) / `r`(해제).

공압 기본값: INFLATE 8 s (상한 15 s), HOLD 3 분 (상한 5 분), DEFLATE 10 s, COOLDOWN 60 s, BOOT_VENT 5 s, EMERGENCY_VENT 20 s.

### 멜트다운 판별 규칙 (v2.3, 성인 착용자 기준)

| 기호 | 조건 | 점수 |
|---|---|---|
| B | 호흡수 ≥ 28 회/분 | 2 |
| I | 들숨 3 s 이상 | 2 |
| X | 날숨 4 s 이상 | 2 |
| V | 호흡 주기 변동계수 ≥ 평소 × 2 | 1 |
| E | I:E 비 ≥ 1.0 | 1 |
| G | 악력 ≥ 평소 × 3, 2.5 s 지속 | 1 |
| P | 10 s 안에 쥠 3회 | 1 |
| H | 심박 ≥ 130 bpm | 1 |

- **경로 1**: 호흡 점수 ≥ 2 그리고 합계 ≥ 3.
- **경로 2**: 센서는 정상인데 10 s 동안 완성된 호흡이 없음 + 강한 쥠 10 s.
- 어느 경로든 조건이 **3 s 유지되어야** 가압을 확정한다.
- 가압 시작 후 20 s 가 지나야 "무호흡 → 배기"를 인정한다 (가압 직후 스트랩이 당겨져 생기는 오판 방지).

### ⚠ 업로드 전에 확인

- 저장소의 `PNEUMATIC_ARMED` 는 **false** 가 기본이다. 공압 시험 때만 자기 PC 에서 true 로 바꾸고, **true 인 상태로 커밋하지 않는다.**
- BLE 를 넣으면 앱 크기가 커서 업로드가 실패할 수 있다 → Tools → Partition Scheme → **Huge APP**.
- 업로드 오류가 나면 출력창의 첫 번째 `error:` 줄 전체를 공유한다.

---

## 3. PC 비주얼라이저 (Python)

Python 3.11. 처음 한 번 `pip install -r requirements.txt`.

```powershell
python common/monitor_all.py --list                         # 포트 목록
python common/monitor_all.py --port COM5                    # 5채널 통합 그래프
python breath-sensor/software/visualizer.py --port COM5     # 호흡
python grip-sensor/software/visualizer.py --port COM5       # 악력
python compression-sensor/software/visualizer.py --port COM5 # 압박
python compression-sensor/software/calibrate.py --port COM5 --channel chest   # 압박 보정
```

- 모든 프로그램은 `--demo` (보드 없이 모의 데이터), `--replay <CSV>` (보고서용 PNG + 통계)를 지원한다.
- **포트는 한 번에 한 프로그램만** 열 수 있다. Arduino 시리얼 모니터를 켜 두면 파이썬이 포트를 못 연다.
- 모든 CSV 에 5채널이 다 기록되므로 `monitor_all` 로 찍은 CSV 를 각 센서 visualizer 의 `--replay` 로 다시 볼 수 있다.
- 공통 파서 `common/embrace_link.py` 하나가 시리얼 프로토콜을 해석한다. 프로토콜이 바뀌면 여기만 고친다.
- `DEMO_*` 파일과 그림은 **모의 데이터**다. 보고서에 실측 결과로 쓰면 안 된다.

### 시리얼 줄 읽는 법

| 줄 | 의미 |
|---|---|
| `seq t_ms breath grip_l grip_r chest back flags` | 데이터 (TAB 구분, mV, 20 ms 마다) |
| `flags` 예: `00010` | 채널당 한 글자. 0 정상 / 1 단선 / 2 단락 |
| `# fs=50.00Hz ... drop=0` | 1초마다 실제 측정 주기 |
| `# WARN ch=... state=open` | 센서 이상 |
| `# ctl ...` | 제어 상태: path, cv, in, ex, nocyc, gp, gon 등 (튜닝할 때 보는 줄) |
| `# det v2.3 ...` | 판별 규칙 점수 |
| `# hr ... app_tx= app_cmd= app_cmd_bad=` | 폰 연결·심박 수신·명령 수신 현황 (v2.4) |

---

## 4. 워치 앱 v2 (`watch-app/`, Galaxy Watch5 · Wear OS)

### 하는 일

- Health Services 운동 세션으로 심박을 받는다. 화면이 꺼져도 포그라운드 서비스로 계속 동작한다.
- 위치는 같은 운동 세션의 GPS 로 받고, **10 s 마다** 보낸다. 위치 권한을 거부하면 심박만 보낸다.
- 둘 다 Data Layer 로 **아동용 폰 앱**에 보낸다 (조끼에 직접 보내지 않음).

### 파일

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | 화면 하나: 심박 / 폰 연결 상태 / 위치 / 시작·중지 버튼. 권한 요청 |
| `HrService.kt` | 포그라운드 서비스, 운동 세션(심박 + 위치) |
| `PhoneLink.kt` | 폰으로 JSON 전송 (`/embrace/hr`, `/embrace/loc`) |
| `StatusBus.kt` | 서비스 → 화면 상태 전달 |
| `AndroidManifest.xml` | 심박(Wear OS 5.1 이하 `BODY_SENSORS`, 6 이상 `READ_HEART_RATE`)·위치·포그라운드 서비스 권한 |
| `phone-snippet/WatchReceiver.kt` | **폰 앱에 넣을 수신 예시** (WearableListenerService + 조끼용 4바이트 생성). 아동용 앱 담당자에게 전달 |

### ⚠ 폰으로 실제 전달되려면 (안 지키면 워치는 "전송 성공"인데 폰에 아무것도 안 온다)

1. 워치 앱 `applicationId` = 아동용 폰 앱 `applicationId` (현재 폰 앱은 `com.example.embraceapp`).
2. 두 앱이 **같은 서명 키**로 빌드되어야 한다 → 같은 PC 에서 debug 빌드하면 자동으로 같다.
3. 폰 앱에 `WearableListenerService` 가 등록되어 있어야 한다 (`phone-snippet/` 참고).
4. 옛 v1 앱이 워치에 남아 있으면 지운다: `adb uninstall com.embrace.watchhr`

### 워치 무선 ADB 재연결 절차

워치: 충전기 위에 올리고 개발자 옵션 "켜진 상태로 유지" ON → 설정 → 개발자 옵션 → 무선 디버깅 → 첫 화면의 **"IP 주소 및 포트"** 확인.

PowerShell (Android Studio Terminal):

```powershell
Set-Alias adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
adb connect 192.168.1.110:<워치에 지금 보이는 포트>
adb devices
```

- `Set-Alias` 는 터미널 창마다 한 번 실행한다. 페어링(`adb pair`)은 다시 할 필요 없다.
- 연결은 끊길 때마다 다시 하고, **포트는 매번 바뀐다**. 워치 IP 가 바뀌었으면 화면에 보이는 IP 를 쓴다.

| 오류 | 원인 → 조치 |
|---|---|
| 10061 / refused | 포트가 틀림. 페어링 포트가 아니라 첫 화면의 포트를 쓴다 |
| 10060 / timeout | 워치가 잠듦 → 화면을 켠다 |
| unauthorized | 워치 화면에서 "허용" |
| 그 외 | `adb kill-server` 후 다시 시도 |

---

## 5. 진행 상황

| 항목 | 상태 |
|---|---|
| 조끼·가방 배선 조립 | 완료 (11/20) |
| 펌웨어 v2.2 (센서 5채널 + FSM + 공압) | 커밋됨 |
| 펌웨어 v2.3 성인 판별 규칙 | PC 모의 시험 T1~T14 통과, **보드 컴파일 미확인** |
| 펌웨어 v2.4 폰 연동 BLE (STATUS / CMD) | **미컴파일** (문법 검사만 통과) |
| PC 비주얼라이저 · 보정 도구 | 동작 확인 (모의 데이터 기준) |
| 워치 앱 v1 (조끼 직접 BLE) | 워치에서 실행·권한·심박 화면 확인 (11/11) — **폐기 예정** |
| 워치 앱 v2 (폰 경유) | **워치 빌드·실행 미확인** |
| 폰 수신 코드 | 예시만 있음, 아동용 앱에 미통합 |
| 백엔드 (EC2, API :8080) | 동료 담당, `origin/embrace-backend` |

---

## 6. 미구현 · 다음 계획

1. **착용 튜닝 (최우선)** — 실측으로 채울 값:
   `GRIP_BASE_MV`(평소 악력, 현재 임시 300), `BREATH_CV_BASE_X100`(평소 호흡 변동계수), `BREATH_HYST_MV`, `BREATH_INVERT`, `EXHALE_HOLD_MS`.
   느린 호흡(8 회/분 이하)에서 X 규칙이 잠깐씩 켜지는 문제 → 평소 `ex=` 최대값을 보고 조정.
2. v2.3 / v2.4 보드 컴파일과 업로드 (Huge APP 파티션).
3. 워치 앱 v2 빌드 → 폰 수신 확인 (applicationId·서명 맞추기).
4. 아동용 폰 앱: WatchReceiver 통합, 조끼 BLE 연결(HR_IN write, STATUS 구독, CMD 배기 버튼).
5. 폰 → 서버 → 보호자 앱 경로 (HTTP / MQTT 결정 필요).
6. 팀 논의: 위치를 워치 GPS 로 받을 이유가 있는가 (폰이 BLE 범위 안이면 폰 GPS 로 충분).
7. 최종 데모용 정량 지표 측정: 판별 Recall / Precision, 전조 → 가압 지연 시간, 가압 정확도.

---

## 7. 알려진 문제 (안전 관련은 ★)

1. ★ **밸브가 NC** 라서 전원이 끊기면 공기가 갇힌다 → 퀵 커플러를 분리하는 수동 배기 절차가 필요하다. 활용계획서의 "NO 상시 개방형" 문구도 정정해야 한다.
2. ★ **압력 센서로 20~30 mmHg 를 보장하지 못한다.** 상한 보호는 가압 시간 제한뿐이다.
3. ★ FSR 단선을 검출할 수 없다 (바이어스 저항 없음).
4. 가압하면 호흡 스트랩이 당겨져 `breath_sensor_fault` 로 오배기할 수 있다.
5. BLE 인증이 없다 → 앱 CMD 는 **배기만 허용**하는 것으로 위험을 줄였다.
6. 폰 앱이 죽으면 심박이 끊긴다 → stale 처리하고 호흡·악력만으로 동작한다.

---

## 변경 기록

| 날짜 | 누가 | 내용 |
|---|---|---|
| 2026-11-23 | 신지후 | 문서 처음 작성 (v2.4 · 워치 앱 v2 업로드와 함께) |
