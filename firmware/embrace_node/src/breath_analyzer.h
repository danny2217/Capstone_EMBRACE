// breath_analyzer.h — 호흡 파형 → 호흡수 · 들숨:날숨 비율 · 진폭 · 무호흡
//
//  방법: 히스테리시스 봉우리/골짜기 검출 (샘플당 O(1), 버퍼 없음)
//    올라가는 중 최대값을 기억 → 최대값보다 HYST 만큼 내려가면 "봉우리" 확정 (들숨 끝)
//    내려가는 중 최소값을 기억 → 최소값보다 HYST 만큼 올라가면 "골짜기" 확정 (날숨 끝)
//    들숨 시간 = 골짜기→봉우리,  날숨 시간 = 봉우리→다음 골짜기,  한 번 = 들숨+날숨
//  확정은 HYST 만큼 지나간 뒤라 실제 꼭지점보다 늦지만, 기록하는 시각은 꼭지점 시각이다.
//
//  단순 문턱(평균선 교차)보다 나은 점: 기준선이 천천히 흘러가도(자세 변화, 고무 늘어짐)
//  "상대적인 오르내림" 만 보므로 영향을 덜 받는다.
#pragma once
#include <stdint.h>

struct BreathMetrics {
  bool     valid;        // 창 안에 완성된 호흡이 3번 이상 + 센서 정상
  uint8_t  cycles;       // 창 안의 완성된 호흡 수
  float    ratePerMin;   // 분당 호흡수 (평균 주기로 계산)
  float    ieRatio;      // 들숨 시간 합 / 날숨 시간 합 (창 안)
  uint16_t ampMv;        // 평균 진폭 (봉우리 - 골짜기)
  bool     apnea;        // 한 번이라도 호흡을 본 뒤, APNEA_MS 동안 꼭지점이 없음
  uint32_t sinceLastMs;  // 마지막 꼭지점 이후 시간
};

class BreathAnalyzer {
 public:
  BreathAnalyzer(uint16_t hystMv, uint32_t apneaMs, bool invert);
  void reset();
  // 50 Hz 마다 한 번. x = 호흡 채널 이동평균 (mV), healthy = 채널 상태 정상
  void push(uint32_t tMs, float x, bool healthy);
  // windowMs 창 안의 완성된 호흡으로 계산. 판단용(짧은 창)과 보고용(1 분 창)을 따로 부른다.
  BreathMetrics metrics(uint32_t nowMs, uint32_t windowMs) const;

 private:
  struct Cycle { uint32_t tEnd; uint32_t inhaleMs; uint32_t exhaleMs; uint16_t ampMv; };
  static constexpr uint8_t RING = 64;   // 1 분 창에 분당 64회까지 담는다

  enum class Phase : uint8_t { Unknown, Rising, Falling };

  void onPeak(uint32_t t, float v);
  void onTrough(uint32_t t, float v);

  uint16_t hyst_;
  uint32_t apneaMs_;
  bool invert_;

  Phase phase_ = Phase::Unknown;
  float runMax_ = 0, runMin_ = 0;
  uint32_t runMaxT_ = 0, runMinT_ = 0;
  bool started_ = false;

  bool haveTrough_ = false, havePeak_ = false;
  uint32_t troughT_ = 0, peakT_ = 0;
  float troughV_ = 0, peakV_ = 0;
  uint32_t pendingInhaleMs_ = 0;
  bool inhalePending_ = false;

  bool everExtreme_ = false;
  uint32_t lastExtremeT_ = 0;
  bool healthy_ = false;

  Cycle ring_[RING] = {};
  uint8_t head_ = 0, count_ = 0;
};
