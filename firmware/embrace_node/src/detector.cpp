// detector.cpp — 설명은 detector.h, 기준값은 ../detect_config.h
//
//  이 모듈은 제어 태스크 하나에서만 불린다 → 내부 상태에 락이 필요 없다.
#include "detector.h"
#include <string.h>
#include "../detect_config.h"
#include "channel.h"

static int s_iBreath = -1, s_iGripL = -1, s_iGripR = -1, s_iChest = -1, s_iBack = -1;

static BreathAnalyzer s_breath(det::BREATH_HYST_MV, det::BREATH_APNEA_MS, det::BREATH_INVERT);

static bool s_gripOnL = false, s_gripOnR = false;
static bool s_gripActive = false;
static uint32_t s_gripSince = 0;

static uint16_t s_gripL = 0, s_gripR = 0, s_compMax = 0;
static bool s_compFault = false, s_breathHealthy = false;
static uint32_t s_lastT = 0;

static bool s_condActive = false;     // 점수 조건이 지금 성립하나
static uint32_t s_condSince = 0;
static bool s_calmActive = false;
static uint32_t s_calmSince = 0;

static int findChannel(const char* name) {
  const Channel* ch = channels();
  for (size_t i = 0; i < channelCount(); i++)
    if (strcmp(ch[i].name, name) == 0) return static_cast<int>(i);
  return -1;
}

void detectorInit() {
  s_iBreath = findChannel("breath");
  s_iGripL = findChannel("grip_l");
  s_iGripR = findChannel("grip_r");
  s_iChest = findChannel("chest");
  s_iBack = findChannel("back");
  s_breath.reset();
}

// 히스테리시스: ON 문턱 이상이면 켜짐, OFF 문턱 이하면 꺼짐, 사이면 유지
static bool gripState(bool prev, uint16_t mv) {
  if (mv >= det::GRIP_ON_MV) return true;
  if (mv <= det::GRIP_OFF_MV) return false;
  return prev;
}

void detectorPush(const Frame& f) {
  const uint32_t t = f.t_ms;
  s_lastT = t;

  // --- 호흡
  if (s_iBreath >= 0) {
    s_breathHealthy = f.health[s_iBreath] == static_cast<uint8_t>(Health::Ok);
    s_breath.push(t, static_cast<float>(f.ma[s_iBreath]), s_breathHealthy);
  }

  // --- 악력 (단선/단락이면 쥠으로 보지 않는다)
  s_gripL = (s_iGripL >= 0) ? f.ma[s_iGripL] : 0;
  s_gripR = (s_iGripR >= 0) ? f.ma[s_iGripR] : 0;
  const bool okL = s_iGripL >= 0 && f.health[s_iGripL] == static_cast<uint8_t>(Health::Ok);
  const bool okR = s_iGripR >= 0 && f.health[s_iGripR] == static_cast<uint8_t>(Health::Ok);
  s_gripOnL = okL && gripState(s_gripOnL, s_gripL);
  s_gripOnR = okR && gripState(s_gripOnR, s_gripR);
  const bool gripNow = det::GRIP_REQUIRE_BOTH ? (s_gripOnL && s_gripOnR) : (s_gripOnL || s_gripOnR);
  if (gripNow && !s_gripActive) s_gripSince = t;
  s_gripActive = gripNow;

  // --- 압박 (안전 규칙용)
  s_compMax = 0;
  s_compFault = false;
  const int compIdx[2] = {s_iChest, s_iBack};
  for (int i : compIdx) {
    if (i < 0) continue;
    if (f.ma[i] > s_compMax) s_compMax = f.ma[i];
    if (f.health[i] != static_cast<uint8_t>(Health::Ok)) s_compFault = true;
  }
}

DetectResult detectorResult(uint32_t now) {
  DetectResult r{};
  r.breath = s_breath.metrics(now, det::BREATH_DETECT_WINDOW_MS);
  r.breath60 = s_breath.metrics(now, det::BREATH_REPORT_WINDOW_MS);
  r.breathEnabled = s_iBreath >= 0;
  r.breathHealthy = s_breathHealthy;
  r.gripHoldMs = s_gripActive ? (now - s_gripSince) : 0;
  r.gripL = s_gripL;
  r.gripR = s_gripR;
  r.compMaxMv = s_compMax;
  r.compFault = s_compFault;
  r.hr = hrLinkGet();

  // ---- 규칙별 점수 (문턱 0 = 꺼짐)
  if (det::BREATH_RATE_HIGH > 0 && r.breath.valid && r.breath.ratePerMin >= det::BREATH_RATE_HIGH) {
    r.nonHrScore += det::W_BREATH_FAST;
    r.rules |= RULE_BREATH_FAST;
  }
  if (det::IE_HIGH_X100 > 0 && r.breath.valid && r.breath.ieRatio * 100.0f >= det::IE_HIGH_X100) {
    r.nonHrScore += det::W_IE;
    r.rules |= RULE_IE_HIGH;
  }
  if (det::GRIP_HOLD_MS > 0 && r.gripHoldMs >= det::GRIP_HOLD_MS) {
    r.nonHrScore += det::W_GRIP;
    r.rules |= RULE_GRIP_HOLD;
  }
  r.score = r.nonHrScore;
  // 심박은 fresh 일 때만. 끊기면(stale/rejoining) 0 점 → 호흡·악력만으로 판단
  if (det::HR_HIGH_BPM > 0 && r.hr.usable() && r.hr.bpm >= det::HR_HIGH_BPM) {
    r.score += det::W_HR;
    r.rules |= RULE_HR_HIGH;
  }

  // ---- 확정 (조건이 CONFIRM_MS 동안 끊기지 않고 유지)
  const bool cond = r.score >= det::SCORE_TRIGGER && r.nonHrScore >= det::MIN_NON_HR_SCORE;
  if (cond && !s_condActive) s_condSince = now;
  s_condActive = cond;
  r.overloadForMs = cond ? (now - s_condSince) : 0;
  r.overload = cond && r.overloadForMs >= det::CONFIRM_MS;

  const bool calm = r.score <= det::CALM_SCORE;
  if (calm && !s_calmActive) s_calmSince = now;
  s_calmActive = calm;
  r.calmForMs = calm ? (now - s_calmSince) : 0;
  return r;
}

void detectorRearm() {
  s_condActive = false;   // 다음 확정은 조건이 "새로" 시작된 시점부터 CONFIRM_MS
}
