# EMBRACE 백엔드 API v2 (HTTP)

> 2026-11-23 · 앱 통신 규격(신희, "EMBRACE 앱 — 워치·조끼 통신 연동 정리")에 맞춰 다시 정리.
> 예전 v1(`/api/vitals`, `/api/sensors`, `inflate` 명령, "ESP32 가 명령을 가져감")은 **폐기**.

## 0. 구조

```
[워치] ─Data Layer─▶ [아동용 폰 앱] ─BLE─▶ [조끼 ESP32] (Wi-Fi 없음)
                         │  ▲
          POST (업로드)   │  │ GET /api/command/next (원격 배기 확인)
                         ▼  │
                     [이 서버 :8080]
                         ▲
          GET (조회)      │  POST /api/command (원격 배기)
                     [보호자 앱]
```

- 서버에 **쓰는 쪽은 아동용 폰 앱**, **읽는 쪽은 보호자 앱**. 조끼는 서버에 직접 붙지 않는다.
- 모든 `/api/*` 요청 헤더에 `X-API-Key: <.env 의 API_KEY>`. 틀리면 `401`.
- 아이 구분 키는 `child_id` (Firebase 로그인 uid 를 쓰는 것을 권장).
- `ts` = 폰에서 값을 받은 시각 (epoch ms, 선택). 서버는 받은 시각을 `created_at`(UTC, `Z`)으로 따로 저장한다.
- 조회 응답에는 `age_sec`(서버가 받은 뒤 지난 초)가 붙는다 → 보호자 앱은 이 값이 크면 "연결 끊김"으로 표시.

## 1. 아동용 폰 앱 → 서버 (업로드)

| 메서드·경로 | 언제 보내나 | 본문 |
|---|---|---|
| `POST /api/vest/status` | 조끼 STATUS 수신 시. **2~5초에 1번**이면 충분 (1초마다도 가능) | §1-1 |
| `POST /api/heart` | 워치 `/embrace/hr` 수신 시 (5초에 1번 정도로 줄여도 됨) | `{"child_id","bpm","valid","source":"watch"\|"vest","ts"}` |
| `POST /api/location` | 워치 `/embrace/loc` 수신 시 (10초마다) | `{"child_id","lat","lon","acc_m"(선택),"ts"}` |
| `POST /api/alerts` | 앱 자동 알림 규칙(앱 문서 4절)이 새 알림을 만들 때 + SOS 누를 때 | §1-2 |
| `GET /api/command/next?child_id=` | 조끼에 연결되어 있는 동안 **3~5초마다** | §3 |

모든 POST 응답: `{"id": 123}`. 형식이 틀리면 `422` + 어느 칸이 틀렸는지.

### 1-1. `POST /api/vest/status` — STATUS 20바이트를 풀어 놓은 값 (앱 `VestStatus` 와 1:1)

```json
{
  "child_id": "uid123", "seq": 5, "state": 1,
  "pump": false, "valve_open": false, "armed": false, "overload": false,
  "breath_valid": true, "breath_healthy": true, "apnea": false, "frames_ok": true,
  "score": 0, "hr_state": 3, "bpm": 72,
  "breath_rate": 18.5, "ie_ratio": 0.5,
  "grip_l": 120, "grip_r": 110, "comp_max_mv": 40,
  "in_state_sec": 12, "cycles": 0, "ts": 1732240000000
}
```

| 칸 | STATUS 바이트 | 비고 |
|---|---|---|
| `state` | 2 | 0 시작배기 · 1 대기 · 2 가압 · 3 유지 · 4 배기 · 5 휴식 · 6 비상배기 · 7 잠금 |
| `pump` … `frames_ok` | 3 (flags bit0~7) | 앱이 이미 풀어 둔 Boolean 그대로 |
| `hr_state` | 5 | 0 없음 · 1 끊김 · 2 재합류 · 3 정상 |
| `breath_rate` | 7–8 | **÷10 한 값** (회/분) |
| `ie_ratio` | 9–10 | **÷100 한 값**, 조끼 정의 = **들숨 ÷ 날숨** (`breath_analyzer.cpp:127`) |
| 나머지 | 표 그대로 | |

### 1-2. `POST /api/alerts`

```json
{"child_id":"uid123","level":"danger","code":"apnea","title":"무호흡 감지","message":"호흡이 멈춘 것으로 감지됐어요.","ts":1732240000000}
```
- `level`: `danger` / `warning` / `stable` (앱 `AlertLevel` 의 Danger / Warning / Stable)
- `code` (권장): `apnea`, `overload`, `inflate`, `emergency`, `lockout`, `ble_lost`, `sos`

## 2. 보호자 앱 ← 서버 (조회)

| 메서드·경로 | 용도 |
|---|---|
| `GET /api/summary?child_id=` | **홈 화면 한 번에**: 최신 조끼 상태 + 심박 + 위치 + `last_alert_id`. 3~5초마다 |
| `GET /api/alerts?child_id=&after_id=` | `after_id` 보다 뒤의 새 알림만 (오래된 순). 보호자 앱은 마지막으로 받은 id 를 기억 |
| `GET /api/vest/status/latest?child_id=` | 최신 조끼 상태만 |
| `GET /api/vest/status/history?child_id=&minutes=10` | 그래프용 (최대 1440분, 최대 5000개) |
| `GET /api/heart/latest?child_id=` | 최신 심박 |
| `GET /api/location/latest?child_id=` | 최신 위치 (지도 핀) |

조끼 상태 응답에는 서버가 붙여 주는 칸이 있다:
- `state_name` ("대기" 등), `hr_state_name` ("정상" 등)
- `ie_text`: 화면용 **`"1 : 2.0"`** (= 1 : 날숨÷들숨). 호흡이 유효하지 않으면 `null`.
  → 아동용 앱도 `ieRatio` 를 그대로 x 에 넣으면 거꾸로 나온다. **x = 1 / ieRatio** 로 고쳐야 함.

아직 값이 없으면 `*/latest` 는 `404`, `summary` 는 해당 칸이 `null`.

## 3. 원격 배기 (보호자 → 서버 → 아동용 폰 → 조끼)

1. 보호자 앱: `POST /api/command` `{"child_id":"uid123","action":"exhaust"}`
   - **`exhaust` 만 받는다.** `inflate` 등은 `422`. 가압은 조끼 앞에서만 가능 (안전 규칙).
2. 아동용 폰 앱: `GET /api/command/next?child_id=uid123`
   - 있으면 `{"action":"exhaust","ble_byte":"x","id":1}` → BLE `CMD` 에 `x` 1바이트 write
   - 없으면 `{"action":"none"}`
   - 한 번 가져가면 끝 (쌓인 명령은 최신 1개만 주고 모두 폐기).
   - **60초 넘게 지난 명령은 주지 않는다** (`{"action":"none","expired_id":..}`) — 늦게 켜진 폰이 옛 명령을 실행하지 않도록.
3. 아동용 폰 앱은 결과를 `POST /api/alerts` (`code: "sos"`)로 남긴다.

> ⚠ `x` = **비상 배기** → 조끼는 20초 배기 후 **잠금(state 7)** 으로 들어가고, 조끼 앞에서 `r` 을 눌러야 풀린다 (`control_task.cpp:128,177`). SOS 를 `x` 로 확정했으므로 정상 동작이다.

## 4. 앱에서 HTTP 쓸 때 주의

- **안드로이드는 기본으로 `http://` 평문 통신을 막는다** (API 28+). EC2 를 `http://IP:8080` 으로 쓰면
  `AndroidManifest.xml` 에 `network_security_config` 로 그 IP 만 허용하거나, 서버에 HTTPS 를 붙여야 한다 (§5).
- `API_KEY` 는 앱 안에 들어가므로 완벽한 보안은 아니다 (캡스톤 데모 수준). 키를 바꾸면 앱도 다시 빌드.
- 업로드 실패 시 재시도는 최근 값만 (밀린 것을 몰아서 보내지 않는다).

## 5. 배포 (EC2)

```bash
git pull                         # embrace-backend 브랜치
docker compose up -d --build     # 코드가 바뀌었으니 --build 필수
curl http://localhost:8080/health
```
- 기존 `data/embrace.db` 를 그대로 둬도 된다. 새 테이블(`vest_status`, `heart_rates`, `child_locations`, `alerts`, `vest_commands`)이 추가로 생기고, 예전 테이블은 쓰지 않을 뿐이다. 깨끗이 시작하려면 컨테이너를 내린 뒤 `data/embrace.db` 를 지운다 (실데이터가 없을 때만).
- 보안그룹 인바운드: TCP **8080** (또는 HTTPS 를 붙이면 443) 허용.
