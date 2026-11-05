// sensor_app.cpp — sensor_reader 의 실제 구현부 (설명은 sensor_reader.ino 상단 참고)
//
// 왜 .ino 가 아니라 .cpp 인가:
//   Arduino IDE 는 .ino 를 컴파일하기 전에 모든 함수의 선언(prototype)을 자동으로 만들어
//   "첫 번째 함수 정의 바로 앞"에 끼워 넣는다. 그런데 그 위치가 enum SensorHealth 정의보다
//   앞이라서 'SensorHealth' does not name a type 오류가 났다.
//   .cpp 파일은 이 자동 처리를 거치지 않고 표준 C++ 그대로 컴파일되므로 이런 문제가 없다.

#include "sensor_app.h"

#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// 보드 선택 검사: 이 코드는 ESP32(WROOM-32) 의 핀·레지스터 구조를 전제로 한다.
// Tools → Board 가 ESP32-S3 / C3 / C5 등으로 되어 있으면 여기서 컴파일을 멈춘다.
#if !CONFIG_IDF_TARGET_ESP32
#error "보드 선택 오류: Tools > Board 에서 [ESP32 Dev Module] 을 선택하세요 (ESP32-WROOM-32 용 코드)"
#endif

// ----------------------------------------------------------------------------
//  설정
// ----------------------------------------------------------------------------
namespace cfg {
constexpr uint8_t  SENSOR_PIN   = 32;     // ADC1_CH4, 내부 풀업 가능 (33 도 가능)
constexpr uint32_t BAUD         = 115200;
constexpr uint32_t PERIOD_MS    = 20;     // 50 Hz. 호흡(0.1~0.7 Hz) 대비 충분한 여유
constexpr uint8_t  OVERSAMPLE   = 8;      // 1회 측정당 ADC 변환 횟수 (≈0.5 ms)
constexpr uint8_t  MA_WINDOW    = 10;     // 이동 평균 길이 = 0.2 s, 군지연 ≈ 90 ms

// 센서 상태 판정 문턱 (풀업 회로이므로 단선 → 레일 근처, 단락 → 0 근처)
constexpr uint32_t OPEN_MV      = 3000;
constexpr uint32_t SHORT_MV     = 60;
constexpr uint32_t FAULT_HOLD   = 25;     // 연속 0.5 s 이상일 때만 경고 (순간 튐 무시)

// 풀업 선택: true = 내부 풀업(약 45kΩ, 외부 부품 없음)
//            false = 외부 풀업 저항 사용 (3V3 ─[R]─ D32). 센서 저항이 수백 kΩ 이상이라
//                    내부 풀업으로는 ADC 상한(약 3.1V)에 붙어버릴 때 사용한다.
constexpr bool     USE_INTERNAL_PULLUP = true;

constexpr bool     REPORT_RATE  = true;   // 1초마다 '# fs=...' 진단 줄 출력

// 태스크 배치: 코어 1(Arduino 기본 코어), Arduino loop 보다 높은 우선순위
constexpr BaseType_t SENSE_CORE = 1;
constexpr UBaseType_t SENSE_PRIO = 3;
constexpr uint32_t SENSE_STACK  = 4096;
}  // namespace cfg

static_assert(cfg::SENSOR_PIN == 32 || cfg::SENSOR_PIN == 33,
              "WROOM-32 에서 ADC1 + 내부 풀업이 모두 가능한 핀은 GPIO32/33 뿐");

// ----------------------------------------------------------------------------
//  이동 평균 — 원형 버퍼 + 누적합, 샘플당 O(1)
// ----------------------------------------------------------------------------
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

 private:
  uint32_t buf_[N] = {0};
  uint32_t sum_ = 0;
  uint8_t idx_ = 0;
  uint8_t count_ = 0;
};

// ----------------------------------------------------------------------------
//  ADC + 내부 풀업 — Arduino-ESP32 코어 버전별로 경로가 다르다 (실제 소스 확인 결과)
// ----------------------------------------------------------------------------
//  [코어 2.x]  analogRead() 가 "호출될 때마다" __adcAttachPin() → pinMode(pin, ANALOG)
//              를 실행하고, 이때 풀업이 꺼진다. 즉 pinMode(INPUT_PULLUP) 을 하든,
//              읽은 뒤 풀업을 켜든 다음 analogRead() 에서 다시 꺼진다.
//              → analogRead 를 아예 쓰지 않고 ESP-IDF ADC 드라이버를 직접 사용.
//  [코어 3.x]  analogRead 는 최초 1회만 채널을 초기화(이때 풀업 해제)하고
//              이후엔 재설정하지 않는다. → 첫 읽기 후 풀업을 켜면 유지된다.
//              단, 이후 analogSetAttenuation() 등을 다시 부르면 또 꺼지니 주의.
//
//  어느 경로든 부팅 시 RTC 패드 레지스터의 풀업 비트를 "직접 읽어" 검증한다.
//  가정에 기대지 말고 레지스터로 확인하라 — 이것이 TC-01 의 근거 자료가 된다.

#include "soc/rtc_io_reg.h"   // ESP32 RTC 패드 레지스터 (IDF 4.4 / 5.x 동일)

#if ESP_ARDUINO_VERSION_MAJOR >= 3
// ---------------- 코어 3.x ----------------
static void adcInit() {
  analogReadResolution(12);
  analogSetAttenuation(ADC_11db);                         // 약 150~3100 mV (보정 스킴도 이 값 사용)
  (void)analogReadMilliVolts(cfg::SENSOR_PIN);            // 채널 attach (이 시점에 풀업 해제됨)
}
static inline uint32_t adcReadMv() { return analogReadMilliVolts(cfg::SENSOR_PIN); }

#else
// ---------------- 코어 2.x (ESP-IDF 4.4 legacy ADC 드라이버) ----------------
#include "driver/adc.h"
#include "esp_adc_cal.h"

static constexpr adc1_channel_t ADC_CH =
    (cfg::SENSOR_PIN == 32) ? ADC1_CHANNEL_4 : ADC1_CHANNEL_5;
static esp_adc_cal_characteristics_t s_adcChars;

static void adcInit() {
  adc1_config_width(ADC_WIDTH_BIT_12);
  adc1_config_channel_atten(ADC_CH, ADC_ATTEN_DB_11);     // 패드를 아날로그로 전환 (풀업 해제됨)
  esp_adc_cal_characterize(ADC_UNIT_1, ADC_ATTEN_DB_11, ADC_WIDTH_BIT_12,
                           1100, &s_adcChars);           // eFuse 보정값 (없으면 1100 mV 기준)
}
static inline uint32_t adcReadMv() {
  return esp_adc_cal_raw_to_voltage(adc1_get_raw(ADC_CH), &s_adcChars);
}
#endif

// RTC 패드 레지스터에서 풀업 enable 비트를 직접 읽는다.
// ESP32 에서 GPIO32/33 은 32 kHz 크리스털 패드(XTAL_32K_P/N)를 겸하며,
// 풀업 비트는 RTC_IO_XTAL_32K_PAD_REG 의 X32P_RUE(GPIO32) / X32N_RUE(GPIO33).
// (공용 테이블 rtc_io_desc[] 는 칩/코어 버전에 따라 링크되지 않을 수 있어 레지스터를 직접 읽는다)
static bool pullupIsEnabled() {
  const uint32_t mask = (cfg::SENSOR_PIN == 32) ? RTC_IO_X32P_RUE : RTC_IO_X32N_RUE;
  return (REG_READ(RTC_IO_XTAL_32K_PAD_REG) & mask) != 0;
}

static void setupSensorPin() {
  adcInit();                                               // ① ADC 채널 설정 (풀업이 꺼짐)
  gpio_pulldown_dis(static_cast<gpio_num_t>(cfg::SENSOR_PIN));
  if (cfg::USE_INTERNAL_PULLUP) {
    gpio_pullup_en(static_cast<gpio_num_t>(cfg::SENSOR_PIN));   // ② 그 "다음에" 풀업 ON
  } else {
    gpio_pullup_dis(static_cast<gpio_num_t>(cfg::SENSOR_PIN));  // 외부 저항만 사용
  }
}

// 오버샘플링: 같은 시점에 N회 변환해 평균 → ADC 양자화/열잡음 감소
static uint32_t readMilliVolts() {
  uint32_t sum = 0;
  for (uint8_t i = 0; i < cfg::OVERSAMPLE; i++) sum += adcReadMv();
  return (sum + cfg::OVERSAMPLE / 2) / cfg::OVERSAMPLE;
}

// ----------------------------------------------------------------------------
//  센서 상태 감시 — 단선/단락을 데이터와 분리된 경고 줄로 알린다
// ----------------------------------------------------------------------------
//  압박 조끼 본 시스템에서는 이 판정이 "센서 신뢰 불가 → 자동 가압 금지"
//  로 이어져야 한다. 지금 단계에선 경고만 출력한다.
enum class SensorHealth : uint8_t { OK, OPEN, SHORT };

static SensorHealth classify(uint32_t mv) {
  if (mv >= cfg::OPEN_MV)  return SensorHealth::OPEN;
  if (mv <= cfg::SHORT_MV) return SensorHealth::SHORT;
  return SensorHealth::OK;
}

static void checkHealth(uint32_t mv) {
  static SensorHealth reported = SensorHealth::OK;   // 마지막으로 알린 상태
  static SensorHealth candidate = SensorHealth::OK;
  static uint32_t hold = 0;

  SensorHealth h = classify(mv);
  if (h == candidate) {
    if (hold < cfg::FAULT_HOLD) hold++;
  } else {
    candidate = h;
    hold = 1;
  }
  // 같은 상태가 FAULT_HOLD 샘플 이상 유지될 때만 상태 전환을 인정 (디바운스)
  if (hold >= cfg::FAULT_HOLD && candidate != reported) {
    reported = candidate;
    switch (reported) {
      case SensorHealth::OPEN:
        Serial.printf("# WARN sensor_open mv=%lu (센서 분리/단선 의심)\n", (unsigned long)mv);
        break;
      case SensorHealth::SHORT:
        Serial.printf("# WARN sensor_short mv=%lu (단락 의심)\n", (unsigned long)mv);
        break;
      case SensorHealth::OK:
        Serial.printf("# INFO sensor_ok mv=%lu\n", (unsigned long)mv);
        break;
    }
  }
}

// ----------------------------------------------------------------------------
//  표본화 주기 자체 계측 — 1초마다 실측 fs 보고
// ----------------------------------------------------------------------------
static void reportRate() {
  static uint32_t prev = 0, n = 0, sum = 0, pMin = UINT32_MAX, pMax = 0;
  const uint32_t now = micros();
  if (prev) {
    const uint32_t p = now - prev;   // unsigned 뺄셈 → micros() 오버플로(71분)에도 안전
    sum += p; n++;
    if (p < pMin) pMin = p;
    if (p > pMax) pMax = p;
    if (n >= 1000 / cfg::PERIOD_MS) {
      Serial.printf("# fs=%.2fHz avg=%luus min=%luus max=%luus\n",
                    1e6f * n / sum, (unsigned long)(sum / n),
                    (unsigned long)pMin, (unsigned long)pMax);
      n = 0; sum = 0; pMin = UINT32_MAX; pMax = 0;
    }
  }
  prev = now;
}

// ----------------------------------------------------------------------------
//  센싱 태스크 — delay() 없이 절대 시각 기준 주기 실행
// ----------------------------------------------------------------------------
//  delay(20) 은 "작업 후 20ms 더 쉬기" 라 실제 주기가 (작업시간+20ms) 로 늘어난다.
//  vTaskDelayUntil 은 다음 기상 시각을 절대값으로 누적하므로 작업 시간이
//  주기에 포함되어 정확히 PERIOD_MS 간격이 유지된다.
static MovingAverage<cfg::MA_WINDOW> g_ma;

static void senseTask(void*) {
  uint32_t seq = 0;
  TickType_t nextWake = xTaskGetTickCount();

  for (;;) {
    const uint32_t tMs = millis();
    const uint32_t mv = readMilliVolts();
    const uint32_t ma = g_ma.push(mv);

    checkHealth(mv);
    Serial.printf("%lu\t%lu\t%lu\t%lu\n",
                  (unsigned long)seq++, (unsigned long)tMs,
                  (unsigned long)mv, (unsigned long)ma);
    if (cfg::REPORT_RATE) reportRate();

    vTaskDelayUntil(&nextWake, pdMS_TO_TICKS(cfg::PERIOD_MS));
  }
}

// ----------------------------------------------------------------------------
void sensorAppBegin() {
  Serial.begin(cfg::BAUD);
  setupSensorPin();

  // 호스트가 설정을 기록할 수 있도록 헤더를 진단 줄로 남긴다
  Serial.printf("# AirRhythm sensor_reader v1.0 board=ESP32-WROOM-32 pin=GPIO%u "
                "pullup=%s fs=%luHz oversample=%u ma_window=%u\n",
                cfg::SENSOR_PIN, cfg::USE_INTERNAL_PULLUP ? "internal" : "external",
                (unsigned long)(1000 / cfg::PERIOD_MS),
                cfg::OVERSAMPLE, cfg::MA_WINDOW);
  (void)readMilliVolts();                                   // 한 번 읽은 뒤에도 유지되는지 확인
  Serial.printf("# pullup_register=%s core=%d.%d.%d\n", pullupIsEnabled() ? "ON" : "OFF",
                ESP_ARDUINO_VERSION_MAJOR, ESP_ARDUINO_VERSION_MINOR, ESP_ARDUINO_VERSION_PATCH);
  Serial.println("# columns: seq\tt_ms\tmv\tma_mv");

  xTaskCreatePinnedToCore(senseTask, "sense", cfg::SENSE_STACK, nullptr,
                          cfg::SENSE_PRIO, nullptr, cfg::SENSE_CORE);
}
