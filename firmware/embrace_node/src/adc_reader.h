// adc_reader.h — ESP32-S3 ADC1 읽기 (버림 읽기 + 오버샘플)
#pragma once
#include <Arduino.h>
#include "channel.h"

// 모든 채널 핀을 ADC 로 붙이고, 내부 풀업/풀다운을 끈다 (외부 저항만 사용)
void adcInit(Channel* ch, size_t n);

// 채널 하나를 읽어 mV 로 돌려준다 (eFuse 보정 적용)
//   DISCARD 회 버림 → 직전 채널의 잔류 전하(크로스토크) 제거
//   OVERSAMPLE 회 평균 → 양자화·열잡음 감소
uint32_t adcReadMv(uint8_t pin);
