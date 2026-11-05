# EMBRACE 센서 모듈 — 다음 대화 인수인계 (2026-11-02 기준)

> 아래 내용은 이전 Claude 대화의 작업 상태다. 이 맥락을 이어받아 지도교수 페르소나(프로젝트 지침)로 진행해 줘.
> 나는 아두이노·회로 초보라서, 장치 관리자 여는 법 수준부터 단계별 설명이 필요하다.

## 1. 범위
- ESP32-S3 DevKitC-1 한 대로 호흡 1 + 악력 2 + 압박 2 = 5채널 측정 → PC 그래프 · CSV · 보고서 PNG.
- 검출 알고리즘(전조), 펌프·밸브 제어, BLE 는 범위 밖. 프레임 구조만 BLE 로 옮길 수 있게 설계.
- 설계 계획서: Claude 문서 "EMBRACE 통합 센서 펌웨어 설계 계획서 (ESP32-S3, 5채널)"

## 2. 확정된 결정
| 항목 | 결정 |
|---|---|
| 펌웨어 구조 | B안 — 펌웨어 1개(`firmware/embrace_node`) + `config.h` 로 채널 ON/OFF |
| 보드 | ESP32-S3 DevKitC-1, Arduino-ESP32 코어 3.x (3.3.4 로 컴파일 확인) |
| 핀 | 호흡 GPIO4, 악력 L/R GPIO5/6, 가슴/등 GPIO7/8 (ADC1 만) |
| 호흡 회로 | 외부 풀업 R_pu (HANDOFF v1 의 B안). 값은 새 센서 이완 저항 |
| 압박 | **힘 집중판 없음 — 감지부 단독 하중 가정** (11/2 팀 결정) |
| 분압 저항 | 악력 R_ref 10 kΩ · R_bias 150 kΩ / 압박 R_ref 470 kΩ · R_bias 4.7 MΩ (실측 후 조정) |
| 폴더 | `breath-sensor`, `grip-sensor`, `compression-sensor`, `common`, `docs` |
| v1 펌웨어 | 태그 `v1.0-wroom32` 로 보존 후 삭제 |

## 3. 진행 상황
- [x] 펌웨어 v2 작성, ESP32-S3 컴파일 확인 (프로그램 22%, RAM 6%), 잘못된 핀·보드는 컴파일 오류로 차단
- [x] PC 공통 모듈, 센서별 visualizer 3종, detector, calibrate, monitor_all — 모의 데이터로 전 경로 검증
- [ ] 실보드 업로드 · TC-F1~F4
- [ ] 저항 실측 (`docs/sensor_specs.md` 표) → `config.h` 저항값 확정
- [ ] TC-C2 압박 판정 (11/6 금) → 유지 또는 XGZP6847A010KPG33 전환 (재료비 마감 11/30 17시)

## 4. 열린 문제
1. **압박 신호 크기**: 감지부 Ø10 mm 가정 시 20~30 mmHg = 21~32 g, 응답 하한(20 g 미만) 바로 위. 감지부 지름 실측 필요.
2. **솔레노이드 밸브 NC/NO 모순**: 구입 계획서 "NC", 활용계획서 "NO, 전원 차단 시 배기". NC 면 Fail-Safe 불성립.
3. 호흡 센서 파손 → 교체품 최대 신장률·내구성 사양 기록.
4. 지연 예산: 압박 제어 경로 약 110 ms (RC 21 + 이동평균 90). 전조 감지 → 가압 허용 지연과 함께 정리 필요.

## 5. 다음 대화에 첨부할 것
1. 저장소 zip (또는 GitHub 최신)
2. `docs/sensor_specs.md` 에 채운 저항 실측값, 감지부 지름
3. `common/monitor_all.py` 요약 txt (TC-F1~F4)
4. `compression-sensor/report_images/calib_*.png`, `creep_*.png` 와 calibrate 출력 화면 (TC-C2)
5. 에러가 나면: 출력창 첫 번째 `error:` 줄 전체
