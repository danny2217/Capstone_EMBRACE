// hr_link.h — 워치 심박 BLE 수신 (ESP = BLE 서버, 워치 = 클라이언트)
//
//  다른 모듈은 hrLinkGet() 하나만 쓴다. BLE 콜백·락은 hr_link.cpp 안에 숨긴다.
//  워치가 없어도 hrLinkGet() 은 항상 즉시 돌아온다 (state = None) → 센서·공압은 영향 없음.
#pragma once
#include <Arduino.h>

enum class HrState : uint8_t {
  None,       // 한 번도 받은 적 없음 (워치 없이 사용)
  Stale,      // 끊김: HR_STALE_MS 동안 유효한 새 값 없음 → 판단에서 제외
  Rejoining,  // 다시 들어오는 중: 연속 수신이 HR_REJOIN_SAMPLES 미만 → 아직 제외
  Fresh,      // 사용 가능 → 판단에 포함
};

struct HrStatus {
  HrState  state;
  uint8_t  bpm;          // 마지막 유효 심박 (state 가 Fresh 일 때만 믿는다)
  uint32_t ageMs;        // 마지막 유효 심박 이후 경과 시간 (받은 적 없으면 UINT32_MAX)
  uint8_t  connections;  // 지금 접속한 BLE 클라이언트 수 (워치, 나중에 폰 앱)
  uint32_t rxCount;      // 받은 패킷 수 (전체)
  uint32_t badCount;     // 형식 오류·범위 밖·센서 미준비 패킷 수

  bool usable() const { return state == HrState::Fresh; }
};

// BLE 를 켜고 광고를 시작한다. config.h 의 ENABLE_HR_BLE 가 false 면 아무것도 하지 않는다.
void hrLinkBegin();

// 현재 심박 상태. 어느 태스크에서 불러도 안전하다 (락으로 보호).
HrStatus hrLinkGet();

const char* hrStateName(HrState s);
