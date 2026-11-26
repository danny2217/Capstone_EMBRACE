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

// 호흡 소스 핀 검사: 부팅·USB·플래시/PSRAM·UART0 핀과 켜진 ADC 핀은 쓸 수 없다
constexpr uint8_t SRC = cfg::BREATH_SOURCE_PIN;
static_assert(SRC == 0 || (SRC <= 48 && SRC != 3 && SRC != 19 && SRC != 20 && SRC != 43 && SRC != 44 &&
                           SRC != 45 && SRC != 46 && !(SRC >= 22 && SRC <= 37)),
              "BREATH_SOURCE_PIN 은 0(사용 안 함) 또는 9~18, 21, 38~42, 47, 48 같은 빈 GPIO 여야 한다");
static_assert(SRC == 0 || ((!cfg::ENABLE_BREATH || SRC != cfg::PIN_BREATH) &&
                           (!cfg::ENABLE_GRIP_L || SRC != cfg::PIN_GRIP_L) &&
                           (!cfg::ENABLE_GRIP_R || SRC != cfg::PIN_GRIP_R) &&
                           (!cfg::ENABLE_CHEST || SRC != cfg::PIN_CHEST) &&
                           (!cfg::ENABLE_BACK || SRC != cfg::PIN_BACK)),
              "BREATH_SOURCE_PIN 이 센서 핀과 겹친다");
static_assert(cfg::PERIOD_MS >= 10, "PERIOD_MS 가 너무 짧다 (전송 대역폭 초과)");
static_assert(cfg::NOISY_WINDOW >= 1 && cfg::NOISY_WINDOW <= 32, "NOISY_WINDOW 는 1~32");
static_assert(cfg::NOISY_MIN_COUNT >= 1 && cfg::NOISY_MIN_COUNT <= cfg::NOISY_WINDOW,
              "NOISY_MIN_COUNT 는 1~NOISY_WINDOW");
static_assert(cfg::OVERSAMPLE >= 4, "떠 있음 검출은 버스트 폭을 쓰므로 OVERSAMPLE 4 이상");

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
    case Health::Noisy: return "noisy";
  }
  return "?";
}

Health Channel::classify(uint32_t mv) const {
  if (lowCheck && mv <= lowMv) return lowState;
  if (mv >= highMv) return highState;
  return Health::Ok;
}

Health Channel::updateHealth(uint32_t mv, uint16_t spreadMv) {
  Health h = classify(mv);
  if (noisyCheck) {
    constexpr uint32_t mask = (cfg::NOISY_WINDOW >= 32) ? 0xFFFFFFFFu : ((1u << cfg::NOISY_WINDOW) - 1u);
    noisyBits = ((noisyBits << 1) | (spreadMv > cfg::NOISY_SPREAD_MV ? 1u : 0u)) & mask;
    // 레일 고정(단선·단락)이 우선. 레일이 아닌데 흔들림이 크면 '떠 있음'
    if (h == Health::Ok && __builtin_popcount(noisyBits) >= cfg::NOISY_MIN_COUNT) h = Health::Noisy;
  }
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
  c.noisyCheck = true;   // 외부 풀업 + 긴 선: 신호선이 빠지면 핀이 뜬다 (11/26)
}

static void addFsr(const char* name, uint8_t pin, uint32_t rRef, uint32_t rBias, bool comp) {
  Channel& c = g_ch[g_n++];
  c.name = name; c.pin = pin; c.kind = ChKind::Fsr;
  c.rRef = rRef; c.rBias = cfg::FSR_HAS_BIAS ? rBias : 0; c.compression = comp;
  c.lowMv = cfg::FSR_OPEN_MV;    c.lowState = Health::Open;   c.lowCheck = cfg::FSR_HAS_BIAS;
  c.highMv = cfg::FSR_SHORT_MV;  c.highState = Health::Short;
  // 악력은 세게 쥐면 내부 풀다운에서 ADC 상한(약 3159 mV)에 붙는다 → '단락' 으로 보면 강한 쥠이 0 이 된다.
  //  (11/26 착용 튜닝: 4 초 꽉 쥐어도 G 가 안 걸린 원인). 악력 채널만 상한 고장 판정을 끈다.
  //  가슴·등(압박)은 그대로 둔다 → 꽉 눌리면 '단락' → 안전 쪽(비상 배기)으로 간다.
  if (!comp) c.highMv = 0xFFFFFFFFu;
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
