// adc_reader.cpp
//
// Arduino-ESP32 코어 3.x 의 analogReadMilliVolts() 를 쓴다.
//   - 코어 3.x 는 ESP-IDF 5.x 의 oneshot ADC 드라이버 + 보정(curve fitting)을 쓴다.
//   - 채널은 첫 읽기 때 한 번만 초기화되고, 그 뒤로는 재설정하지 않는다.
//
// 내부 풀업을 쓰지 않는 이유 (v1 과 달라진 점)
//   v1(WROOM-32)은 호흡 센서에 내부 풀업(칩마다 30~80 kΩ)을 썼다. 이 값이 칩마다 달라
//   동작점이 ADC 상한에 붙는 문제가 있었다(HANDOFF 5-2). v2 는 5채널 모두 외부 저항을
//   쓰고, 값은 config.h 에 적어 헤더로 남긴다. 그래서 여기서는 풀업/풀다운을 끈다.
#include "adc_reader.h"
#include "driver/gpio.h"

void adcInit(Channel* ch, size_t n) {
  analogReadResolution(12);
  for (size_t i = 0; i < n; i++) {
    const uint8_t pin = ch[i].pin;
    analogSetPinAttenuation(pin, ADC_11db);     // 약 0~3.1 V 측정 범위
    (void)analogReadMilliVolts(pin);           // 채널 attach (보정 스킴 생성)
    gpio_pullup_dis(static_cast<gpio_num_t>(pin));
    gpio_pulldown_dis(static_cast<gpio_num_t>(pin));
  }
}

uint32_t adcReadMv(uint8_t pin) {
  for (uint8_t i = 0; i < cfg::DISCARD; i++) (void)analogReadMilliVolts(pin);
  uint32_t sum = 0;
  for (uint8_t i = 0; i < cfg::OVERSAMPLE; i++) sum += analogReadMilliVolts(pin);
  return (sum + cfg::OVERSAMPLE / 2) / cfg::OVERSAMPLE;
}
