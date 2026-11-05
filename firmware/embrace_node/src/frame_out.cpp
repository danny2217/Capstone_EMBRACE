// frame_out.cpp — 시리얼 프로토콜 v2
//
//  줄 종류 (모두 '\n' 으로 끝남)
//    # EMBRACE ...            헤더: 펌웨어·보드·샘플링 설정        (부팅 시 + 5 s 마다 + 'h' 수신 시)
//    # ch <이름> ...           채널 정의: 핀·회로·저항값·판정 문턱  (헤더와 함께)
//    # columns: seq t_ms ...   데이터 열 이름 (TAB 구분)             (헤더와 함께)
//    seq<TAB>t_ms<TAB>...     데이터 (20 ms 마다)
//    # WARN ch=.. state=..    상태가 정상 → 단선/단락 으로 바뀜
//    # INFO ch=.. state=ok    상태가 정상으로 돌아옴
//    # fs=...                 1 s 마다 실측 주기·작업 시간·유실 수
//
//  flags 열: 채널당 한 글자 (0 정상, 1 단선, 2 단락), 데이터 열 순서대로 이어 붙임. 예) 00010
#include "frame_out.h"
#include "../config.h"
#include "adc_reader.h"
#include "channel.h"
#include "frame.h"
#include "sense_task.h"

static QueueHandle_t s_q = nullptr;

// ----------------------------------------------------------------------------
static void printOhm(const char* key, uint32_t ohm) {
  if (ohm) Serial.printf(" %s=%lu", key, (unsigned long)ohm);
  else     Serial.printf(" %s=unknown", key);
}

static void printHeader() {
  const size_t n = channelCount();
  const Channel* ch = channels();

  Serial.printf("# EMBRACE embrace_node v%s board=ESP32-S3 core=%d.%d.%d fs=%luHz "
                "oversample=%u discard=%u ma_window=%u send_ma=%d\n",
                EMBRACE_FW_VERSION, ESP_ARDUINO_VERSION_MAJOR, ESP_ARDUINO_VERSION_MINOR,
                ESP_ARDUINO_VERSION_PATCH, (unsigned long)(1000 / cfg::PERIOD_MS),
                cfg::OVERSAMPLE, cfg::DISCARD, cfg::MA_WINDOW, cfg::SEND_MA ? 1 : 0);

  for (size_t i = 0; i < n; i++) {
    const Channel& c = ch[i];
    Serial.printf("# ch %s pin=%u kind=%s", c.name, c.pin, kindName(c.kind));
    switch (c.kind) {
      case ChKind::RubberPullup:
        if (c.rRef) Serial.printf(" r_pu=%lu", (unsigned long)c.rRef);
        else if (breathUsesSourcePin())                    // 저항 대신 GPIO 약출력을 풀업으로
          Serial.printf(" r_pu=gpio%u_drive0", cfg::BREATH_SOURCE_PIN);
        else Serial.print(" r_pu=internal");      // BREATH_R_PU_OHM=0 → 칩 내부 풀업 사용
        break;
      case ChKind::Fsr:
        if (c.rRef) Serial.printf(" r_ref=%lu", (unsigned long)c.rRef);
        else Serial.print(" r_ref=internal_pd");    // R_REF=0 → 칩 내부 풀다운 사용
        printOhm("r_bias", c.rBias);
        Serial.printf(" open_check=%s", c.lowCheck ? "on" : "off");
        break;
      case ChKind::Xgzp:
        Serial.print(" range_kpa=10 vout_mv=200-2700");
        break;
    }
    // 압박 FSR 채널은 감지부 지름도 남긴다 (PC 의 등가 압력 환산용, 집중판 없음)
    if (c.compression && c.kind == ChKind::Fsr) {
      Serial.printf(" sense_mm=%.1f", cfg::SENSE_DIAMETER_MM);
    }
    Serial.printf(" low_mv=%lu:%s high_mv=%lu:%s\n",
                  (unsigned long)c.lowMv, c.lowCheck ? healthName(c.lowState) : "none",
                  (unsigned long)c.highMv, healthName(c.highState));
  }

  Serial.print("# columns:\tseq\tt_ms");
  for (size_t i = 0; i < n; i++) Serial.printf("\t%s", ch[i].name);
  if (cfg::SEND_MA) for (size_t i = 0; i < n; i++) Serial.printf("\t%s_ma", ch[i].name);
  Serial.print("\tflags\n");
}

// ----------------------------------------------------------------------------
static void printFrame(const Frame& f) {
  const size_t n = channelCount();
  char line[160];
  int len = snprintf(line, sizeof(line), "%lu\t%lu", (unsigned long)f.seq, (unsigned long)f.t_ms);
  for (size_t i = 0; i < n; i++) len += snprintf(line + len, sizeof(line) - len, "\t%u", f.mv[i]);
  if (cfg::SEND_MA)
    for (size_t i = 0; i < n; i++) len += snprintf(line + len, sizeof(line) - len, "\t%u", f.ma[i]);
  line[len++] = '\t';
  for (size_t i = 0; i < n; i++) line[len++] = static_cast<char>('0' + f.health[i]);
  line[len++] = '\n';
  Serial.write(reinterpret_cast<const uint8_t*>(line), len);
}

// 상태 변화는 전송 태스크가 직접 비교해 찾는다 → 프레임이 버려져도 경고는 빠지지 않는다
static void printHealthChanges(const Frame& f, uint8_t* last) {
  const Channel* ch = channels();
  for (size_t i = 0; i < channelCount(); i++) {
    if (f.health[i] == last[i]) continue;
    last[i] = f.health[i];
    const Health h = static_cast<Health>(f.health[i]);
    Serial.printf("# %s ch=%s state=%s mv=%u\n", h == Health::Ok ? "INFO" : "WARN",
                  ch[i].name, healthName(h), f.mv[i]);
  }
}

// ----------------------------------------------------------------------------
//  1 s 마다 실측 주기 보고 — TC-F1 의 근거
// ----------------------------------------------------------------------------
struct RateStats {
  uint32_t n = 0, sum = 0, pMin = UINT32_MAX, pMax = 0, workMax = 0;

  void add(const Frame& f) {
    if (f.work_us > workMax) workMax = f.work_us;
    if (!f.period_us) return;
    sum += f.period_us; n++;
    if (f.period_us < pMin) pMin = f.period_us;
    if (f.period_us > pMax) pMax = f.period_us;
    if (n >= 1000 / cfg::PERIOD_MS) {
      Serial.printf("# fs=%.2fHz avg=%luus min=%luus max=%luus work_max=%luus drop=%lu\n",
                    1e6f * n / sum, (unsigned long)(sum / n), (unsigned long)pMin,
                    (unsigned long)pMax, (unsigned long)workMax,
                    (unsigned long)senseDropCount());
      *this = RateStats();
    }
  }
};

// ----------------------------------------------------------------------------
static void txTask(void*) {
  uint8_t lastHealth[MAX_CH] = {0};
  RateStats rate;
  uint32_t lastHeaderMs = 0;
  bool headerDue = true;
  Frame f;

  for (;;) {
    // PC 에서 'h' 를 보내면 즉시 헤더 재전송
    while (Serial.available()) {
      if (Serial.read() == 'h') headerDue = true;
    }
    if (xQueueReceive(s_q, &f, pdMS_TO_TICKS(100)) != pdTRUE) continue;

    if (headerDue || millis() - lastHeaderMs >= cfg::HEADER_EVERY_MS) {
      printHeader();
      lastHeaderMs = millis();
      headerDue = false;
    }
    printHealthChanges(f, lastHealth);
    printFrame(f);
    if (cfg::REPORT_RATE) rate.add(f);
  }
}

void txTaskStart(QueueHandle_t q) {
  s_q = q;
  xTaskCreatePinnedToCore(txTask, "tx", cfg::TASK_STACK, nullptr,
                          cfg::TX_PRIO, nullptr, cfg::SENSE_CORE);
}
