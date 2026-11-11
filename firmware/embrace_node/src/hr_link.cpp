// hr_link.cpp — 워치 심박 BLE 수신
//
//  구조
//    워치 앱(BLE 클라이언트) ──write 4바이트, 1초마다──▶ ESP(BLE 서버) HR_IN 특성
//    onWrite 콜백(BLE 태스크, 코어 0) 에서 검사 후 공유 변수에 저장
//    hrLinkGet() (센싱·판단·전송 태스크, 코어 1) 이 락을 잡고 복사해 간다
//
//  패킷 형식 (docs/ble_hr_protocol.md 와 1:1 대응)
//    [0] version = 1
//    [1] bpm     (0 = 아직 모름)
//    [2] flags   bit0 = 워치가 "심박 측정 가능(ACQUIRED)" 이라고 판단
//    [3] seq     0~255 순환. 유실 확인용
//
//  안전 관련 설계
//    - 이 모듈은 공압을 직접 건드리지 않는다. 심박은 판단의 "보조 근거" 일 뿐이다.
//    - 인증 없이 아무 기기나 write 할 수 있다 (MVP 단계). 그래서 범위 검사를 하고,
//      심박 하나만으로 가압이 시작되지 않게 판단 모듈에서 막는다 (Step 2).
#include "hr_link.h"
#include "../config.h"

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

// UUID — 워치 앱(HrBleSender.kt)·폰 앱과 반드시 같아야 한다
static const char* SERVICE_UUID = "e3b70001-5c3a-4f6e-9a1d-2b8f0c4d7e10";
static const char* HR_IN_UUID   = "e3b70002-5c3a-4f6e-9a1d-2b8f0c4d7e10";

static constexpr uint8_t PROTO_VERSION = 1;
static constexpr uint8_t FLAG_VALID    = 0x01;

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

// 락 안에서만 부른다: 오래 끊겼으면 fresh 를 내리고 재합류 카운터를 0 으로
static void expireLocked(uint32_t now) {
  if (s_everValid && (now - s_lastValidMs) > cfg::HR_STALE_MS) {
    s_fresh = false;
    s_rejoin = 0;
  }
}

static void onPacket(const uint8_t* p, size_t len) {
  const uint32_t now = millis();
  const bool ok = len >= 4 && p[0] == PROTO_VERSION && (p[2] & FLAG_VALID) &&
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

// ----------------------------------------------------------------------------
//  BLE 콜백
// ----------------------------------------------------------------------------
class HrWriteCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    onPacket(c->getData(), c->getLength());
  }
};

class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    portENTER_CRITICAL(&s_lock);
    s_conn++;
    portEXIT_CRITICAL(&s_lock);
    // 워치가 붙어도 광고를 계속한다 → 나중에 폰 앱도 동시에 접속할 수 있게
    BLEDevice::startAdvertising();
  }
  void onDisconnect(BLEServer*) override {
    portENTER_CRITICAL(&s_lock);
    if (s_conn) s_conn--;
    portEXIT_CRITICAL(&s_lock);
    // 끊기면 광고를 다시 켜서 워치가 재접속할 수 있게 한다 (안 하면 재부팅 전까지 못 붙음)
    BLEDevice::startAdvertising();
  }
};

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

const char* hrStateName(HrState s) {
  switch (s) {
    case HrState::None:      return "none";
    case HrState::Stale:     return "stale";
    case HrState::Rejoining: return "rejoining";
    case HrState::Fresh:     return "fresh";
  }
  return "?";
}
