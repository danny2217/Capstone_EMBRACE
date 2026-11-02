// =============================================================================
//  embrace_node.ino — EMBRACE 통합 센서 펌웨어 (ESP32-S3 DevKitC-1)
// =============================================================================
//  호흡 1 + 악력 2 + 압박 2 = 5채널을 50 Hz 로 "같은 주기 안에서" 읽어
//  한 줄 프레임으로 PC 에 보낸다. 모든 채널이 같은 seq·t_ms 를 갖는다.
//
//  설정은 config.h 하나만 고친다 (핀, 채널 ON/OFF, 저항값, 압박 센서 종류).
//
//  Arduino IDE 설정
//    Tools > Board          : ESP32S3 Dev Module
//    Tools > USB CDC On Boot: Disabled  ← 보드의 "UART" 라고 적힌 USB 포트에 꽂을 때
//                             Enabled   ← "USB" 라고 적힌 포트에 꽂을 때
//    시리얼 모니터 속도     : 115200
//
//  태스크 구성 (delay() 를 쓰지 않는다)
//    senseTask (코어1, 우선순위3) : 20 ms 마다 ADC 읽기 → 이동평균 → 상태판정 → 큐
//    txTask    (코어1, 우선순위2) : 큐 → 시리얼 텍스트 출력, 5 s 마다 헤더 재전송
//    loop()                       : 아무 일도 하지 않음
//
//  파일 구성 — 모두 이 폴더(embrace_node/) 안에 있어야 한다
//    embrace_node.ino   진입점
//    config.h           설정
//    src/               구현 (Arduino IDE 가 src/ 아래를 자동으로 컴파일한다)
//
//  v1(breath-sensor/firmware, ESP32-WROOM-32)은 git 태그 v1.0-wroom32 에 보존되어 있다.
// =============================================================================

#include "src/embrace_app.h"

void setup() {
  embraceBegin();
}

void loop() {
  // 모든 작업은 senseTask / txTask 가 한다. loop 태스크는 CPU 를 양보만 한다.
  vTaskDelay(portMAX_DELAY);
}
