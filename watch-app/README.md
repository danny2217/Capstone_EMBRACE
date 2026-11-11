# EMBRACE 워치 심박 송신 앱 (최소판)

갤럭시 워치5(SM-R900)에서 심박을 재서 조끼(ESP32-S3)로 1 초마다 보내는 앱.
화면은 하나: **심박 / 조끼 연결 상태 / 시작·중지 버튼**. 그 밖의 기능은 일부러 넣지 않았다.

```
[워치] ExerciseClient(운동 세션) ─심박─▶ HrService(포그라운드 서비스) ─BLE write─▶ [ESP32] hr_link
```

| 파일 | 하는 일 |
|---|---|
| `src/main/AndroidManifest.xml` | 권한, 서비스 선언 |
| `.../MainActivity.kt` | 화면, 권한 요청, 시작/중지 |
| `.../HrService.kt` | 화면이 꺼져도 도는 서비스. 운동 세션으로 심박 받기 |
| `.../HrBleSender.kt` | 조끼 찾기·접속·재접속, 4바이트 쓰기 |
| `.../StatusBus.kt` | 서비스 → 화면 상태 전달 |

프로토콜은 `docs/ble_hr_protocol.md`.

> ⚠ 이 코드는 아직 **한 번도 빌드되지 않았다** (작성 환경에 안드로이드 SDK 가 없었음).
> 처음 빌드할 때 오류가 나면 Build 창의 첫 번째 `e:` 줄을 그대로 복사해서 보여줄 것.

---

## 1. Android Studio 프로젝트 만들기 (처음 한 번)

저장소 안에 Android Studio 프로젝트 전체를 넣지 않고, **템플릿으로 만든 뒤 우리 파일 5개를 덮어쓰는** 방식이다.
(Gradle 버전은 각자 PC 의 Android Studio 버전에 맞춰 템플릿이 정해 주므로 충돌이 적다)

1. Android Studio → **File > New > New Project**
2. 왼쪽 목록에서 **Wear OS** → **Empty Wear App** → Next
3. 입력:
   - Name: `EmbraceWatchHr`
   - Package name: `com.embrace.watchhr` ← **반드시 이대로** (코드의 package 줄과 같아야 함)
   - Save location: 저장소 **밖** 아무 폴더 (예: `D:\2026-2\EmbraceWatchHr`)
   - Minimum SDK: 아무거나 (아래 3번에서 바꾼다)
4. Finish → 아래쪽 진행 막대(Gradle sync)가 끝날 때까지 기다린다 (처음엔 몇 분).

## 2. 파일 덮어쓰기

왼쪽 Project 창을 **Project** 보기로 바꾸면 실제 폴더가 보인다.

1. `app/src/main/AndroidManifest.xml` → 이 저장소의 `watch-app/src/main/AndroidManifest.xml` 내용으로 **통째로 교체**
2. `app/src/main/java/com/embrace/watchhr/` 안에 있는 템플릿 파일·폴더(`presentation` 등)를 **삭제**
3. 같은 위치에 이 저장소의 `.kt` 파일 4개를 복사

## 3. `app/build.gradle.kts` 고치기

`android { defaultConfig { ... } }` 안의 줄을 바꾼다:

```kotlin
minSdk = 34
```

`dependencies { ... }` 안에 세 줄을 추가한다:

```kotlin
implementation("androidx.health:health-services-client:1.1.0-rc02")
implementation("com.google.guava:guava:33.3.1-android")
implementation("androidx.core:core-ktx:1.13.1")
```

오른쪽 위 **Sync Now** 를 누른다.

## 4. 워치를 PC 에 무선 연결

1. 워치: **설정 > 워치 정보 > 소프트웨어 정보 > 소프트웨어 버전** 을 7번 연속 탭 → "개발자 모드 켜짐"
2. 워치: **설정 > 개발자 옵션 > ADB 디버깅** 켜기, **무선 디버깅** 켜기
3. 워치와 PC 를 **같은 Wi-Fi** 에 연결 (워치: 설정 > 연결 > Wi-Fi)
4. 워치: 무선 디버깅 화면 → **새 기기 페어링** → 6자리 코드와 `IP:포트` 가 나온다
5. Android Studio: 위쪽 기기 선택 칸 → **Pair Devices Using Wi-Fi** → **Pair using pairing code** → 워치에 나온 값 입력
6. 기기 선택 칸에 `Galaxy Watch5 ...` 가 보이면 성공

## 5. 실행

1. 초록 ▶ (Run) → 워치에 앱이 깔리고 화면이 뜬다
2. **시작** → 권한 창(심박/근처 기기/알림) 모두 **허용**
3. 화면에 `심박: 72 bpm`, `조끼: 찾는 중…` 이 보이면 정상 (조끼가 꺼져 있으면 계속 "찾는 중/재시도")
4. 조끼(ESP)를 켜면 `조끼: 연결됨` → ESP 시리얼에 `# hr state=fresh bpm=..` 가 나온다
5. 끝낼 때는 반드시 **중지** (운동 세션이 꺼져야 배터리가 안 닳는다)

## 6. 자주 나는 문제

| 증상 | 원인·조치 |
|---|---|
| `운동 시작 실패` | 삼성 헬스에서 운동 기록이 켜져 있음 → 끄고 다시 시작 (운동 세션은 앱 하나만 가질 수 있다) |
| `권한이 필요합니다` | 워치 설정 > 애플리케이션 > EMBRACE 심박 > 권한 → 모두 허용 |
| 계속 `찾는 중…` | ESP 전원/펌웨어 확인. 폰의 nRF Connect 에서 `EMBRACE-VEST` 가 보이는지 먼저 확인 |
| 빌드 오류 `'onRegistered' overrides nothing` | `HrService.kt` 의 `onRegistered`, `onRegistrationFailed` 두 함수를 지운다 (라이브러리 버전 차이) |
| 빌드 오류 `Unresolved reference: ...` | Gradle Sync 를 다시 하고, 3번의 dependencies 세 줄이 들어갔는지 확인 |
