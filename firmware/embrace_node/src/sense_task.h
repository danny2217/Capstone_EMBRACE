// sense_task.h — 50 Hz 센싱 태스크
#pragma once
#include <Arduino.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

// 센싱 태스크를 시작한다. 프레임은 q 로 보낸다 (가득 차면 버리고 drop 카운터 증가).
void senseTaskStart(QueueHandle_t q);

// 큐가 가득 차서 버린 프레임 수 (전송 태스크가 진단 줄에 쓴다)
uint32_t senseDropCount();
