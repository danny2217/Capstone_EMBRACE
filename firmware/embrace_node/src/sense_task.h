// sense_task.h — 50 Hz 센싱 태스크
#pragma once
#include <Arduino.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

// 센싱 태스크를 시작한다. 같은 프레임을 두 큐에 보낸다 (가득 차면 버리고 drop 카운터 증가).
//   txQ   : 전송 태스크 (시리얼 출력)
//   ctrlQ : 제어 태스크 (판단·공압). nullptr 이면 보내지 않음
void senseTaskStart(QueueHandle_t txQ, QueueHandle_t ctrlQ);

// 큐가 가득 차서 버린 프레임 수 (전송 태스크가 진단 줄에 쓴다)
uint32_t senseDropCount();
// 제어 큐가 가득 차서 버린 프레임 수 (0 이어야 정상)
uint32_t senseCtrlDropCount();
