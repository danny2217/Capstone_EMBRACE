// =============================================================================
//  detect_config.h — 감각 과부하(전조) 판단 기준. ★ 판단 기준은 이 파일만 고친다 ★
// =============================================================================
//  지금 값은 전부 "임시값" 이다. 실측(생활 동작 6종, 호흡 TC-B1) 후 고친다.
//  고친 뒤 다시 업로드하면 부팅 헤더의 '# det ...' 줄에 현재 값이 찍혀 CSV 에 남는다.
//
//  판단 방식: 규칙마다 점수를 주고 합산한다.
//    규칙의 문턱을 0 으로 두면 그 규칙은 꺼진다.
//
//    점수 합 ≥ SCORE_TRIGGER  그리고  심박을 뺀 점수 ≥ MIN_NON_HR_SCORE
//    → 이 상태가 CONFIRM_MS 동안 계속되면 "과부하" 로 확정 → 가압 시작
//
//  심박 규칙은 워치 심박이 fresh(사용 가능) 일 때만 점수를 준다.
//  끊기면(stale) 자동으로 0 점 → 호흡·악력만으로 판단한다 (11/11 결정).
//  MIN_NON_HR_SCORE 때문에 심박 하나만으로는 절대 가압되지 않는다.
//
//  자세한 설명·예시: docs/control_fsm.md
// =============================================================================
#pragma once
#include <stdint.h>

namespace det {

constexpr bool DETECT_ENABLED = true;   // false 면 자동 가압을 하지 않는다 (시리얼 'i' 수동 가압만)

// ---------------------------------------------------------------------------
//  1) 호흡 신호 해석 (호흡 센서 이동평균 값을 쓴다)
// ---------------------------------------------------------------------------
//  들숨 때 mV 가 올라가는 센서면 false, 내려가는 센서면 true (visualizer 의 --invert 와 같은 뜻)
constexpr bool     BREATH_INVERT  = false;
//  봉우리·골짜기로 인정할 최소 흔들림 (mV). 정지 잡음보다 크고 평상 호흡 진폭보다 작게.
//  (예: 정지 잡음 ±10 mV, 평상 호흡 진폭 150 mV → 40 정도)
constexpr uint16_t BREATH_HYST_MV = 40;
//  호흡수·들숨날숨비를 계산하는 창 (ms) — 두 개를 따로 쓴다
//   판단용 : 짧아야 빨리 반응한다. 60 s 창이면 호흡이 갑자기 빨라져도 문턱을 넘기까지
//            30 s 이상 걸린다 (11/11 PC 모의 시험: 20→45 회/분 전환 후 약 35 s).
//   보고용 : 보호자 앱의 "1 분 동안의 들숨:날숨 비율" 정의 그대로 60 s.
constexpr uint32_t BREATH_DETECT_WINDOW_MS = 15000;
constexpr uint32_t BREATH_REPORT_WINDOW_MS = 60000;
//  호흡이 이만큼 안 보이면 '무호흡' — 가압·유지 중이면 비상 배기 (안전 규칙, 점수와 무관)
//  0 = 끔. 아이가 숨을 참는 경우도 배기된다 (보수적으로 둔다).
constexpr uint32_t BREATH_APNEA_MS = 20000;

// ---------------------------------------------------------------------------
//  2) 점수 규칙 — 문턱 0 = 그 규칙 끔
// ---------------------------------------------------------------------------
// [B] 빠른 호흡: 분당 호흡수 ≥ 문턱 (참고: 6~12세 안정 시 약 18~25 회/분)
constexpr uint16_t BREATH_RATE_HIGH = 40;    // 회/분
constexpr uint8_t  W_BREATH_FAST    = 2;

// [E] 들숨:날숨 비율 ≥ 문턱 (안정 시 약 0.5 = 1:2, 날숨이 짧아지면 커진다). 값 ×100 으로 적는다.
constexpr uint16_t IE_HIGH_X100     = 100;   // 100 = 1.00
constexpr uint8_t  W_IE             = 1;

// [G] 가방끈 꽉 쥐기: 이동평균이 GRIP_ON_MV 이상인 상태가 GRIP_HOLD_MS 이상 계속
//     꺼짐은 GRIP_OFF_MV 아래로 내려갈 때 (두 값 사이에서는 직전 상태 유지 = 히스테리시스)
constexpr uint16_t GRIP_ON_MV        = 1000;
constexpr uint16_t GRIP_OFF_MV       = 600;
constexpr bool     GRIP_REQUIRE_BOTH = false; // true = 양손 모두 쥐어야 인정
constexpr uint32_t GRIP_HOLD_MS      = 3000;
constexpr uint8_t  W_GRIP            = 2;

// [H] 심박 높음 (워치 심박이 fresh 일 때만)
constexpr uint8_t  HR_HIGH_BPM = 130;
constexpr uint8_t  W_HR        = 1;

// ---------------------------------------------------------------------------
//  3) 확정
// ---------------------------------------------------------------------------
constexpr uint8_t  SCORE_TRIGGER    = 3;
constexpr uint8_t  MIN_NON_HR_SCORE = 2;     // 심박 점수를 뺀 나머지가 최소 이만큼
constexpr uint32_t CONFIRM_MS       = 3000;  // 조건이 이만큼 계속돼야 확정 (순간 튐 무시)

// ---------------------------------------------------------------------------
//  4) 유지 중 조기 종료 (진정됨) — 기본 꺼짐. 켜면 유지 시간 전이라도 배기
// ---------------------------------------------------------------------------
constexpr bool     EARLY_RELEASE_ENABLED = false;
constexpr uint8_t  CALM_SCORE            = 0;      // 점수가 이 값 이하인 상태가
constexpr uint32_t CALM_MS               = 60000;  //   이만큼 계속되면 진정으로 본다
constexpr uint32_t HOLD_MIN_MS           = 60000;  // 단, 유지가 최소 이만큼은 지난 뒤

static_assert(GRIP_OFF_MV <= GRIP_ON_MV, "GRIP_OFF_MV 는 GRIP_ON_MV 이하여야 한다");
static_assert(MIN_NON_HR_SCORE >= 1, "MIN_NON_HR_SCORE 가 0 이면 심박 하나만으로 가압될 수 있다");
static_assert(SCORE_TRIGGER >= MIN_NON_HR_SCORE, "SCORE_TRIGGER 는 MIN_NON_HR_SCORE 이상");

}  // namespace det
