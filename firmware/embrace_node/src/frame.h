// frame.h — 센싱 태스크 → 전송 태스크로 넘기는 한 샘플 묶음
#pragma once
#include <stdint.h>
#include "channel.h"

struct Frame {
  uint32_t seq;               // 0부터 증가. PC 가 유실을 센다
  uint32_t t_ms;              // 기기 시각 (millis)
  uint16_t mv[MAX_CH];        // 오버샘플 평균 전압 (mV), 채널 정의 순서
  uint16_t ma[MAX_CH];        // 이동 평균 (mV)
  uint8_t  health[MAX_CH];    // Health 값 (0 정상, 1 단선, 2 단락)
  uint32_t period_us;         // 직전 샘플과의 실제 간격 (주기 계측용, 첫 샘플은 0)
  uint32_t work_us;           // 이번 주기 ADC 작업 시간
};
