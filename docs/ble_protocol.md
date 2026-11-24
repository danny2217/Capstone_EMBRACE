# EMBRACE 통신 규격 v2 (워치 · 폰 · 조끼 · 서버)

> 2026-11-23. 기준 코드: `firmware/embrace_node/src/hr_link.cpp`, `watch-app/.../PhoneLink.kt`,
> `embrace-app/.../ble/VestConnection.kt`, `embrace-app/.../wear/WatchReceiver.kt`, 백엔드 `API.md`(embrace-backend 브랜치).
> v1(워치 → 조끼 BLE 직접 연결)은 `ble_hr_protocol.md` 이며 **폐기**.

```
[Galaxy Watch5] ─①Data Layer JSON─▶ [아동용 폰 앱] ─②BLE write HR_IN─▶ [조끼 ESP32-S3] (Wi-Fi 없음)
                                        ▲  │        ◀─③BLE notify STATUS 1 s──┘
                                        │  └───────③' BLE write CMD 'x' ─────▶ (배기만)
                                        ▼④ HTTP (API.md)
                                    [서버 EC2 :8080] ◀──HTTP──▶ [보호자 앱]
```

## 1. 워치 → 폰 (Wear OS Data Layer, MessageClient)

메시지 1개 = UTF-8 JSON 1개.

| path | JSON |
|---|---|
| `/embrace/hr` | `{"type":"hr","v":1,"bpm":72,"valid":true,"seq":12,"ts":1732240000000}` |
| `/embrace/loc` | ~~`{"type":"loc",...}`~~ **2026-11-23 폐기 — 폰이 무시, 워치 v2.1(11/24)도 더 이상 안 보냄.** 위치는 아동용 폰 GPS(`location/ChildLocationTracker.kt`, 10 s)가 직접 잡아 `POST /api/location` |

- `v ≠ 1`, JSON 오류, `lat`/`lon` 없음 → 폰이 무시.
- 전달 조건: 워치·폰 앱 **`applicationId` 동일 (`com.example.embraceapp`)** + 같은 서명 키 + 폰에 `WearableListenerService`(`.wear.WatchReceiver`) 등록.

## 2. 폰 ↔ 조끼 (BLE GATT)

조끼 = peripheral, 폰 = central. 광고 이름 **`EMBRACE-VEST`**.

| 특성 | UUID | 속성 | 방향 |
|---|---|---|---|
| 서비스 | `e3b70001-5c3a-4f6e-9a1d-2b8f0c4d7e10` | | |
| `HR_IN` | `e3b70002-…` | Write / Write w/o Response | 폰 → 조끼 |
| `STATUS` | `e3b70003-…` | Read / Notify | 조끼 → 폰 (1 s) |
| `CMD` | `e3b70004-…` | Write / Write w/o Response | 폰 → 조끼 |

(`…` = `-5c3a-4f6e-9a1d-2b8f0c4d7e10`)

### 2-1. `HR_IN` (4바이트)

| 바이트 | 이름 | 뜻 |
|---|---|---|
| 0 | version | `1` |
| 1 | bpm | 정수, 0 = 모름 |
| 2 | flags | bit0 = 측정 유효 |
| 3 | seq | 0~255 순환 |

예) 72 bpm, 유효, 순번 5 → `01 48 01 05`. 조끼가 버리는 값: 길이 < 4, version ≠ 1, 유효 비트 0, bpm < 30 또는 > 230.

### 2-2. `STATUS` (20바이트, 리틀 엔디언, 1초마다)

| 바이트 | 이름 | 설명 |
|---|---|---|
| 0 | version | `1` |
| 1 | seq | 순번 |
| 2 | state | 0 시작배기 · 1 대기 · 2 가압 · 3 유지 · 4 배기 · 5 휴식 · 6 비상배기 · 7 잠금 |
| 3 | flags | bit0 펌프 · 1 밸브 열림 · 2 armed · 3 overload · 4 breathValid · 5 breathHealthy · 6 apnea · 7 framesOk |
| 4 | score | 판단 점수 |
| 5 | hrState | 0 없음 · 1 끊김 · 2 재합류 · 3 정상 |
| 6 | bpm | 쓸 수 없으면 0 |
| 7–8 | breathRate | 호흡수 ×10 (회/분) |
| 9–10 | ieRatio | **들숨 ÷ 날숨** ×100 (`breath_analyzer.cpp:127`). 화면 표시 "1 : x" 는 **x = 1 / ieRatio** |
| 11–12 | gripL | 왼쪽 악력 (mV) |
| 13–14 | gripR | 오른쪽 악력 (mV) |
| 15–16 | compMaxMv | 가슴/등 압박 센서 중 큰 값 (mV) |
| 17–18 | inStateSec | 현재 상태 유지 시간 (초) |
| 19 | cycles | 가압 횟수 (최대 255) |

### 2-3. `CMD` (1바이트)

- 조끼는 **`d`(배기)·`x`(비상 배기)만** 받는다 (`hr_link.cpp:84`). `i`(가압)·`r`(잠금 해제)는 BLE 로 받지 않음 → 조끼 앞 시리얼/버튼으로만.
- 앱 SOS = **`x`** (2026-11-23 결정). `x` → 비상 배기 20 s → **잠금(state 7)**, 조끼 앞에서 `r` 로 해제.
- `PNEUMATIC_ARMED = false` 이면 명령은 받지만 펌프·밸브는 움직이지 않는다.

## 3. 폰 ↔ 서버 (HTTP)

자세한 내용은 embrace-backend 브랜치 `API.md`. 요약:
- 아동용 폰: `POST /api/vest/status`(STATUS 풀어서) · `/api/heart` · `/api/location` · `/api/alerts`, `GET /api/command/next` (원격 배기 → BLE `x`)
- 보호자 폰: `GET /api/summary` · `/api/alerts?after_id=`, `POST /api/command {"action":"exhaust"}`
- `child_id` = 자녀 전화번호 숫자만. 헤더 `X-API-Key`.
