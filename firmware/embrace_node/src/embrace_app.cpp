// embrace_app.cpp
#include "embrace_app.h"

#include <Arduino.h>
#include "../config.h"
#include "adc_reader.h"
#include "channel.h"
#include "frame.h"
#include "frame_out.h"
#include "hr_link.h"
#include "sense_task.h"

// 보드 선택 검사: 이 코드는 ESP32-S3 의 ADC1 핀 배치(GPIO1~10)를 전제로 한다.
// Tools → Board 가 다른 칩이면 여기서 컴파일을 멈춘다.
#if !CONFIG_IDF_TARGET_ESP32S3
#error "보드 선택 오류: Tools > Board 에서 [ESP32S3 Dev Module] 을 선택하세요 (ESP32-S3 DevKitC-1 용 코드)"
#endif
#if ESP_ARDUINO_VERSION_MAJOR < 3
#error "Arduino-ESP32 코어 3.x 가 필요합니다 (Tools > Board > Boards Manager 에서 esp32 3.x 설치)"
#endif

void embraceBegin() {
  Serial.begin(cfg::BAUD);

  const size_t n = channelsInit();
  adcInit(channels(), n);

  QueueHandle_t q = xQueueCreate(cfg::QUEUE_LEN, sizeof(Frame));
  if (!q) {
    // 메모리 부족 — 정상 동작 불가. 계속 알린다.
    for (;;) {
      Serial.println("# ERROR queue_alloc_failed");
      delay(1000);   // 여기는 태스크 시작 전 setup() 안의 치명적 오류 경로라 delay 허용
    }
  }
  txTaskStart(q);      // 전송 태스크가 첫 프레임을 받을 때 헤더를 먼저 보낸다
  senseTaskStart(q);

  // 센싱을 먼저 시작한 "뒤에" BLE 를 켠다.
  // BLE 초기화가 실패하거나 느려도 센서 측정은 이미 돌고 있다 (워치는 보조 입력).
  hrLinkBegin();
}
