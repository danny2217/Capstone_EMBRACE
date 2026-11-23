// breath_analyzer.cpp — 설명은 breath_analyzer.h
#include "breath_analyzer.h"
#include <math.h>

// 한 번의 호흡으로 인정할 주기 범위 (ms). 밖이면 잡음/자세 변화로 보고 버린다.
static constexpr uint32_t MIN_CYCLE_MS = 1000;    // 60 회/분
static constexpr uint32_t MAX_CYCLE_MS = 20000;   //  3 회/분

BreathAnalyzer::BreathAnalyzer(uint16_t hystMv, uint32_t apneaMs, bool invert)
    : hyst_(hystMv), apneaMs_(apneaMs), invert_(invert) {}

void BreathAnalyzer::reset() {
  *this = BreathAnalyzer(hyst_, apneaMs_, invert_);
}

void BreathAnalyzer::push(uint32_t t, float x, bool healthy) {
  if (healthy && !healthy_) healthySince_ = t;   // 정상으로 돌아온 순간부터 '인식 불가' 시간을 센다
  healthy_ = healthy;
  if (!healthy) {          // 단선/단락 중 값은 의미 없음 → 검출 상태를 처음부터 다시
    phase_ = Phase::Unknown;
    started_ = false;
    inhalePending_ = false;
    haveTrough_ = havePeak_ = false;
    return;
  }
  if (invert_) x = -x;     // 이후로는 "들숨 = 올라감" 으로만 생각한다
  const float h = static_cast<float>(hyst_);

  if (!started_) {
    runMax_ = runMin_ = x;
    runMaxT_ = runMinT_ = t;
    started_ = true;
    return;
  }

  switch (phase_) {
    case Phase::Unknown:   // 첫 방향을 정할 때까지 최대·최소를 함께 추적
      if (x > runMax_) { runMax_ = x; runMaxT_ = t; }
      if (x < runMin_) { runMin_ = x; runMinT_ = t; }
      if (x >= runMin_ + h) {            // 골짜기에서 올라오는 중
        onTrough(runMinT_, runMin_);
        phase_ = Phase::Rising;
        runMax_ = x; runMaxT_ = t;
      } else if (x <= runMax_ - h) {     // 봉우리에서 내려가는 중
        onPeak(runMaxT_, runMax_);
        phase_ = Phase::Falling;
        runMin_ = x; runMinT_ = t;
      }
      break;

    case Phase::Rising:
      if (x > runMax_) { runMax_ = x; runMaxT_ = t; }
      else if (x <= runMax_ - h) {       // 봉우리 확정
        onPeak(runMaxT_, runMax_);
        phase_ = Phase::Falling;
        runMin_ = x; runMinT_ = t;
      }
      break;

    case Phase::Falling:
      if (x < runMin_) { runMin_ = x; runMinT_ = t; }
      else if (x >= runMin_ + h) {       // 골짜기 확정
        onTrough(runMinT_, runMin_);
        phase_ = Phase::Rising;
        runMax_ = x; runMaxT_ = t;
      }
      break;
  }
}

void BreathAnalyzer::onPeak(uint32_t t, float v) {
  everExtreme_ = true;
  lastExtremeT_ = t;
  if (haveTrough_) {                     // 골짜기 → 봉우리 = 들숨
    pendingInhaleMs_ = t - troughT_;
    inhalePending_ = true;
  }
  havePeak_ = true;
  peakT_ = t;
  peakV_ = v;
}

void BreathAnalyzer::onTrough(uint32_t t, float v) {
  everExtreme_ = true;
  lastExtremeT_ = t;
  if (havePeak_ && inhalePending_) {     // 봉우리 → 골짜기 = 날숨 → 호흡 한 번 완성
    const uint32_t exhaleMs = t - peakT_;
    const uint32_t cycleMs = pendingInhaleMs_ + exhaleMs;
    if (cycleMs >= MIN_CYCLE_MS && cycleMs <= MAX_CYCLE_MS) {
      const float lowV = (v < troughV_) ? v : troughV_;
      Cycle& c = ring_[head_];
      c.tEnd = t;
      c.inhaleMs = pendingInhaleMs_;
      c.exhaleMs = exhaleMs;
      const float amp = peakV_ - lowV;
      c.ampMv = static_cast<uint16_t>(amp > 0 ? (amp < 65535.f ? amp : 65535.f) : 0);
      head_ = (head_ + 1) % RING;
      if (count_ < RING) count_++;
      haveCycle_ = true;
      lastCycleT_ = t;
    }
    inhalePending_ = false;
  }
  haveTrough_ = true;
  troughT_ = t;
  troughV_ = v;
}

BreathMetrics BreathAnalyzer::metrics(uint32_t now, uint32_t windowMs) const {
  BreathMetrics m{};
  m.sinceLastMs = everExtreme_ ? (now - lastExtremeT_) : 0;
  m.apnea = apneaMs_ > 0 && everExtreme_ && healthy_ && m.sinceLastMs > apneaMs_;

  uint32_t sumIn = 0, sumEx = 0, sumAmp = 0;
  uint8_t n = 0;
  for (uint8_t i = 0; i < count_; i++) {
    const Cycle& c = ring_[(head_ + RING - 1 - i) % RING];   // 최신부터
    if (now - c.tEnd > windowMs) break;
    sumIn += c.inhaleMs;
    sumEx += c.exhaleMs;
    sumAmp += c.ampMv;
    n++;
  }
  m.cycles = n;
  if (n > 0 && sumEx > 0) {
    m.ratePerMin = 60000.0f * n / static_cast<float>(sumIn + sumEx);
    m.ieRatio = static_cast<float>(sumIn) / static_cast<float>(sumEx);
    m.ampMv = static_cast<uint16_t>(sumAmp / n);
  }
  m.valid = healthy_ && n >= 3;

  // 호흡 주기 변동계수 — 두 번째 훑기 (n ≤ 64, 50 Hz 에서 충분히 가볍다)
  if (n >= 3) {
    const float mean = static_cast<float>(sumIn + sumEx) / n;
    float var = 0;
    for (uint8_t i = 0; i < n; i++) {
      const Cycle& c = ring_[(head_ + RING - 1 - i) % RING];
      const float d = static_cast<float>(c.inhaleMs + c.exhaleMs) - mean;
      var += d * d;
    }
    m.cycleCv = mean > 0 ? sqrtf(var / n) / mean : 0;
  }

  // 지금 진행 중인 단계의 길이 (들숨 유지 / 날숨 지속 판정용)
  if (healthy_) {
    if (phase_ == Phase::Rising && haveTrough_) m.inhaleNowMs = now - troughT_;
    if (phase_ == Phase::Falling && havePeak_) m.exhaleNowMs = now - peakT_;
    // 인식 불가 시간: 센서가 정상이 된 뒤의 마지막 완성 호흡부터 (없으면 정상이 된 순간부터)
    uint32_t ref = healthySince_;
    if (haveCycle_ && static_cast<int32_t>(lastCycleT_ - healthySince_) > 0) ref = lastCycleT_;
    m.noCycleMs = now - ref;
  }
  return m;
}
