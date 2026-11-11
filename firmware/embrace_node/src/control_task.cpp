// control_task.cpp — 공압 상태 머신 + 안전 감시 (설명: docs/control_fsm.md)
//
//  태스크 구성에서의 위치
//    senseTask (prio 3) ──Frame──▶ txQ   ──▶ txTask (prio 2): 시리얼 출력
//                       └─Frame──▶ ctrlQ ──▶ controlTask (prio 4, 이 파일)
//                                                │ detector: 호흡·악력·심박 → 점수
//                                                │ FSM: 상태 전이, 안전 검사
//                                                └ pneumatic: 펌프·밸브 핀
//  controlTask 는 Serial 을 직접 쓰지 않는다. 상태는 스냅샷(락)으로, 사건은 이벤트 큐로
//  전송 태스크에 넘긴다 → 출력이 막혀도 제어 주기는 흔들리지 않는다.
//
//  안전 원칙
//    1) 위험 신호가 하나라도 있으면 "배기" 쪽으로 간다 (가압 쪽으로 가는 길은 좁게)
//    2) 모든 가압·유지에는 시간 상한이 있다 (센서가 전부 죽어도 끝난다)
//    3) 이 태스크가 멈추면 워치독이 칩을 리셋 → 부팅하자마자 BOOT_VENT 로 배기
#include "control_task.h"

#include <stdarg.h>
#include "esp_task_wdt.h"
#include "../config.h"
#include "../detect_config.h"
#include "detector.h"
#include "frame.h"
#include "pneumatic.h"

struct Event { char text[112]; };

static QueueHandle_t s_frameQ = nullptr;
static QueueHandle_t s_cmdQ = nullptr;
static QueueHandle_t s_evtQ = nullptr;

static CtlState s_state = CtlState::BootVent;
static uint32_t s_enteredMs = 0;
static uint32_t s_lastFrameMs = 0;
static bool     s_compOver = false;
static uint32_t s_compOverSince = 0;
static uint32_t s_cycles = 0;          // 유지까지 간 가압 횟수
static uint32_t s_evtDrop = 0;

// 전송 태스크가 읽는 스냅샷
struct Snapshot {
  CtlState state;
  uint32_t inStateMs;
  DetectResult d;
  bool pump, valveOpen, framesOk;
  uint32_t cycles;
};
static portMUX_TYPE s_snapLock = portMUX_INITIALIZER_UNLOCKED;
static Snapshot s_snap = {};

const char* ctlStateName(CtlState s) {
  switch (s) {
    case CtlState::BootVent:  return "boot_vent";
    case CtlState::Idle:      return "idle";
    case CtlState::Inflate:   return "inflate";
    case CtlState::Hold:      return "hold";
    case CtlState::Deflate:   return "deflate";
    case CtlState::Cooldown:  return "cooldown";
    case CtlState::Emergency: return "emergency";
    case CtlState::Lockout:   return "lockout";
  }
  return "?";
}

static void event(const char* fmt, ...) {
  Event e;
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(e.text, sizeof(e.text), fmt, ap);
  va_end(ap);
  if (!s_evtQ || xQueueSend(s_evtQ, &e, 0) != pdTRUE) s_evtDrop++;
}

// 상태별 출력 — 이 표 하나가 "어느 상태에서 펌프·밸브가 어떤가" 의 전부다
static void outputsFor(CtlState s, bool& pump, bool& valveOpen) {
  switch (s) {
    case CtlState::Inflate:   pump = true;  valveOpen = false; return;
    case CtlState::BootVent:
    case CtlState::Deflate:
    case CtlState::Emergency: pump = false; valveOpen = true;  return;
    default:                  pump = false; valveOpen = false; return;   // Idle, Hold, Cooldown, Lockout
  }
}

static void enter(CtlState next, uint32_t now, const char* why) {
  if (next == s_state) return;
  event("# EVT %s->%s why=%s", ctlStateName(s_state), ctlStateName(next), why);
  if (s_state == CtlState::Inflate && next == CtlState::Hold) s_cycles++;
  s_state = next;
  s_enteredMs = now;
  bool pump, valve;
  outputsFor(next, pump, valve);
  pneumaticApply(pump, valve);
  if (next == CtlState::Idle) detectorRearm();   // 대기로 돌아오면 확정 타이머를 새로 시작
}

// 가압·유지 중 "지금 당장 배기해야 하는가" — 이유 문자열, 없으면 nullptr
static const char* dangerReason(uint32_t now, const DetectResult& d, bool framesOk) {
  if (!framesOk) return "sensing_stalled";
  if (cfg::COMP_LIMIT_MV > 0 && s_compOver && now - s_compOverSince >= cfg::COMP_LIMIT_HOLD_MS)
    return "comp_over_limit";
  if (d.compFault) return "comp_sensor_fault";
  if (cfg::EMERGENCY_ON_BREATH_FAULT && d.breathEnabled && !d.breathHealthy) return "breath_sensor_fault";
  if (d.breath.apnea) return "apnea";
  return nullptr;
}

static void reject(char cmd, const char* why) {
  event("# EVT reject cmd=%c state=%s why=%s", cmd, ctlStateName(s_state), why);
}

// 한 번의 판단. now 기준으로 상태를 한 칸 옮긴다.
static void fsmStep(uint32_t now, const DetectResult& d, char cmd, bool estop, bool framesOk) {
  const uint32_t el = now - s_enteredMs;
  const CtlState before = s_state;

  // 압박 상한 초과가 얼마나 계속됐나 (상태와 무관하게 늘 추적)
  const bool over = cfg::COMP_LIMIT_MV > 0 && d.compMaxMv >= cfg::COMP_LIMIT_MV;
  if (over && !s_compOver) s_compOverSince = now;
  s_compOver = over;

  // ① 비상 입력 — 어느 상태에서든
  if (estop && s_state != CtlState::Emergency) { enter(CtlState::Emergency, now, "estop_button"); return; }
  if (cmd == 'x' && s_state != CtlState::Emergency) { enter(CtlState::Emergency, now, "serial_x"); return; }

  // ② 공기가 들어 있는 상태의 안전 검사
  if (s_state == CtlState::Inflate || s_state == CtlState::Hold) {
    const char* why = dangerReason(now, d, framesOk);
    if (why) { enter(CtlState::Emergency, now, why); return; }
  }

  // ③ 상태별 전이
  switch (s_state) {
    case CtlState::BootVent:
      if (el >= cfg::BOOT_VENT_MS) enter(CtlState::Idle, now, "boot_vent_done");
      break;

    case CtlState::Idle:
      if (cmd == 'i') {
        if (!framesOk) reject(cmd, "sensing_not_ready");
        else enter(CtlState::Inflate, now, "manual_i");
      } else if (det::DETECT_ENABLED && framesOk && d.overload) {
        char why[48];
        snprintf(why, sizeof(why), "detect(score=%u,nonhr=%u)", d.score, d.nonHrScore);
        enter(CtlState::Inflate, now, why);
      }
      break;

    case CtlState::Inflate:
      if (cmd == 'd') enter(CtlState::Deflate, now, "manual_d");
      else if (el >= cfg::INFLATE_MS || el >= cfg::INFLATE_MAX_MS) enter(CtlState::Hold, now, "inflate_time");
      else if (cfg::COMP_TARGET_MV > 0 && d.compMaxMv >= cfg::COMP_TARGET_MV) enter(CtlState::Hold, now, "comp_target");
      break;

    case CtlState::Hold:
      if (cmd == 'd') enter(CtlState::Deflate, now, "manual_d");
      else if (el >= cfg::HOLD_MS || el >= cfg::HOLD_MAX_MS) enter(CtlState::Deflate, now, "hold_time");
      else if (det::EARLY_RELEASE_ENABLED && el >= det::HOLD_MIN_MS && d.calmForMs >= det::CALM_MS)
        enter(CtlState::Deflate, now, "calm");
      break;

    case CtlState::Deflate:
      if (el >= cfg::DEFLATE_MS) enter(CtlState::Cooldown, now, "deflate_done");
      break;

    case CtlState::Cooldown:
      if (cmd == 'i') reject(cmd, "cooldown");
      if (el >= cfg::COOLDOWN_MS) enter(CtlState::Idle, now, "cooldown_done");
      break;

    case CtlState::Emergency:
      if (el >= cfg::EMERGENCY_VENT_MS) enter(CtlState::Lockout, now, "vent_done");
      break;

    case CtlState::Lockout:
      if (cmd == 'r') enter(CtlState::Idle, now, "reset_r");
      else if (cmd == 'i') reject(cmd, "lockout(send r first)");
      break;
  }
  if (cmd == 'i' && (before == CtlState::Inflate || before == CtlState::Hold ||
                     before == CtlState::Deflate || before == CtlState::BootVent))
    reject(cmd, "busy");
}

// BOOT 버튼: 눌림이 ESTOP_DEBOUNCE_MS 동안 유지되면 "한 번 눌림" (누르고 있는 동안 반복하지 않음)
static bool readEstop(uint32_t now) {
  if (!cfg::ENABLE_ESTOP_BUTTON) return false;
  static bool latched = false;
  static uint32_t lowSince = 0;
  const bool low = digitalRead(cfg::PIN_ESTOP) == LOW;
  if (!low) { lowSince = 0; latched = false; return false; }
  if (lowSince == 0) lowSince = now ? now : 1;
  if (!latched && now - lowSince >= cfg::ESTOP_DEBOUNCE_MS) { latched = true; return true; }
  return false;
}

static void wdtAttach() {
  // Arduino 코어가 이미 태스크 워치독을 켜 두었으면 등록만 (그 경우 시간은 코어 기본값)
  if (esp_task_wdt_add(nullptr) == ESP_OK) return;
  esp_task_wdt_config_t c = {};
  c.timeout_ms = cfg::CTRL_WDT_MS;
  c.idle_core_mask = 0;
  c.trigger_panic = true;              // 멈추면 리셋 → 부팅 배기
  if (esp_task_wdt_init(&c) == ESP_OK) esp_task_wdt_add(nullptr);
}

static void publish(uint32_t now, const DetectResult& d, bool framesOk) {
  Snapshot s;
  s.state = s_state;
  s.inStateMs = now - s_enteredMs;
  s.d = d;
  s.pump = pneumaticPumpOn();
  s.valveOpen = pneumaticValveOpen();
  s.framesOk = framesOk;
  s.cycles = s_cycles;
  portENTER_CRITICAL(&s_snapLock);
  s_snap = s;
  portEXIT_CRITICAL(&s_snapLock);
}

static void controlTask(void*) {
  wdtAttach();
  const uint32_t t0 = millis();
  s_enteredMs = t0;
  s_state = CtlState::BootVent;
  bool p, v;
  outputsFor(s_state, p, v);
  pneumaticApply(p, v);
  event("# EVT start state=boot_vent armed=%d valve=%s", cfg::PNEUMATIC_ARMED ? 1 : 0,
        cfg::VALVE_NORMALLY_CLOSED ? "NC" : "NO");

  Frame f;
  for (;;) {
    esp_task_wdt_reset();

    // 프레임을 기다리되 20 ms 이상은 안 기다린다 → 센싱이 멈춰도 감시는 돈다
    if (xQueueReceive(s_frameQ, &f, pdMS_TO_TICKS(20)) == pdTRUE) {
      do {
        detectorPush(f);
      } while (xQueueReceive(s_frameQ, &f, 0) == pdTRUE);   // 밀린 것까지 모두
      s_lastFrameMs = millis();
    }

    const uint32_t now = millis();
    char cmd = 0;
    xQueueReceive(s_cmdQ, &cmd, 0);
    const bool estop = readEstop(now);
    const bool framesOk = s_lastFrameMs != 0 && now - s_lastFrameMs <= cfg::FRAME_TIMEOUT_MS;
    const DetectResult d = detectorResult(now);

    fsmStep(now, d, cmd, estop, framesOk);
    publish(now, d, framesOk);
  }
}

void controlTaskStart(QueueHandle_t frameQ) {
  s_frameQ = frameQ;
  s_cmdQ = xQueueCreate(8, sizeof(char));
  s_evtQ = xQueueCreate(16, sizeof(Event));
  if (cfg::ENABLE_ESTOP_BUTTON) pinMode(cfg::PIN_ESTOP, INPUT_PULLUP);
  detectorInit();
  xTaskCreatePinnedToCore(controlTask, "ctrl", cfg::CTRL_STACK, nullptr, cfg::CTRL_PRIO, nullptr,
                          cfg::SENSE_CORE);
}

void ctlCommand(char c) {
  if (s_cmdQ && (c == 'i' || c == 'd' || c == 'x' || c == 'r')) xQueueSend(s_cmdQ, &c, 0);
}

// ----------------------------------------------------------------------------
//  출력 (전송 태스크에서 호출)
// ----------------------------------------------------------------------------
void ctlPrintStatus() {
  Snapshot s;
  portENTER_CRITICAL(&s_snapLock);
  s = s_snap;
  portEXIT_CRITICAL(&s_snapLock);
  const DetectResult& d = s.d;

  char rules[5] = {
      (d.rules & RULE_BREATH_FAST) ? 'B' : '-', (d.rules & RULE_IE_HIGH) ? 'E' : '-',
      (d.rules & RULE_GRIP_HOLD) ? 'G' : '-', (d.rules & RULE_HR_HIGH) ? 'H' : '-', '\0'};
  char hr[24];
  if (d.hr.usable()) snprintf(hr, sizeof(hr), "fresh:%u", d.hr.bpm);
  else snprintf(hr, sizeof(hr), "%s", hrStateName(d.hr.state));

  // 한 줄로: 상태 · 점수 · 호흡(수/비/진폭) · 악력 · 압박 · 심박 · 출력
  Serial.printf("# ctl st=%s t=%lus score=%u/%u rules=%s conf=%lums "
                "br_rate=%.1f ie=%.2f amp=%u n=%u br_ok=%d apnea=%d rate60=%.1f ie60=%.2f "
                "grip_ms=%lu gl=%u gr=%u comp=%u hr=%s pump=%d valve=%s armed=%d cycles=%lu\n",
                ctlStateName(s.state), (unsigned long)(s.inStateMs / 1000), d.score, d.nonHrScore,
                rules, (unsigned long)d.overloadForMs, d.breath.ratePerMin, d.breath.ieRatio,
                d.breath.ampMv, d.breath.cycles, d.breath.valid ? 1 : 0, d.breath.apnea ? 1 : 0,
                d.breath60.ratePerMin, d.breath60.ieRatio,
                (unsigned long)d.gripHoldMs, d.gripL, d.gripR, d.compMaxMv, hr, s.pump ? 1 : 0,
                s.valveOpen ? "open" : "closed", cfg::PNEUMATIC_ARMED ? 1 : 0,
                (unsigned long)s.cycles);
}

void ctlPrintEvents() {
  if (!s_evtQ) return;
  Event e;
  while (xQueueReceive(s_evtQ, &e, 0) == pdTRUE) Serial.println(e.text);
}

// 부팅 헤더에 판단 기준·공압 설정을 남긴다 → CSV 에 함께 기록되어 "어떤 기준으로 돌렸는지" 가 남는다
void ctlPrintConfig() {
  Serial.printf("# det enabled=%d trigger=%u min_nonhr=%u confirm_ms=%lu "
                "B=%u:%u E=%u:%u G=%u/%u,%lums,both=%d:%u H=%u:%u "
                "hyst=%u invert=%d window_ms=%lu/%lu apnea_ms=%lu early_release=%d\n",
                det::DETECT_ENABLED ? 1 : 0, det::SCORE_TRIGGER, det::MIN_NON_HR_SCORE,
                (unsigned long)det::CONFIRM_MS, det::BREATH_RATE_HIGH, det::W_BREATH_FAST,
                det::IE_HIGH_X100, det::W_IE, det::GRIP_ON_MV, det::GRIP_OFF_MV,
                (unsigned long)det::GRIP_HOLD_MS, det::GRIP_REQUIRE_BOTH ? 1 : 0, det::W_GRIP,
                det::HR_HIGH_BPM, det::W_HR, det::BREATH_HYST_MV, det::BREATH_INVERT ? 1 : 0,
                (unsigned long)det::BREATH_DETECT_WINDOW_MS, (unsigned long)det::BREATH_REPORT_WINDOW_MS,
                (unsigned long)det::BREATH_APNEA_MS,
                det::EARLY_RELEASE_ENABLED ? 1 : 0);
  Serial.printf("# pn armed=%d valve=%s pins=%u,%u,%u,%u inflate_ms=%lu hold_ms=%lu deflate_ms=%lu "
                "cooldown_ms=%lu boot_vent_ms=%lu comp_target=%lu comp_limit=%lu estop=%d\n",
                cfg::PNEUMATIC_ARMED ? 1 : 0, cfg::VALVE_NORMALLY_CLOSED ? "NC" : "NO",
                cfg::PIN_PUMP_IN1, cfg::PIN_PUMP_IN2, cfg::PIN_VALVE_IN3, cfg::PIN_VALVE_IN4,
                (unsigned long)cfg::INFLATE_MS, (unsigned long)cfg::HOLD_MS,
                (unsigned long)cfg::DEFLATE_MS, (unsigned long)cfg::COOLDOWN_MS,
                (unsigned long)cfg::BOOT_VENT_MS, (unsigned long)cfg::COMP_TARGET_MV,
                (unsigned long)cfg::COMP_LIMIT_MV, cfg::ENABLE_ESTOP_BUTTON ? 1 : 0);
}
