// control_task.h — 공압 상태 머신 (FSM) + 안전 감시
//
//  상태 (docs/control_fsm.md 그림 참고)
//    BOOT_VENT → IDLE → INFLATE → HOLD → DEFLATE → COOLDOWN → IDLE
//    INFLATE/HOLD 중 위험 → EMERGENCY(배기) → LOCKOUT (자동 가압 금지, 'r' 로 해제)
#pragma once
#include <Arduino.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"

enum class CtlState : uint8_t { BootVent, Idle, Inflate, Hold, Deflate, Cooldown, Emergency, Lockout };
const char* ctlStateName(CtlState s);

// 제어 태스크 시작. frameQ = 센싱 태스크가 프레임을 넣어 주는 큐 (전송용 큐와 별개)
void controlTaskStart(QueueHandle_t frameQ);

// 시리얼 명령 전달 (전송 태스크가 부른다)
//   'i' 수동 가압   'd' 배기   'x' 비상 배기   'r' LOCKOUT 해제
void ctlCommand(char c);

// 전송 태스크가 1 초마다 상태 줄을 찍는다
void ctlPrintStatus();
// 쌓인 이벤트 줄을 모두 찍는다 (상태 전이 등)
void ctlPrintEvents();
// 판단 기준·공압 설정 2줄 ('# det ...', '# pn ...') — 헤더와 함께 찍는다
void ctlPrintConfig();
// ============================================================================
//  폰 앱으로 보낼 상태 요약. hr_link.cpp 의 statusTask 가 1 초마다 읽는다.
// ============================================================================
struct CtlStatus {
	CtlState state;
	uint32_t inStateMs;      // 지금 상태에 들어온 뒤 시간
	uint32_t cycles;         // 유지까지 간 가압 횟수
	bool     pump, valveOpen, framesOk, armed;
	bool     overload;       // 과부하 확정
	uint8_t  score;          // 판단 점수 합
	bool     breathValid;    // 1 분 창 호흡수가 믿을 만한가
	bool     breathHealthy;  // 호흡 센서 정상
	bool     apnea;          // 무호흡
	float    breathRate;     // 분당 호흡수 (1 분 창)
	float    ieRatio;        // 들숨/날숨 (1 분 창)
	uint16_t gripL, gripR;   // 악력 이동평균 (mV)
	uint16_t compMaxMv;      // 가슴·등 압박 중 큰 값 (mV)
};
bool ctlGetStatus(CtlStatus& out);