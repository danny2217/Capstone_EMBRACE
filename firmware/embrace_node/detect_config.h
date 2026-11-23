// =============================================================================
//  detect_config.h — 감각 과부하(멜트다운 전조) 판단 기준. ★ 판단 기준은 이 파일만 고친다 ★
// =============================================================================
//  v2.3 (11/20): 성인 착용 기준 + 자료조사 기준표(호흡 주기·악력·심박) 반영.
//  지금 값은 전부 "임시값" 이다. 오늘 밤 실측(평소 호흡·평소 악력) 후 [측정] 표시된 값을 고친다.
//  고친 뒤 다시 업로드하면 부팅 헤더의 '# det ...' 줄에 현재 값이 찍혀 CSV 에 남는다.
//
//  ── 판단 순서: 호흡이 1순위 ──────────────────────────────────────────────
//
//  [경로 1] 호흡이 보일 때 (평소)
//      호흡 점수 ≥ BREATH_MIN_SCORE   그리고   전체 점수 ≥ SCORE_TRIGGER
//      → 악력·심박은 "호흡 이상을 거드는" 역할만 한다.
//        호흡이 정상이면 가방끈을 아무리 쥐어도, 심박이 아무리 높아도 가압하지 않는다.
//
//  [경로 2] 호흡이 안 보일 때 (몸부림으로 주기를 못 셈)
//      호흡 센서는 정상(단선·단락 아님)인데 완성된 호흡이 UNREC_BREATH_MS 동안 없음
//      그리고 강한 악력(평소의 3배)이 UNREC_GRIP_HOLD_MS 이상 계속
//      → "몸부림을 동반한 과부하" 로 본다. 악력이 이 시간을 못 채우면 가압하지 않는다.
//      ※ 호흡 센서가 단선·단락이면 경로 2 도 막힌다 (고장과 몸부림을 구분 못 하므로).
//
//  두 경로 모두 조건이 CONFIRM_MS 동안 끊기지 않아야 확정 → 가압 시작.
//
//  규칙의 문턱을 0 으로 두면 그 규칙은 꺼진다.
//  자세한 설명·예시: docs/control_fsm.md 4절
// =============================================================================
#pragma once
#include <stdint.h>

namespace det {

constexpr bool DETECT_ENABLED = true;   // false 면 자동 가압을 하지 않는다 (시리얼 'i' 수동 가압만)

// ---------------------------------------------------------------------------
//  1) 호흡 신호 해석 (호흡 센서 이동평균 값을 쓴다)
// ---------------------------------------------------------------------------
//  들숨 때 mV 가 올라가는 센서면 false, 내려가는 센서면 true  [측정: 숨을 크게 들이쉬어 보고 정한다]
constexpr bool     BREATH_INVERT  = false;
//  봉우리·골짜기로 인정할 최소 흔들림 (mV). 정지 잡음보다 크고 평상 호흡 진폭보다 작게.  [측정]
//  (예: 정지 잡음 ±10 mV, 평상 호흡 진폭 150 mV → 40 정도)
constexpr uint16_t BREATH_HYST_MV = 40;
//  호흡수·들숨날숨비·변동성을 계산하는 창 (ms)
//   판단용 15 s : 빨리 반응해야 한다.   보고용 60 s : 보호자 앱의 "1 분" 정의.
constexpr uint32_t BREATH_DETECT_WINDOW_MS = 15000;
constexpr uint32_t BREATH_REPORT_WINDOW_MS = 60000;
//  가압·유지 중 호흡 꼭지점이 이만큼 없으면 비상 배기 (안전 규칙, 점수와 무관). 0 = 끔.
//  가압을 시작한 시점부터 센다 → 경로 2(호흡 안 보임)로 가압해도 최소 이만큼은 유지된다.
constexpr uint32_t BREATH_APNEA_MS = 20000;

// ---------------------------------------------------------------------------
//  2) 호흡 규칙 (1순위) — 점수를 "호흡 점수" 에 더한다
// ---------------------------------------------------------------------------
// [B] 빠른 호흡: 15 s 창 분당 호흡수 ≥ 문턱
//     기준표: 청소년·성인 25~30 회/분 이상 (아동은 35~40). 성인 안정 시 약 12~20 회/분.
constexpr uint16_t BREATH_RATE_HIGH = 28;    // 회/분 (성인)
constexpr uint8_t  W_BREATH_FAST    = 2;

// [I] 들숨 유지: 숨을 들이쉰 상태(들숨 단계)가 이만큼 끝나지 않음 — 공황 시 가슴 근육 경직
//     기준표: 3 s 이상. 성인 평상 들숨은 약 1~2 s.
constexpr uint32_t INHALE_HOLD_MS = 3000;
constexpr uint8_t  W_INHALE_HOLD  = 2;

// [X] 날숨 지속: 숨을 내쉬는 단계가 이만큼 끝나지 않음 — 소리 지르기·비명
//     기준표: 4 s 이상. ⚠ 아주 느린 평상 호흡(10 회/분 이하)에서도 날숨+쉼이 4 s 가까이 될 수 있다.
//     평소 호흡 실측에서 'ex=' 최대값을 보고, 평소에 걸리면 문턱을 올린다.
constexpr uint32_t EXHALE_HOLD_MS = 4000;
constexpr uint8_t  W_EXHALE_HOLD  = 2;

// [V] 호흡 간격 변동성 급증: 15 s 창 호흡 주기의 변동계수(표준편차/평균) ≥ 평소 × 배수
//     기준표: 평소 대비 2~3 배. 평소 값은 '# ctl' 의 cv= 를 평상 호흡 1 분 동안 보고 적는다.
constexpr uint16_t BREATH_CV_BASE_X100 = 20;   // [측정] 평소 변동계수 × 100 (0.20 → 20). 0 = 규칙 끔
constexpr uint16_t BREATH_CV_FACTOR_X10 = 20;  // 배수 × 10 (2.0 배 → 20)
constexpr uint8_t  W_BREATH_VAR = 1;

// [E] 들숨:날숨 비율 ≥ 문턱 (안정 시 약 0.5 = 1:2, 날숨이 짧아지면 커진다). 값 ×100.
constexpr uint16_t IE_HIGH_X100 = 100;   // 100 = 1.00
constexpr uint8_t  W_IE         = 1;

// ---------------------------------------------------------------------------
//  3) 악력 규칙 (보조) — 기준은 "평소 가방끈을 쥐는 힘의 3배"
// ---------------------------------------------------------------------------
//  ZD10-100 은 500 g 급 센서라 kgf 절대값(최대 악력 70%)은 잴 수 없다 → 평소 대비 배수로 정한다.
//  GRIP_BASE_MV: 조끼를 메고 평소처럼 가방끈을 잡았을 때의 이동평균 (gl=/gr= 값)  [측정]
constexpr uint16_t GRIP_BASE_MV         = 300;
constexpr uint8_t  GRIP_ON_FACTOR_X10   = 30;   // 평소의 3.0 배 이상 → "강한 쥠" 시작
constexpr uint8_t  GRIP_OFF_FACTOR_X10  = 20;   // 평소의 2.0 배 이하 → 해제 (사이 = 직전 상태 유지)
constexpr bool     GRIP_REQUIRE_BOTH    = false; // true = 양손 모두 쥐어야 인정

constexpr uint16_t GRIP_ON_MV  = static_cast<uint16_t>(GRIP_BASE_MV * GRIP_ON_FACTOR_X10 / 10);
constexpr uint16_t GRIP_OFF_MV = static_cast<uint16_t>(GRIP_BASE_MV * GRIP_OFF_FACTOR_X10 / 10);

// [G] 강한 쥠 지속: 넘어지며 순간 잡는 것을 빼기 위해 이만큼 계속 (기준표 2.5~3 s)
constexpr uint32_t GRIP_HOLD_MS = 2500;
constexpr uint8_t  W_GRIP       = 1;

// [P] 반복 쥐어짜기: 강한 쥠이 GRIP_PULSE_WINDOW_MS 안에 GRIP_PULSE_COUNT 번 이상 새로 시작 (기준표 10 s 에 3 회)
constexpr uint8_t  GRIP_PULSE_COUNT     = 3;
constexpr uint32_t GRIP_PULSE_WINDOW_MS = 10000;
constexpr uint8_t  W_GRIP_PULSE         = 1;

// ---------------------------------------------------------------------------
//  4) 심박 규칙 (보조) — 워치 심박이 fresh 일 때만, 위험 심박 절대값
// ---------------------------------------------------------------------------
// [H] 심박 ≥ 문턱 (기준표: 성인 멜트다운 시 100~140+ bpm)
constexpr uint8_t  HR_HIGH_BPM = 130;
constexpr uint8_t  W_HR        = 1;

// ---------------------------------------------------------------------------
//  5) 확정
// ---------------------------------------------------------------------------
// [경로 1]
constexpr uint8_t  SCORE_TRIGGER    = 3;     // 전체 점수 (호흡 + 악력 + 심박)
constexpr uint8_t  BREATH_MIN_SCORE = 2;     // 그중 호흡 점수가 최소 이만큼 (호흡 1순위)
// [경로 2] 호흡 안 보임 + 강한 악력 지속
constexpr bool     UNREC_ENABLED       = true;
constexpr uint32_t UNREC_BREATH_MS     = 10000;  // 완성된 호흡이 이만큼 없음 (기준표 10 s)
constexpr uint32_t UNREC_GRIP_HOLD_MS  = 10000;  // 강한 쥠이 이만큼 계속 (11/20 학생 결정: 10 s)
// 공통
constexpr uint32_t CONFIRM_MS = 3000;  // 조건이 이만큼 계속돼야 확정 (순간 튐 무시)

// ---------------------------------------------------------------------------
//  6) 유지 중 조기 종료 (진정됨) — 기본 꺼짐. 켜면 유지 시간 전이라도 배기
// ---------------------------------------------------------------------------
constexpr bool     EARLY_RELEASE_ENABLED = false;
constexpr uint8_t  CALM_SCORE            = 0;      // 점수가 이 값 이하인 상태가
constexpr uint32_t CALM_MS               = 60000;  //   이만큼 계속되면 진정으로 본다
constexpr uint32_t HOLD_MIN_MS           = 60000;  // 단, 유지가 최소 이만큼은 지난 뒤

// ---------------------------------------------------------------------------
//  컴파일 시점 검사 — 값을 잘못 고치면 업로드 전에 막는다
// ---------------------------------------------------------------------------
static_assert(GRIP_OFF_FACTOR_X10 <= GRIP_ON_FACTOR_X10, "해제 배수는 시작 배수 이하여야 한다");
static_assert(GRIP_BASE_MV == 0 || GRIP_ON_MV <= 3000,
              "평소 악력 × 배수가 3000 mV 를 넘는다 — ADC 상한(약 3100 mV)에 붙어 강한 쥠을 못 잰다. "
              "배수를 낮추거나 센서 위치를 바꿔라");
static_assert(BREATH_MIN_SCORE >= 1, "BREATH_MIN_SCORE 가 0 이면 호흡 이상 없이 악력·심박만으로 가압된다");
static_assert(SCORE_TRIGGER >= BREATH_MIN_SCORE, "SCORE_TRIGGER 는 BREATH_MIN_SCORE 이상");
static_assert(!UNREC_ENABLED || (UNREC_BREATH_MS >= 5000 && UNREC_GRIP_HOLD_MS >= GRIP_HOLD_MS),
              "경로 2 시간이 너무 짧다 (호흡 안 보임 ≥ 5 s, 악력 지속 ≥ GRIP_HOLD_MS)");
static_assert(BREATH_APNEA_MS == 0 || BREATH_APNEA_MS >= 10000,
              "무호흡 배기 시간이 10 s 보다 짧으면 평상 호흡의 쉼에도 배기될 수 있다");

}  // namespace det
