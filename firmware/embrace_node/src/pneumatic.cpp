// pneumatic.cpp — L298N 로 펌프·밸브 구동
//
//  L298N 한 채널 = IN 두 개. (HIGH, LOW) → 출력 ON,  (LOW, LOW) → 출력 OFF
//    펌프 : IN1/IN2 → OUT1/OUT2
//    밸브 : IN3/IN4 → OUT3/OUT4   (밸브는 극성이 없어 방향은 상관없음)
//
//  밸브 "열기" 가 전기적으로 무엇인지는 밸브 종류에 따라 다르다.
//    NC(상시 닫힘) : 통전 = 열림(배기),  무통전 = 닫힘(유지)   ← 현재 (구입 계획서)
//    NO(상시 열림) : 통전 = 닫힘(유지),  무통전 = 열림(배기)
//  위쪽 코드는 "열어라/닫아라" 만 말하고, 전기 신호 변환은 여기서만 한다.
#include "pneumatic.h"
#include "../config.h"

// ---- 핀 검사 (컴파일 시점) --------------------------------------------------
constexpr bool pinFree(uint8_t p) {
  // 부팅(0,3,45,46) · USB(19,20) · 플래시/PSRAM(26~37) · UART0(43,44) 금지
  return !(p == 0 || p == 3 || p == 45 || p == 46 || p == 19 || p == 20 ||
           (p >= 26 && p <= 37) || p == 43 || p == 44) && p <= 48;
}
constexpr bool notSensorPin(uint8_t p) {
  return p != cfg::PIN_BREATH && p != cfg::PIN_GRIP_L && p != cfg::PIN_GRIP_R &&
         p != cfg::PIN_CHEST && p != cfg::PIN_BACK &&
         (cfg::BREATH_SOURCE_PIN == 0 || p != cfg::BREATH_SOURCE_PIN);
}
static_assert(pinFree(cfg::PIN_PUMP_IN1) && pinFree(cfg::PIN_PUMP_IN2) &&
              pinFree(cfg::PIN_VALVE_IN3) && pinFree(cfg::PIN_VALVE_IN4),
              "펌프/밸브 핀이 부팅·USB·플래시 핀과 겹친다");
static_assert(notSensorPin(cfg::PIN_PUMP_IN1) && notSensorPin(cfg::PIN_PUMP_IN2) &&
              notSensorPin(cfg::PIN_VALVE_IN3) && notSensorPin(cfg::PIN_VALVE_IN4),
              "펌프/밸브 핀이 센서 핀(또는 BREATH_SOURCE_PIN=15)과 겹친다. "
              "호흡을 GPIO15 약출력으로 쓰려면 펌프 핀을 옮겨야 한다");
static_assert(cfg::INFLATE_MS <= cfg::INFLATE_MAX_MS, "INFLATE_MS 가 절대 상한 INFLATE_MAX_MS 를 넘는다");
static_assert(cfg::HOLD_MS <= cfg::HOLD_MAX_MS, "HOLD_MS 가 절대 상한 HOLD_MAX_MS 를 넘는다");
static_assert(cfg::DEFLATE_MS >= 3000 && cfg::EMERGENCY_VENT_MS >= cfg::DEFLATE_MS,
              "배기 시간이 너무 짧다");

static bool s_pump = false;
static bool s_valveOpen = false;

static void writePins(bool pumpPower, bool valvePower) {
  digitalWrite(cfg::PIN_PUMP_IN1, pumpPower ? HIGH : LOW);
  digitalWrite(cfg::PIN_PUMP_IN2, LOW);
  digitalWrite(cfg::PIN_VALVE_IN3, valvePower ? HIGH : LOW);
  digitalWrite(cfg::PIN_VALVE_IN4, LOW);
}

void pneumaticSafeInit() {
  // 출력으로 바꾸기 전에 래치를 LOW 로 → 바꾸는 순간에도 HIGH 가 나가지 않게
  const uint8_t pins[] = {cfg::PIN_PUMP_IN1, cfg::PIN_PUMP_IN2, cfg::PIN_VALVE_IN3, cfg::PIN_VALVE_IN4};
  for (uint8_t p : pins) digitalWrite(p, LOW);
  for (uint8_t p : pins) pinMode(p, OUTPUT);
  writePins(false, false);
  s_pump = false;
  s_valveOpen = false;
}

void pneumaticApply(bool pumpOn, bool valveOpen) {
  if (pumpOn && valveOpen) pumpOn = false;   // 열린 밸브로 펌핑하지 않는다
  s_pump = pumpOn;
  s_valveOpen = valveOpen;

  if (!cfg::PNEUMATIC_ARMED) {               // 드라이런: 핀은 늘 OFF
    writePins(false, false);
    return;
  }
  const bool valvePower = cfg::VALVE_NORMALLY_CLOSED ? valveOpen : !valveOpen;
  writePins(pumpOn, valvePower);
}

bool pneumaticPumpOn() { return s_pump; }
bool pneumaticValveOpen() { return s_valveOpen; }
