// adc_reader.cpp
//
// Arduino-ESP32 코어 3.x 의 analogReadMilliVolts() 를 쓴다.
//   - 코어 3.x 는 ESP-IDF 5.x 의 oneshot ADC 드라이버 + 보정(curve fitting)을 쓴다.
//   - 채널은 첫 읽기 때 한 번만 초기화되고, 그 뒤로는 재설정하지 않는다.
//
// 풀업 정책
//   - FSR 채널과 외부 풀업을 단 호흡 채널: 내부 풀업/풀다운을 끈다 (외부 저항만 사용).
//   - 호흡 채널이면서 config.h 의 BREATH_R_PU_OHM == 0 (외부 저항 없음):
//     v1(WROOM-32)과 같은 방식으로 "칩 내부 풀업(약 45 kΩ, 칩마다 30~80 kΩ)" 을 켠다.
//     → 센서 두 선만 GPIO4 와 GND 에 꽂으면 바로 시험할 수 있다 (빠른 시험용).
//     → 센서 저항이 수백 kΩ 이상이면 ADC 상한(약 3.1 V)에 붙어 반응이 없을 수 있다.
//       그때는 외부 풀업 R_pu 를 달고 BREATH_R_PU_OHM 에 그 값을 적는다.
//   - 호흡 채널이면서 BREATH_R_PU_OHM == 0 이고 BREATH_SOURCE_PIN != 0:
//     그 GPIO 를 가장 약한 세기(GPIO_DRIVE_CAP_0)의 HIGH 출력으로 켜서 풀업 저항 대신 쓴다.
//     출력 트랜지스터의 온저항이 약 100~300 Ω 의 풀업처럼 동작한다 (시험대용, config.h 주석 참고).
//   코어 3.x 는 첫 analogRead 때 채널을 초기화하면서 풀업을 끄므로, 반드시 그 "다음에" 켠다.
#include "adc_reader.h"
#include "driver/gpio.h"

bool breathUsesSourcePin() {
  return cfg::ENABLE_BREATH && cfg::BREATH_R_PU_OHM == 0 && cfg::BREATH_SOURCE_PIN != 0;
}

static bool usesInternalPullup(const Channel& c) {
  return c.kind == ChKind::RubberPullup && c.rRef == 0 && !breathUsesSourcePin();
}

// FSR 채널이면서 R_ref = 0 → 칩 내부 풀다운을 R_ref 로 쓴다 (외부 저항 없음)
static bool usesInternalPulldown(const Channel& c) {
  return c.kind == ChKind::Fsr && c.rRef == 0;
}

static void breathSourceInit() {
  if (!breathUsesSourcePin()) return;
  const gpio_num_t src = static_cast<gpio_num_t>(cfg::BREATH_SOURCE_PIN);
  gpio_reset_pin(src);
  gpio_set_direction(src, GPIO_MODE_OUTPUT);
  gpio_set_drive_capability(src, GPIO_DRIVE_CAP_0);   // 가장 약한 세기 → 전류 제한 효과
  gpio_set_level(src, 1);
}

void adcInit(Channel* ch, size_t n) {
  breathSourceInit();
  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);                  // 모든 채널 기본 감쇠 (약 0~3.1 V). attach 전에 설정
  for (size_t i = 0; i < n; i++) {
    const gpio_num_t pin = static_cast<gpio_num_t>(ch[i].pin);
    (void)analogReadMilliVolts(ch[i].pin);         // 채널 attach (이때 풀업이 꺼진다)
    // attach 다음에 켜야 유지된다
    if (usesInternalPulldown(ch[i])) gpio_pulldown_en(pin);
    else gpio_pulldown_dis(pin);
    if (usesInternalPullup(ch[i])) gpio_pullup_en(pin);
    else gpio_pullup_dis(pin);
  }
}

uint32_t adcReadMv(uint8_t pin) {
  for (uint8_t i = 0; i < cfg::DISCARD; i++) (void)analogReadMilliVolts(pin);
  uint32_t sum = 0;
  for (uint8_t i = 0; i < cfg::OVERSAMPLE; i++) sum += analogReadMilliVolts(pin);
  return (sum + cfg::OVERSAMPLE / 2) / cfg::OVERSAMPLE;
}
