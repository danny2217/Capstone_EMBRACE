// moving_average.h — 원형 버퍼 + 누적합 이동 평균, 샘플당 O(1)
// (breath-sensor v1 의 sensor_app.cpp 에서 그대로 옮김)
#pragma once
#include <stdint.h>

template <uint8_t N>
class MovingAverage {
 public:
  uint32_t push(uint32_t x) {
    sum_ -= buf_[idx_];              // 가장 오래된 값 제거 (초기엔 0)
    buf_[idx_] = x;
    sum_ += x;
    idx_ = (idx_ + 1) % N;
    if (count_ < N) count_++;        // 창이 차기 전엔 실제 개수로 나눔 → 시작 과도응답 없음
    return (sum_ + count_ / 2) / count_;   // 반올림
  }
  void reset() { *this = MovingAverage<N>(); }

 private:
  uint32_t buf_[N] = {0};
  uint32_t sum_ = 0;
  uint8_t idx_ = 0;
  uint8_t count_ = 0;
};
