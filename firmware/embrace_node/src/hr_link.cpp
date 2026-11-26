// hr_link.cpp — 조끼 BLE 서버 (v2): 폰 앱 → 심박 수신, 조끼 → 폰 앱 상태 알림, 폰 앱 → 배기 명령
//
//  구조
//    아동용 폰 앱(BLE 클라이언트)
//       ├─ write 4바이트 (HR_IN)   ──▶ onPacket()  ──▶ 공유 변수 ──▶ hrLinkGet() (판단 모듈)
//       ├─ notify 20바이트 (STATUS) ◀── statusTask (1 초마다, ctlGetStatus() 스냅샷을 포장)
//       └─ write 1바이트 (CMD)      ──▶ 'd'/'x' 만 ctlCommand() 로 넘김 (배기 쪽만 허용)
//
//  안전 관련 설계
//    - 이 모듈은 공압 핀을 직접 건드리지 않는다. 명령은 상태 머신의 명령 큐로만 넘긴다.
//    - BLE 에 인증이 없다(MVP). 그래서 앱 명령은 "공기를 빼는 쪽"(d 배기, x 비상 배기)만 받는다.
//      가압(i), LOCKOUT 해제(r) 는 조끼 앞의 사람(시리얼·버튼)만 할 수 있다.
//    - 심박은 범위 검사 후 판단의 "보조 근거" 로만 쓴다. 심박 하나만으로는 가압되지 않는다 (detector).
//
//  패킷 형식은 docs/ble_protocol.md 와 1:1 대응. 하나를 바꾸면 문서와 폰 앱도 같이 바꾼다.
#include "hr_link.h"
#include "../config.h"
#include "control_task.h"

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#if defined(CONFIG_BLUEDROID_ENABLED)
#include <BLE2902.h>   // notify 구독 설명자. NimBLE 는 자동으로 만들어 주므로 Bluedroid 에서만 단다
#endif

// UUID — 폰 앱과 반드시 같아야 한다
static const char* SERVICE_UUID = "e3b70001-5c3a-4f6e-9a1d-2b8f0c4d7e10";
static const char* HR_IN_UUID   = "e3b70002-5c3a-4f6e-9a1d-2b8f0c4d7e10";   // 폰 → 조끼 심박
static const char* STATUS_UUID  = "e3b70003-5c3a-4f6e-9a1d-2b8f0c4d7e10";   // 조끼 → 폰 상태
static const char* CMD_UUID     = "e3b70004-5c3a-4f6e-9a1d-2b8f0c4d7e10";   // 폰 → 조끼 명령

static constexpr uint8_t HR_PROTO_VERSION     = 1;
static constexpr uint8_t STATUS_PROTO_VERSION = 1;
static constexpr uint8_t FLAG_VALID           = 0x01;
static constexpr size_t  STATUS_LEN           = 20;     // 기본 MTU(23) - 3 = 20 → MTU 협상 없이 한 번에 간다
static constexpr uint32_t STATUS_PERIOD_MS    = 1000;

// ----------------------------------------------------------------------------
//  공유 상태 — BLE 태스크(쓰기) ↔ 다른 태스크(읽기). s_lock 으로 보호.
// ----------------------------------------------------------------------------
static portMUX_TYPE s_lock = portMUX_INITIALIZER_UNLOCKED;
static bool     s_everValid  = false;
static bool     s_fresh      = false;
static uint8_t  s_rejoin     = 0;     // 끊긴 뒤 연속으로 받은 유효 샘플 수
static uint8_t  s_bpm        = 0;
static uint32_t s_lastValidMs = 0;
static uint32_t s_rx = 0, s_bad = 0;
static uint8_t  s_conn = 0;
static uint32_t s_statusSent = 0, s_cmdOk = 0, s_cmdBad = 0;

static BLECharacteristic* s_statusChar = nullptr;

// 락 안에서만 부른다: 오래 끊겼으면 fresh 를 내리고 재합류 카운터를 0 으로
static void expireLocked(uint32_t now) {
  if (s_everValid && (now - s_lastValidMs) > cfg::HR_STALE_MS) {
    s_fresh = false;
    s_rejoin = 0;
  }
}

static void onPacket(const uint8_t* p, size_t len) {
  const uint32_t now = millis();
  const bool ok = len >= 4 && p[0] == HR_PROTO_VERSION && (p[2] & FLAG_VALID) &&
                  p[1] >= cfg::HR_MIN_BPM && p[1] <= cfg::HR_MAX_BPM;

  portENTER_CRITICAL(&s_lock);
  s_rx++;
  if (!ok) {
    s_bad++;
  } else {
    expireLocked(now);             // 이번 값이 오기 전에 이미 끊겨 있었는지 먼저 정리
    s_bpm = p[1];
    s_lastValidMs = now;
    s_everValid = true;
    if (!s_fresh && ++s_rejoin >= cfg::HR_REJOIN_SAMPLES) s_fresh = true;
  }
  portEXIT_CRITICAL(&s_lock);
}

// 앱 명령: i 가압 / d 배기 / x 비상 배기 / r 잠금(LOCKOUT) 해제
//  11/26: 앱 개발자 모드에서 가압·잠금 해제를 하도록 i·r 추가 (시리얼 명령과 같은 집합)
static void onCommand(const uint8_t* p, size_t len) {
  const char c = len >= 1 ? static_cast<char>(p[0]) : 0;
  const bool ok = (c == 'i' || c == 'd' || c == 'x' || c == 'r');
  if (ok) ctlCommand(c);   // 큐에 넣기만 한다 (상태 머신이 다음 주기에 처리, 거부 사유는 '# EVT' 로 찍힘)
  portENTER_CRITICAL(&s_lock);
  if (ok) s_cmdOk++; else s_cmdBad++;
  portEXIT_CRITICAL(&s_lock);
}

// ----------------------------------------------------------------------------
//  BLE 콜백
// ----------------------------------------------------------------------------
class HrWriteCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override { onPacket(c->getData(), c->getLength()); }
};

class CmdWriteCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override { onCommand(c->getData(), c->getLength()); }
};

class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    portENTER_CRITICAL(&s_lock);
    s_conn++;
    portEXIT_CRITICAL(&s_lock);
    // 접속 뒤에도 광고를 계속한다 → 폰 앱을 다시 켜거나 두 번째 기기(시험용 nRF Connect)도 붙을 수 있게
    BLEDevice::startAdvertising();
  }
  void onDisconnect(BLEServer*) override {
    portENTER_CRITICAL(&s_lock);
    if (s_conn) s_conn--;
    portEXIT_CRITICAL(&s_lock);
    // 끊기면 광고를 다시 켜서 폰이 재접속할 수 있게 한다 (안 하면 재부팅 전까지 못 붙음)
    BLEDevice::startAdvertising();
  }
};

// ----------------------------------------------------------------------------
//  상태 패킷 (20 바이트, 리틀 엔디언) — docs/ble_protocol.md 3절
// ----------------------------------------------------------------------------
static inline void put16(uint8_t* p, uint32_t v) {
  if (v > 0xFFFF) v = 0xFFFF;                       // 넘치면 최대값으로 (값이 돌아가 작아 보이는 일 방지)
  p[0] = static_cast<uint8_t>(v & 0xFF);
  p[1] = static_cast<uint8_t>(v >> 8);
}

static uint8_t hrStateCode(HrState s) { return static_cast<uint8_t>(s); }   // 0 none 1 stale 2 rejoining 3 fresh

static bool buildStatus(uint8_t* p, uint8_t seq) {
  CtlStatus c;
  if (!ctlGetStatus(c)) return false;
  const HrStatus h = hrLinkGet();

  uint8_t flags = 0;
  if (c.pump)          flags |= 1 << 0;
  if (c.valveOpen)     flags |= 1 << 1;
  if (c.armed)         flags |= 1 << 2;
  if (c.overload)      flags |= 1 << 3;
  if (c.breathValid)   flags |= 1 << 4;
  if (c.breathHealthy) flags |= 1 << 5;
  if (c.apnea)         flags |= 1 << 6;
  if (c.framesOk)      flags |= 1 << 7;

  p[0] = STATUS_PROTO_VERSION;
  p[1] = seq;
  p[2] = static_cast<uint8_t>(c.state);             // 0 boot_vent 1 idle 2 inflate 3 hold 4 deflate 5 cooldown 6 emergency 7 lockout
  p[3] = flags;
  p[4] = c.score;
  p[5] = hrStateCode(h.state);
  p[6] = h.usable() ? h.bpm : 0;
  put16(p + 7,  c.breathValid ? static_cast<uint32_t>(c.breathRate * 10.0f + 0.5f) : 0);   // 회/분 ×10
  put16(p + 9,  c.breathValid ? static_cast<uint32_t>(c.ieRatio * 100.0f + 0.5f) : 0);     // I:E ×100
  put16(p + 11, c.gripL);
  put16(p + 13, c.gripR);
  put16(p + 15, c.compMaxMv);
  put16(p + 17, c.inStateMs / 1000);
  p[19] = c.cycles > 255 ? 255 : static_cast<uint8_t>(c.cycles);
  return true;
}

// 1 초마다 상태를 갱신. 접속한 폰이 있으면 notify, 없어도 값은 갱신해 둔다 (read 로도 읽을 수 있게).
// 제어 태스크보다 낮은 우선순위 → BLE 가 느려져도 공압 안전 판단에는 영향이 없다.
static void statusTask(void*) {
  uint8_t seq = 0;
  uint8_t pkt[STATUS_LEN];
  TickType_t next = xTaskGetTickCount();
  for (;;) {
    vTaskDelayUntil(&next, pdMS_TO_TICKS(STATUS_PERIOD_MS));
    if (!s_statusChar || !buildStatus(pkt, seq)) continue;
    s_statusChar->setValue(pkt, STATUS_LEN);
    portENTER_CRITICAL(&s_lock);
    const uint8_t conn = s_conn;
    portEXIT_CRITICAL(&s_lock);
    if (conn) {
      s_statusChar->notify();
      portENTER_CRITICAL(&s_lock);
      s_statusSent++;
      portEXIT_CRITICAL(&s_lock);
    }
    seq++;
  }
}

// ----------------------------------------------------------------------------
void hrLinkBegin() {
  if (!cfg::ENABLE_HR_BLE) return;

  BLEDevice::init(cfg::BLE_DEVICE_NAME);
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCb());

  BLEService* svc = server->createService(SERVICE_UUID);

  BLECharacteristic* hrIn = svc->createCharacteristic(
      HR_IN_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  hrIn->setCallbacks(new HrWriteCb());

  s_statusChar = svc->createCharacteristic(
      STATUS_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
#if defined(CONFIG_BLUEDROID_ENABLED)
  s_statusChar->addDescriptor(new BLE2902());   // 이게 없으면 폰이 notify 를 켤 수 없다
#endif

  BLECharacteristic* cmd = svc->createCharacteristic(
      CMD_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  cmd->setCallbacks(new CmdWriteCb());

  svc->start();

  // 광고 패킷은 31바이트 한도 → 128비트 서비스 UUID 는 광고에, 이름은 스캔 응답에 나눠 싣는다
  BLEAdvertisementData advData;
  advData.setFlags(0x06);                                // LE General Discoverable, BR/EDR 미지원
  advData.setCompleteServices(BLEUUID(SERVICE_UUID));
  BLEAdvertisementData scanResp;
  scanResp.setName(cfg::BLE_DEVICE_NAME);

  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->setAdvertisementData(advData);
  adv->setScanResponseData(scanResp);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();

  // 우선순위 1: 전송(2)·센싱(3)·제어(4) 보다 낮다
  xTaskCreatePinnedToCore(statusTask, "ble_stat", 4096, nullptr, 1, nullptr, cfg::SENSE_CORE);
}

HrStatus hrLinkGet() {
  HrStatus s{};
  const uint32_t now = millis();

  portENTER_CRITICAL(&s_lock);
  expireLocked(now);
  s.bpm = s_bpm;
  s.ageMs = s_everValid ? (now - s_lastValidMs) : UINT32_MAX;
  s.connections = s_conn;
  s.rxCount = s_rx;
  s.badCount = s_bad;
  if (!s_everValid)  s.state = HrState::None;
  else if (s_fresh)  s.state = HrState::Fresh;
  else if (s.ageMs <= cfg::HR_STALE_MS) s.state = HrState::Rejoining;
  else               s.state = HrState::Stale;
  portEXIT_CRITICAL(&s_lock);
  return s;
}

AppLinkStats appLinkStats() {
  AppLinkStats a;
  portENTER_CRITICAL(&s_lock);
  a.statusSent = s_statusSent;
  a.cmdAccepted = s_cmdOk;
  a.cmdRejected = s_cmdBad;
  portEXIT_CRITICAL(&s_lock);
  return a;
}

const char* hrStateName(HrState s) {
  switch (s) {
    case HrState::None:      return "none";
    case HrState::Stale:     return "stale";
    case HrState::Rejoining: return "rejoining";
    case HrState::Fresh:     return "fresh";
  }
  return "?";
}
