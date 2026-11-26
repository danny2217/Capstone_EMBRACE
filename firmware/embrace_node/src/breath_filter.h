// breath_filter.h — 호흡 대역통과 필터 (2차 Butterworth 고역통과 → 저역통과)
//
//  Air-Rhythm(원본 호흡 벨트 프로젝트) firmware/breath_monitor/breath_filter.* 를 옮겨 왔다.
//  계수·초기화 방식은 원본과 같다 → 원본 그래프처럼 기준선 흔들림이 빠진 매끄러운 곡선이 된다.
//    고역 HP_HZ : 고무줄이 늘어났다 천천히 돌아오는 느린 이동(드리프트)을 지운다
//    저역 LP_HZ : 숨보다 빠른 잡음(전원 잡음 등)을 지운다
//  출력은 0 을 중심으로 오르내리는 mV 값 (들숨 + / 날숨 −, BREATH_INVERT 적용 전).
#pragma once

class Biquad {
 public:
  void lowpass(float cutoffHz, float fsHz);
  void highpass(float cutoffHz, float fsHz);
  float update(float x);
  void reset() { init_ = false; }

 private:
  void initSteady(float x);   // x 가 계속 들어왔던 것처럼 상태를 맞춰 시작 튐을 없앤다
  float b0_ = 0, b1_ = 0, b2_ = 0, a1_ = 0, a2_ = 0;
  float s1_ = 0, s2_ = 0;
  bool init_ = false;
};

class BreathBandpass {
 public:
  void begin(float fsHz, float hpHz, float lpHz);
  float update(float mv);
  // 단선·단락 뒤 다시 정상이 되면 처음부터 (첫 샘플을 새 기준점으로)
  void reset();

 private:
  Biquad hp_, lp_;
  float offset_ = 0;   // 첫 샘플을 빼서 float 오차를 줄인다 (원본 주석: 오차 33배 감소)
  bool haveOffset_ = false;
};
