// pneumatic.h — 펌프·밸브 출력 (L298N). 상태 머신(control_task)만 이 모듈을 부른다.
//
//  원본: firmware/pump_test/Air_pump_valve/Air_pump_valve.ino (동료 코드, 같은 핀·같은 신호)
//  바꾼 점: delay() 시퀀스 제거 → "지금 이 상태로 둬라" 만 하는 함수로 축소. 시간은 상태 머신이 잰다.
#pragma once
#include <Arduino.h>

// 펌웨어에서 "가장 먼저" 부른다 (Serial 보다도 먼저).
// 네 핀을 출력 LOW 로 만들어 부팅 직후 펌프가 도는 일을 막는다.
void pneumaticSafeInit();

// 원하는 출력 상태를 적용한다.
//  - pumpOn && valveOpen 은 허용하지 않는다 (공기가 바로 새므로 펌프를 끈다).
//  - PNEUMATIC_ARMED == false 면 핀은 항상 LOW, 요청 값만 기록한다 (드라이런).
void pneumaticApply(bool pumpOn, bool valveOpen);

// 마지막으로 "요청된" 상태 (드라이런이어도 상태 머신이 원한 값) — 로그용
bool pneumaticPumpOn();
bool pneumaticValveOpen();
