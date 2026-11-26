// hr_link.h — 조끼 BLE 서버: 아동용 폰 앱과 주고받기 (v2, 11/22 설계 변경)
//
//  v1: 워치 ──심박──▶ 조끼                       (워치가 BLE 클라이언트)
//  v2: 워치 ──심박·위치──▶ 아동용 폰 앱 ──심박──▶ 조끼   (폰 앱이 BLE 클라이언트)
//                          아동용 폰 앱 ◀──상태── 조끼   (1 초마다 notify)
//
//  ESP 는 여전히 BLE 서버(peripheral) 다. 심박 패킷(HR_IN) 형식은 v1 과 같다 → 보내는 쪽만 워치→폰으로 바뀜.
//  다른 모듈은 hrLinkGet() 만 쓴다. BLE 콜백·락·상태 전송 태스크는 hr_link.cpp 안에 숨긴다.
//  폰이 없어도 hrLinkGet() 은 항상 즉시 돌아온다 (state = None) → 센서·공압은 영향 없음.
//  프로토콜 표: docs/ble_protocol.md
#pragma once
#include <Arduino.h>
#include "frame.h"

enum class HrState : uint8_t {
  None,       // 한 번도 받은 적 없음 (워치·폰 없이 사용)
  Stale,      // 끊김: HR_STALE_MS 동안 유효한 새 값 없음 → 판단에서 제외
  Rejoining,  // 다시 들어오는 중: 연속 수신이 HR_REJOIN_SAMPLES 미만 → 아직 제외
  Fresh,      // 사용 가능 → 판단에 포함
};

struct HrStatus {
  HrState  state;
  uint8_t  bpm;          // 마지막 유효 심박 (state 가 Fresh 일 때만 믿는다)
  uint32_t ageMs;        // 마지막 유효 심박 이후 경과 시간 (받은 적 없으면 UINT32_MAX)
  uint8_t  connections;  // 지금 접속한 BLE 클라이언트 수 (아동용 폰 앱)
  uint32_t rxCount;      // 받은 심박 패킷 수 (전체)
  uint32_t badCount;     // 형식 오류·범위 밖·센서 미준비 패킷 수

  bool usable() const { return state == HrState::Fresh; }
};

// BLE 를 켜고 광고를 시작한다. 상태 알림(STATUS) 태스크도 여기서 시작한다.
// config.h 의 ENABLE_HR_BLE 가 false 면 아무것도 하지 않는다.
void hrLinkBegin();

// 현재 심박 상태. 어느 태스크에서 불러도 안전하다 (락으로 보호).
HrStatus hrLinkGet();

const char* hrStateName(HrState s);

// 앱 호흡 그래프용 파형 (WAVE 특성 e3b70005). 제어 태스크가 프레임마다 부른다.
//  br_bp(대역통과 값) 2 개를 평균해 25 Hz 로 버퍼에 넣기만 한다 → 빠르고 막히지 않는다.
//  0.2 초마다 BLE 태스크가 묶어서 notify:  [0] 2 [1] 순번 [2] 개수 n [3..] int16 LE × n (mV, 0 중심)
//  호흡 센서 이상(단선·단락)이면 -32768.
void hrLinkPushFrame(const Frame& f);

// 앱 연결 진단 ('# app ...' 줄용)
struct AppLinkStats {
  uint32_t statusSent;    // 보낸 상태 알림 수
  uint32_t cmdAccepted;   // 받아들인 앱 명령 수 (d, x)
  uint32_t cmdRejected;   // 거부한 앱 명령 수 (그 밖의 글자)
};
AppLinkStats appLinkStats();
