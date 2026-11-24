package com.example.embraceapp.wear

import com.example.embraceapp.ble.VestConnection
import com.example.embraceapp.data.SensorRepository
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

// ───────── 워치에서 오는 값 받기 ─────────
// 워치 앱(PhoneLink.kt)이 Wear OS Data Layer 로 보내는 메시지를 받습니다.
// 메시지 한 개 = UTF-8 JSON 한 개. 규격은 팀 저장소 docs/ble_protocol.md 1절.
//   /embrace/hr   {"type":"hr","v":1,"bpm":72,"valid":true,"seq":12,"ts":...}
//   /embrace/loc  → 2026-11-23 부터 무시. 위치는 아동 폰이 직접 잡는다 (location/ChildLocationTracker.kt).
//                   워치 위치까지 받으면 폰 GPS 값과 번갈아 덮어써서 보호자 지도 핀이 튄다.
//
// ⚠ 이 서비스가 메시지를 받으려면 워치 앱과 이 앱의 applicationId, 서명 키가 같아야 합니다.
class WatchReceiver : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val json = try { JSONObject(String(event.data, Charsets.UTF_8)) } catch (e: Exception) { return }
        if (json.optInt("v", 1) != 1) return // 모르는 버전은 무시

        when (event.path) {
            "/embrace/hr" -> {
                val bpm = json.optInt("bpm", 0)
                val valid = json.optBoolean("valid", false)
                val batt = json.optInt("batt", -1).takeIf { it in 0..100 } // 워치 배터리 (v2.2 부터)
                SensorRepository.updateWatchHr(bpm, valid, battery = batt) // 홈 화면 심박수
                WatchLink.onMessage(event.sourceNodeId)
                VestConnection.sendHeartRate(bpm, valid)   // 조끼가 연결돼 있으면 그대로 전달 (범위 검사는 조끼가 함)
            }
            "/embrace/loc" -> Unit // 무시 (위 설명)
        }
    }
}
