// sense_task.cpp — delay() 없이 절대 시각 기준으로 20 ms 마다 실행
//
//  delay(20) 은 "작업 후 20 ms 더 쉬기" 라 실제 주기가 (작업시간 + 20 ms) 로 늘어난다.
//  vTaskDelayUntil 은 다음 기상 시각을 절대값으로 누적하므로 작업 시간이 주기에
//  포함되어 정확히 PERIOD_MS 간격이 유지된다.
//
//  이 태스크는 Serial 을 전혀 쓰지 않는다. 출력은 모두 전송 태스크가 한다.
//  → USB 가 잠시 막혀도 샘플링 주기는 흔들리지 않는다 (막히는 동안 큐에 쌓임).
#include "sense_task.h"
#include "../config.h"
#include "adc_reader.h"
#include "channel.h"
#include "frame.h"
#include "breath_filter.h"
#include <string.h>

static QueueHandle_t s_q = nullptr;
static QueueHandle_t s_ctrlQ = nullptr;
static volatile uint32_t s_drop = 0;
static volatile uint32_t s_ctrlDrop = 0;

uint32_t senseDropCount() { return s_drop; }
uint32_t senseCtrlDropCount() { return s_ctrlDrop; }

static void senseTask(void*) {
  Channel* ch = channels();
  const size_t n = channelCount();
  uint32_t seq = 0;
  uint32_t prevUs = 0;
  TickType_t nextWake = xTaskGetTickCount();

  int iBreath = -1;
  for (size_t i = 0; i < n; i++)
    if (strcmp(ch[i].name, "breath") == 0) iBreath = static_cast<int>(i);
  BreathBandpass bp;
  bp.begin(1000.0f / cfg::PERIOD_MS, cfg::BREATH_BP_HP_HZ, cfg::BREATH_BP_LP_HZ);

  for (;;) {
    const uint32_t startUs = micros();
    Frame f = {};
    f.seq = seq++;
    f.t_ms = millis();

    // 모든 채널을 같은 주기 안에서 연달아 읽는다 → 같은 seq·t_ms 를 공유 (센서 퓨전의 전제)
    for (size_t i = 0; i < n; i++) {
      uint16_t spread = 0;
      const uint32_t mv = adcReadMv(ch[i].pin, &spread);
      f.mv[i] = static_cast<uint16_t>(mv);
      f.ma[i] = static_cast<uint16_t>(ch[i].ma.push(mv));
      f.health[i] = static_cast<uint8_t>(ch[i].updateHealth(mv, spread));
    }

    // 호흡 대역통과: 오버샘플 평균 mV 를 그대로 넣는다. 비정상(단선·단락)이면 필터를 처음부터 다시
    if (iBreath >= 0) {
      // 잡음(Noisy)은 펌프 전기 잡음으로 자주 걸린다 → 필터는 단선·단락일 때만 처음부터 (11/26)
      if (f.health[iBreath] != static_cast<uint8_t>(Health::Open) && f.health[iBreath] != static_cast<uint8_t>(Health::Short)) {
        const float y = bp.update(static_cast<float>(f.mv[iBreath]));
        f.br_bp = static_cast<int16_t>(y >= 0 ? y + 0.5f : y - 0.5f);
      } else {
        bp.reset();
        f.br_bp = 0;
      }
    }

    f.period_us = prevUs ? (startUs - prevUs) : 0;   // unsigned 뺄셈 → micros() 순환에도 안전
    prevUs = startUs;
    f.work_us = micros() - startUs;

    if (xQueueSend(s_q, &f, 0) != pdTRUE) s_drop = s_drop + 1;
    if (s_ctrlQ && xQueueSend(s_ctrlQ, &f, 0) != pdTRUE) s_ctrlDrop = s_ctrlDrop + 1;

    vTaskDelayUntil(&nextWake, pdMS_TO_TICKS(cfg::PERIOD_MS));
  }
}

void senseTaskStart(QueueHandle_t txQ, QueueHandle_t ctrlQ) {
  s_q = txQ;
  s_ctrlQ = ctrlQ;
  xTaskCreatePinnedToCore(senseTask, "sense", cfg::TASK_STACK, nullptr,
                          cfg::SENSE_PRIO, nullptr, cfg::SENSE_CORE);
}
