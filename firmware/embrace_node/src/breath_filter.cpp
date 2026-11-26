#include "breath_filter.h"

#include <math.h>

// 쌍일차 변환 Butterworth: k = tan(pi*fc/fs), norm = 1/(1+sqrt2*k+k^2)  (원본과 동일)
static void butterCommon(float fc, float fs, float* k, float* norm) {
  const float kk = static_cast<float>(tan(M_PI * fc / fs));
  *k = kk;
  *norm = 1.0f / (1.0f + static_cast<float>(M_SQRT2) * kk + kk * kk);
}

void Biquad::lowpass(float fc, float fs) {
  float k, n;
  butterCommon(fc, fs, &k, &n);
  b0_ = k * k * n;
  b1_ = 2.0f * b0_;
  b2_ = b0_;
  a1_ = 2.0f * (k * k - 1.0f) * n;
  a2_ = (1.0f - static_cast<float>(M_SQRT2) * k + k * k) * n;
  init_ = false;
}

void Biquad::highpass(float fc, float fs) {
  float k, n;
  butterCommon(fc, fs, &k, &n);
  b0_ = n;
  b1_ = -2.0f * n;
  b2_ = n;
  a1_ = 2.0f * (k * k - 1.0f) * n;
  a2_ = (1.0f - static_cast<float>(M_SQRT2) * k + k * k) * n;
  init_ = false;
}

void Biquad::initSteady(float x) {
  const float gain = (b0_ + b1_ + b2_) / (1.0f + a1_ + a2_);
  const float y = gain * x;
  s2_ = b2_ * x - a2_ * y;
  s1_ = b1_ * x - a1_ * y + s2_;
  init_ = true;
}

float Biquad::update(float x) {   // Direct Form II transposed
  if (!init_) initSteady(x);
  const float y = b0_ * x + s1_;
  s1_ = b1_ * x - a1_ * y + s2_;
  s2_ = b2_ * x - a2_ * y;
  return y;
}

void BreathBandpass::begin(float fs, float hpHz, float lpHz) {
  hp_.highpass(hpHz, fs);
  lp_.lowpass(lpHz, fs);
  reset();
}

void BreathBandpass::reset() {
  hp_.reset();
  lp_.reset();
  haveOffset_ = false;
}

float BreathBandpass::update(float mv) {
  if (!haveOffset_) {
    offset_ = mv;
    haveOffset_ = true;
  }
  return lp_.update(hp_.update(mv - offset_));
}
