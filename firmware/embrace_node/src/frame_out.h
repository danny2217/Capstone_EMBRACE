// frame_out.h — 시리얼 출력 (헤더 · 데이터 · 진단) 을 전담하는 전송 태스크
#pragma once
#include <Arduino.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

#define EMBRACE_FW_VERSION "2.0.0"

void txTaskStart(QueueHandle_t q);
