// channel.cpp — 채널 정의 표 + 상태 판정
#include "channel.h"

// ----------------------------------------------------------------------------
//  컴파일 시점 검사: 켜진 채널의 핀이 ESP32-S3 ADC1(GPIO1~10) 인지
// ----------------------------------------------------------------------------
constexpr bool isAdc1(uint8_t pin) { return pin >= 1 && pin <= 10; }
static_assert(!cfg::ENABLE_BREATH || isAdc1(cfg::PIN_BREATH), "PIN_BREATH 는 GPIO1~10(ADC1) 이어야 한다");
static_assert(!cfg::ENABLE_GRIP_L || isAdc1(cfg::PIN_GRIP_L), "PIN_GRIP_L 는 GPIO1~10(ADC1) 이어야 한다");
static_assert(!cfg::ENABLE_GRIP_R || isAdc1(cfg::PIN_GRIP_R), "PIN_GRIP_R 는 GPIO1~10(ADC1) 이어야 한다");
static_assert(!cfg::ENABLE_CHEST  || isAdc1(cfg::PIN_CHEST),  "PIN_CHEST 는 GPIO1~10(ADC1) 이어야 한다");
static_assert(!cfg::ENABLE_BACK   || isAdc1(cfg::PIN_BACK),   "PIN_BACK 는 GPIO1~10(ADC1) 이어야 한다");
static_assert(cfg::ENABLE_BREATH || cfg::ENABLE_GRIP_L || cfg::ENABLE_GRIP_R ||
                  cfg::ENABLE_CHEST || cfg::ENABLE_BACK,
              "ENABLE_* 중 하나 이상은 true 여야 한다");
static_assert(cfg::OVERSAMPLE >= 1, "OVERSAMPLE 은 1 이상");
static_assert(cfg::PERIOD_MS >= 10, "PERIOD_MS 가 너무 짧다 (전송 대역폭 초과)");

static Channel g_ch[MAX_CH];
static size_t g_n = 0;

const char* kindName(ChKind k) {
  switch (k) {
    case ChKind::RubberPullup: return "pullup";
    case ChKind::Fsr:          return "fsr";
    case ChKind::Xgzp:         return "xgzp6847a_33";
  }
  return "?";
}

const char* healthName(Health h) {
  switch (h) {
    case Health::Ok:    return "ok";
    case Health::Open:  return "open";
    case Health::Short: return "short";
  }
  return "?";
}

Health Channel::classify(uint32_t mv) const {
  if (lowCheck && mv <= lowMv) return lowState;
  if (mv >= highMv) return highState;
  return Health::Ok;
}

Health Channel::updateHealth(uint32_t mv) {
  const Health h = classify(mv);
  if (h == candidate) {
    if (hold < cfg::FAULT_HOLD) hold++;
  } else {
    candidate = h;
    hold = 1;
  }
  // 같은 상태가 FAULT_HOLD 샘플 이상 이어질 때만 전환을 인정 (순간 튐 무시)
  if (hold >= cfg::FAULT_HOLD) reported = candidate;
  return reported;
}

// ----------------------------------------------------------------------------
static void addPullup(const char* name, uint8_t pin) {
  Channel& c = g_ch[g_n++];
  c.name = name; c.pin = pin; c.kind = ChKind::RubberPullup; c.rRef = cfg::BREATH_R_PU_OHM;
  c.lowMv = cfg::PULLUP_SHORT_MV;  c.lowState = Health::Short; c.lowCheck = true;
  c.highMv = cfg::PULLUP_OPEN_MV;  c.highState = Health::Open;
}

static void addFsr(const char* name, uint8_t pin, uint32_t rRef, uint32_t rBias, bool comp) {
  Channel& c = g_ch[g_n++];
  c.name = name; c.pin = pin; c.kind = ChKind::Fsr;
  c.rRef = rRef; c.rBias = cfg::FSR_HAS_BIAS ? rBias : 0; c.compression = comp;
  c.lowMv = cfg::FSR_OPEN_MV;    c.lowState = Health::Open;   c.lowCheck = cfg::FSR_HAS_BIAS;
  c.highMv = cfg::FSR_SHORT_MV;  c.highState = Health::Short;
}

static void addXgzp(const char* name, uint8_t pin) {
  Channel& c = g_ch[g_n++];
  c.name = name; c.pin = pin; c.kind = ChKind::Xgzp; c.compression = true;
  c.lowMv = cfg::XGZP_OPEN_MV;    c.lowState = Health::Open;  c.lowCheck = true;
  c.highMv = cfg::XGZP_SHORT_MV;  c.highState = Health::Short;
}

static void addCompression(const char* name, uint8_t pin) {
  if (cfg::COMPRESSION_SOURCE == cfg::PressureSource::XGZP6847A_33) addXgzp(name, pin);
  else addFsr(name, pin, cfg::COMP_R_REF_OHM, cfg::COMP_R_BIAS_OHM, true);
}

size_t channelsInit() {
  g_n = 0;
  // 순서 = CSV 열 순서. 바꾸지 않는다.
  if (cfg::ENABLE_BREATH) addPullup("breath", cfg::PIN_BREATH);
  if (cfg::ENABLE_GRIP_L) addFsr("grip_l", cfg::PIN_GRIP_L, cfg::GRIP_R_REF_OHM, cfg::GRIP_R_BIAS_OHM, false);
  if (cfg::ENABLE_GRIP_R) addFsr("grip_r", cfg::PIN_GRIP_R, cfg::GRIP_R_REF_OHM, cfg::GRIP_R_BIAS_OHM, false);
  if (cfg::ENABLE_CHEST)  addCompression("chest", cfg::PIN_CHEST);
  if (cfg::ENABLE_BACK)   addCompression("back", cfg::PIN_BACK);
  return g_n;
}

Channel* channels() { return g_ch; }
size_t channelCount() { return g_n; }
