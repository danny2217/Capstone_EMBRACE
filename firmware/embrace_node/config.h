// =============================================================================
//  config.h — embrace_node 설정 (학생이 고치는 유일한 파일)
// =============================================================================
//  센서를 하나만 시험할 때 : 해당 ENABLE_* 만 true, 나머지는 false
//  회로 상수를 바꿨을 때   : *_OHM 값을 실측값으로 고친다
//                           (부팅 헤더에 그대로 찍혀 CSV 에 남는다 → 보고서 근거)
//  압박 센서를 바꿨을 때   : COMPRESSION_SOURCE 한 줄만 바꾼다
//
//  배선 요약 (ESP32-S3 DevKitC-1, ADC1 만 사용)
//
//    호흡  : 3V3 ─[R_pu]─┬─ GPIO4           (늘이면 저항이 변하는 고무 센서)
//                        └─[호흡 센서]─ GND
//
//    FSR   : 3V3 ─[ZD10-100]─┬─ GPIOx ─┬─[R_ref]─ GND
//            (센서 끝단에 R_bias 를 센서와 병렬로 연결)  └─[100nF]─ GND
//            악력 L = GPIO5, 악력 R = GPIO6  : R_ref 10 kΩ,  R_bias 150 kΩ
//            가슴   = GPIO7, 등     = GPIO8  : R_ref 470 kΩ, R_bias 4.7 MΩ  ← 100nF 필수
//
//  압박은 힘 집중판 없이 "감지부만 하중을 받는다" 고 가정한다 (11/2 팀 결정).
//  20~30 mmHg 가 감지부 힘 약 21~32 g (Ø10 mm 가정) 이라 응답 하한 바로 위다.
//  → 압박 채널은 R_ref 를 크게 해 회로에서 신호를 키운다 (10 kΩ 대비 약 10배, 예시값).
//
//  자세한 그림과 이유: docs/wiring.md
// =============================================================================
#pragma once
#include <stdint.h>

namespace cfg {

// ---------------------------------------------------------------------------
//  1) 채널 ON/OFF
// ---------------------------------------------------------------------------
constexpr bool ENABLE_BREATH = true;   // 가슴 스트랩 호흡 인장 센서
constexpr bool ENABLE_GRIP_L = true;   // 가방끈 왼쪽 악력 (ZD10-100)
constexpr bool ENABLE_GRIP_R = true;   // 가방끈 오른쪽 악력 (ZD10-100)
constexpr bool ENABLE_CHEST  = true;   // 가슴 압박 (ZD10-100, 집중판 없음)
constexpr bool ENABLE_BACK   = true;   // 등 압박   (ZD10-100, 집중판 없음)

// ---------------------------------------------------------------------------
//  2) 핀 — ESP32-S3 의 ADC1 은 GPIO1~10 뿐이다 (그 밖이면 컴파일 오류)
//     GPIO0/3/45/46(부팅), 19/20(USB), 11~20(ADC2, Wi-Fi 와 충돌) 은 쓰지 않는다
// ---------------------------------------------------------------------------
constexpr uint8_t PIN_BREATH = 4;    // ADC1_CH3
constexpr uint8_t PIN_GRIP_L = 5;    // ADC1_CH4
constexpr uint8_t PIN_GRIP_R = 6;    // ADC1_CH5
constexpr uint8_t PIN_CHEST  = 7;    // ADC1_CH6
constexpr uint8_t PIN_BACK   = 8;    // ADC1_CH7

// ---------------------------------------------------------------------------
//  3) 회로 상수 (Ω) — 실측 후 기입. 0 = 아직 모름 (헤더에 'unknown' 으로 표시)
// ---------------------------------------------------------------------------
//  R_ref 고르는 법: 측정할 구간 양 끝의 센서 저항 R1, R2 를 재서 R_ref ≈ √(R1·R2)
//                   (그때 두 점의 전압 차가 가장 크다)
//  R_bias        : 동작 구간 센서 저항의 10배 이상 (감도를 거의 깎지 않으면서 단선 검출)
constexpr uint32_t BREATH_R_PU_OHM = 0;         // 호흡 외부 풀업 (Ω). 0 = 외부 저항 없음

// 저항 없이 호흡 센서를 쓰는 방법 (BREATH_R_PU_OHM = 0 일 때만 적용)
//   BREATH_SOURCE_PIN = 0  : 칩 내부 풀업(약 45 kΩ) 사용. 센서 저항이 수십 kΩ 일 때 맞다.
//   BREATH_SOURCE_PIN = 15 : GPIO15 를 "약한 세기(drive cap 0)의 HIGH 출력" 으로 켜서
//                            풀업 저항 대신 쓴다. 핀 내부 트랜지스터가 약 100~300 Ω 의
//                            풀업처럼 동작한다 → 센서가 수백 Ω 이하일 때 신호가 수십 배 커진다.
//                            배선: GPIO15 ─(점퍼선)─ GPIO4 ─[호흡 센서]─ GND  (3V3 는 쓰지 않음)
//   ⚠ 시험대(bench)용이다. 출력 세기는 칩·온도마다 다르고 데이터시트로 보증되지 않는다.
//     센서로 약 5~15 mA 가 계속 흐르므로, 제품에서는 실제 저항 R_pu 로 바꾼다.
constexpr uint8_t  BREATH_SOURCE_PIN = 0;     // 0 = 내부 풀업 (11/4 시험에서 집게 간격을 넓혀 정상 동작 확인)
//  ★ R_REF 를 0 으로 두면 외부 저항 없이 "칩 내부 풀다운(약 45 kΩ, 칩마다 30~80 kΩ)" 을 R_ref 로 쓴다.
//    배선: 3V3 ─[ZD10-100]─ GPIO  (선 두 개, 저항 없음). 11/5 현재 저항이 없어 이 방식을 쓴다.
//    이때는 R_bias 도 없으므로 FSR_HAS_BIAS = false (단선 검출 꺼짐 — 아래 주의 참고).
constexpr uint32_t GRIP_R_REF_OHM  = 0;         // 악력: 0 = 내부 풀다운. 저항이 생기면 10000
constexpr uint32_t GRIP_R_BIAS_OHM = 150000;    //   무부하 기준 약 206 mV
constexpr uint32_t COMP_R_REF_OHM  = 0;         // 압박: 0 = 내부 풀다운. 저항이 생기면 470000
constexpr uint32_t COMP_R_BIAS_OHM = 4700000;   //   무부하 기준 약 300 mV

// 바이어스 저항을 아직 안 달았으면 false.
//  → 무부하 FSR 은 0 V 이고 단선도 0 V 라서 둘을 구분할 수 없다.
//    false 면 FSR 채널의 '단선' 판정을 끈다 (헤더에 open_check=off 로 표시).
//    ⚠ 압박 채널은 단선을 '압력 0' 으로 읽으면 계속 가압될 수 있다. 시제품에서는 반드시 true.
constexpr bool FSR_HAS_BIAS = false;           // 바이어스 저항 없음 (저항이 생기면 true)

// ---------------------------------------------------------------------------
//  4) 압박 채널 센서 종류 — 전환 시 이 줄만 바꾼다
//     FSR_ZD10      : ZD10-100, 집중판 없이 감지부 단독 하중 (현재)
//     XGZP6847A_33  : 공압 라인 압력센서 XGZP6847A010KPG33 (0~10 kPa, 출력 0.2~2.7 V)
//                     → 센서 출력을 GPIO7/8 에 직접 연결, 분압 저항 불필요
// ---------------------------------------------------------------------------
enum class PressureSource : uint8_t { FSR_ZD10, XGZP6847A_33 };
constexpr PressureSource COMPRESSION_SOURCE = PressureSource::FSR_ZD10;

// ZD10-100 감지부(원형 감지 영역) 지름 (mm) — 자로 실측해서 고친다.
// 집중판이 없으므로 에어백 압력은 이 면적에만 걸린다고 본다.
// PC 프로그램이 "감지부 하중(g) → 등가 압력(mmHg)" 환산에 쓴다 (P = m·g / 감지부 면적).
constexpr float SENSE_DIAMETER_MM = 10.0f;

// ---------------------------------------------------------------------------
//  5) 샘플링
// ---------------------------------------------------------------------------
constexpr uint32_t BAUD       = 115200;
constexpr uint32_t PERIOD_MS  = 20;     // 50 Hz. 호흡(0.1~0.7 Hz)·악력(수 Hz) 대비 충분
constexpr uint8_t  DISCARD    = 1;      // 채널 전환 직후 버리는 변환 수 (크로스토크 방지)
constexpr uint8_t  OVERSAMPLE = 16;     // 1회 측정당 평균낼 변환 수 (8→16: 잡음 약 ÷1.4 추가 감소)
                                        // 변환 1회 약 52 us (11/4 실측) → 5채널 × 17회 ≈ 4.4 ms. '# fs' 의 work_max ≤ 5000us 확인
constexpr uint8_t  MA_WINDOW  = 10;     // 이동 평균 길이 0.2 s (군지연 약 90 ms)
constexpr bool     SEND_MA    = false;  // true 면 각 채널의 이동평균 열(<이름>_ma)도 보낸다

// ---------------------------------------------------------------------------
//  6) 상태 판정 문턱 (mV) — 같은 상태가 FAULT_HOLD 샘플 이상 이어질 때만 인정
// ---------------------------------------------------------------------------
// 호흡(풀업형): 단선 → 레일 근처, 단락 → 0 근처
constexpr uint32_t PULLUP_OPEN_MV  = 3000;
constexpr uint32_t PULLUP_SHORT_MV = 60;
// FSR(풀다운형): 단선 → 0 근처 (바이어스 기준 약 200 mV 보다 낮음), 단락 → 레일 근처
constexpr uint32_t FSR_OPEN_MV     = 80;
constexpr uint32_t FSR_SHORT_MV    = 3000;
// XGZP6847A_33: 정상 출력 0.2~2.7 V
constexpr uint32_t XGZP_OPEN_MV    = 100;
constexpr uint32_t XGZP_SHORT_MV   = 2950;

constexpr uint32_t FAULT_HOLD      = 25;    // 0.5 s

// ---------------------------------------------------------------------------
//  7) 출력
// ---------------------------------------------------------------------------
constexpr uint32_t HEADER_EVERY_MS = 5000;  // 헤더 재전송 주기 (PC 가 늦게 연결해도 5 s 안에 받음)
constexpr bool     REPORT_RATE     = true;  // 1 s 마다 '# fs=...' 진단 줄
constexpr uint8_t  QUEUE_LEN       = 50;    // 센싱 → 전송 큐 길이 (1 s 분량)

// ---------------------------------------------------------------------------
//  8) 워치 심박 수신 (BLE) — 프로토콜: docs/ble_hr_protocol.md
// ---------------------------------------------------------------------------
//  ESP 가 BLE "서버(peripheral)" 로 광고하고, 워치 앱이 접속해서 1 초마다 심박을 써 넣는다.
//  워치가 없거나 끊겨도 센서·공압은 그대로 동작한다. 심박은 "있으면 쓰는" 보조 입력이다.
//
//  심박 상태 3가지
//    none  : 한 번도 받은 적 없음 (워치 없이 쓰는 경우)
//    stale : 받았었지만 HR_STALE_MS 동안 새 값이 없음 → 판단에서 뺀다
//    fresh : 최근 값이 있고, 끊겼다 돌아온 뒤 HR_REJOIN_SAMPLES 개 이상 연속으로 받음
//            → 판단에 다시 넣는다 (돌아오자마자 튀는 첫 값으로 판단하지 않게)
constexpr bool     ENABLE_HR_BLE     = true;          // false 면 BLE 를 아예 켜지 않는다
constexpr char     BLE_DEVICE_NAME[] = "EMBRACE-VEST"; // 워치 앱이 이 이름/서비스로 찾는다
constexpr uint32_t HR_STALE_MS       = 12000;         // 이 시간 동안 새 심박이 없으면 stale
                                                      //  워치 화면이 꺼지면 심박이 5 초씩 묶여서 온다
                                                      //  (BatchingMode.HEART_RATE_5_SECONDS). 5 s 로 두면
                                                      //  정상인데도 stale↔fresh 를 오간다 → 묶음 2회 + 여유
constexpr uint8_t  HR_REJOIN_SAMPLES = 3;             // stale → fresh 로 돌아가는 데 필요한 연속 수신 수
constexpr uint8_t  HR_MIN_BPM        = 30;            // 이 범위를 벗어난 값은 버린다 (센서 오류)
constexpr uint8_t  HR_MAX_BPM        = 230;
constexpr uint32_t HR_REPORT_MS      = 1000;          // '# hr ...' 진단 줄 주기

// 태스크 배치 (코어 1 = Arduino 기본 코어)
constexpr int      SENSE_CORE  = 1;
constexpr unsigned SENSE_PRIO  = 3;
constexpr unsigned TX_PRIO     = 2;
constexpr uint32_t TASK_STACK  = 4096;

}  // namespace cfg
