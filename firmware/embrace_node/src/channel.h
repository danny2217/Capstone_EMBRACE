// channel.h — 채널 정의 표와 채널별 상태(단선/단락) 판정
//
// 펌웨어는 "센서별 폴더"가 아니라 "회로 방식(kind)" 으로 채널을 구분한다.
// 악력과 압박은 같은 FSR 분압 회로라 처리 방식이 같고, 차이는 PC 쪽 해석(쥠 지수, 압력 환산)에 있다.
#pragma once
#include <Arduino.h>
#include "../config.h"
#include "moving_average.h"

constexpr size_t MAX_CH = 5;

enum class ChKind : uint8_t {
  RubberPullup,  // 3V3 ─R_pu─ 노드 ─센서─ GND   (호흡 고무 센서)
  Fsr,           // 3V3 ─FSR─ 노드 ─R_ref─ GND  (ZD10-100, 하중↑ → 전압↑)
  Xgzp,          // 압력센서 아날로그 출력 직결 (0.2~2.7 V)
};

enum class Health : uint8_t { Ok = 0, Open = 1, Short = 2 };

const char* kindName(ChKind k);
const char* healthName(Health h);

struct Channel {
  const char* name;   // CSV 열 이름 (breath, grip_l, grip_r, chest, back)
  uint8_t pin;
  ChKind kind;
  uint32_t rRef = 0;     // 분압 하단 저항 (FSR) 또는 풀업 (호흡), Ω. 0 = 모름
  uint32_t rBias = 0;    // FSR 병렬 바이어스 저항, Ω. 0 = 없음
  bool compression = false;

  // 전압이 lowMv 이하로 FAULT_HOLD 샘플 지속 → lowState, highMv 이상 → highState
  // lowCheck=false 면 낮은 쪽 판정을 하지 않는다 (바이어스 저항 없는 FSR)
  uint32_t lowMv, highMv;
  Health lowState, highState;
  bool lowCheck;

  MovingAverage<cfg::MA_WINDOW> ma;

  // 디바운스 상태 (센싱 태스크만 접근)
  Health reported = Health::Ok;
  Health candidate = Health::Ok;
  uint32_t hold = 0;

  Health classify(uint32_t mv) const;
  // 샘플 하나 반영, 현재 인정된 상태를 돌려준다
  Health updateHealth(uint32_t mv);
};

// config.h 의 ENABLE_* 순서대로 켜진 채널만 채운다. 반환값 = 채널 수
size_t channelsInit();
Channel* channels();
size_t channelCount();
