// detector.h — 센서 프레임 → 특징(호흡·악력·심박) → 점수 → 과부하 확정
//
//  판단 기준(문턱·가중치)은 모두 ../detect_config.h 에 있다. 이 파일은 계산만 한다.
#pragma once
#include <stdint.h>
#include "breath_analyzer.h"
#include "frame.h"
#include "hr_link.h"

// 어떤 규칙이 점수를 줬는지 (로그용 비트)
enum RuleBit : uint8_t {
  RULE_BREATH_FAST = 1 << 0,   // B
  RULE_IE_HIGH     = 1 << 1,   // E
  RULE_GRIP_HOLD   = 1 << 2,   // G
  RULE_HR_HIGH     = 1 << 3,   // H
};

struct DetectResult {
  uint8_t  score;          // 전체 점수
  uint8_t  nonHrScore;     // 심박을 뺀 점수
  uint8_t  rules;          // RuleBit 조합
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
