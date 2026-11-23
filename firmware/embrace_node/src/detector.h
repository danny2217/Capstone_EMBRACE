// detector.h — 센서 프레임 → 특징(호흡·악력·심박) → 점수 → 과부하 확정
//
//  판단 기준(문턱·가중치)은 모두 ../detect_config.h 에 있다. 이 파일은 계산만 한다.
#pragma once
#include <stdint.h>
#include "breath_analyzer.h"
#include "frame.h"
#include "hr_link.h"

// 어떤 규칙이 점수를 줬는지 (로그용 비트). '# ctl' 의 rules= 글자 순서와 같다: BIXVE GP H U
enum RuleBit : uint16_t {
  RULE_BREATH_FAST = 1 << 0,   // B 빠른 호흡          (호흡)
  RULE_INHALE_HOLD = 1 << 1,   // I 들숨 유지           (호흡)
  RULE_EXHALE_HOLD = 1 << 2,   // X 날숨 지속           (호흡)
  RULE_BREATH_VAR  = 1 << 3,   // V 호흡 간격 변동성    (호흡)
  RULE_IE_HIGH     = 1 << 4,   // E 들숨:날숨 비율      (호흡)
  RULE_GRIP_HOLD   = 1 << 5,   // G 강한 쥠 지속        (악력)
  RULE_GRIP_PULSE  = 1 << 6,   // P 반복 쥐어짜기       (악력)
  RULE_HR_HIGH     = 1 << 7,   // H 위험 심박           (심박)
  RULE_UNREC       = 1 << 8,   // U 경로 2: 호흡 안 보임 + 강한 쥠 10 s
};

// 어느 경로로 조건이 성립했나
enum class DetectPath : uint8_t { None = 0, Breath = 1, Unrec = 2 };

struct DetectResult {
  uint8_t  score;          // 전체 점수 (호흡 + 악력 + 심박)
  uint8_t  breathScore;    // 그중 호흡 규칙 점수 (경로 1 의 1순위 조건)
  uint16_t rules;          // RuleBit 조합
  DetectPath path;         // 지금 성립한 경로 (확정 전에도 표시)
  bool     breathUnrec;    // 호흡 인식 불가 (센서 정상 + 완성 호흡 UNREC_BREATH_MS 동안 없음)
  uint8_t  gripPulses;     // 최근 GRIP_PULSE_WINDOW_MS 안의 강한 쥠 시작 횟수
  bool     overload;       // 조건이 CONFIRM_MS 동안 유지됨 (확정)
  uint32_t overloadForMs;  // 조건이 유지된 시간 (확정 전에도 증가)
  uint32_t calmForMs;      // 점수 ≤ CALM_SCORE 가 유지된 시간
  BreathMetrics breath;     // 판단용 짧은 창 (BREATH_DETECT_WINDOW_MS)
  BreathMetrics breath60;   // 보고용 1 분 창 (BREATH_REPORT_WINDOW_MS) — 보호자 앱의 분당 호흡수·들숨날숨비
  bool     breathEnabled;  // 호흡 채널이 켜져 있나
  bool     breathHealthy;
  uint32_t gripHoldMs;     // 악력 "쥠" 이 계속된 시간
  uint16_t gripL, gripR;   // 이동평균 (mV), 채널 꺼짐이면 0
  uint16_t compMaxMv;      // 가슴·등 압박 중 큰 값 (이동평균, mV)
  bool     compFault;      // 압박 채널 단락/단선
  HrStatus hr;
};

// 채널 표에서 이름으로 인덱스를 찾아 둔다. channelsInit() 다음에 한 번.
void detectorInit();
// 프레임 하나 반영 (50 Hz)
void detectorPush(const Frame& f);
// 현재 결과 (now = millis)
DetectResult detectorResult(uint32_t nowMs);
// 가압이 끝나면 확정 타이머를 다시 0 부터 (같은 사건으로 곧바로 재가압하지 않게)
void detectorRearm();
