package com.embrace.watchhr

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/**
 * 워치 → 아동용 폰 앱 전송 (Wear OS Data Layer, MessageClient).
 *
 *   [워치] HrService ──심박──▶ PhoneLink     ──블루투스(Data Layer)──▶ [폰] 아동용 앱
 *                                                                         └─▶ 조끼 ESP (BLE, 폰 앱 담당)
 *
 * 왜 BLE GATT 를 직접 안 쓰고 Data Layer 인가:
 *   워치와 폰은 Galaxy Wearable 앱으로 이미 블루투스 페어링되어 있다. Data Layer 는 그 연결을
 *   그대로 쓰므로 스캔·접속·재접속 코드가 필요 없고, 폰 앱이 꺼져 있어도 시스템이 앱을 깨워 전달한다.
 *
 * ⚠ 전달 조건 (안 지키면 sendMessage 는 "성공" 인데 폰에 아무것도 안 온다)
 *   1) 워치 앱과 폰 앱의 applicationId 가 같아야 한다  (build.gradle.kts)
 *   2) 두 앱이 같은 서명 키로 빌드되어야 한다       (같은 PC 의 debug 빌드면 자동으로 같음)
 *   3) 폰 앱에 WearableListenerService 가 있어야 한다  (phone-snippet/ 참고)
 *
 * 형식: UTF-8 JSON 한 개 = 메시지 한 개. 자세한 표는 docs/ble_protocol.md 1절.
 *   /embrace/hr   {"type":"hr","v":1,"bpm":72,"valid":true,"seq":12,"ts":1732240000000}
 *   (/embrace/loc 위치는 v2.1(11/24)에서 삭제 — 아동용 폰이 자기 GPS 로 직접 잡는다)
 *
 * 모든 상태 변경은 메인 스레드에서만 한다. Google Play services 의 Task 리스너는
 * 실행기를 지정하지 않으면 메인 스레드에서 불리므로 락이 필요 없다.
 */
class PhoneLink(ctx: Context) {

    companion object {
        const val PATH_HR = "/embrace/hr"
        private const val NODE_REFRESH_MS = 30_000L   // 폰 목록 다시 확인 주기
    }

    private val main = Handler(Looper.getMainLooper())
    private val battery = ctx.getSystemService(android.os.BatteryManager::class.java)
    private val nodeClient = Wearable.getNodeClient(ctx)
    private val msgClient = Wearable.getMessageClient(ctx)

    private var running = false
    private var nodes: List<Node> = emptyList()
    private var hrSeq = 0
    private var okCount = 0
    private var failCount = 0

    fun start() = main.post {
        running = true
        refreshNodes()
    }

    fun stop() = main.post {
        running = false
        main.removeCallbacksAndMessages(null)
        nodes = emptyList()
        StatusBus.link("중지")
    }

    fun sendHr(bpm: Int, valid: Boolean) = main.post {
        val json = JSONObject()
            .put("type", "hr").put("v", 1)
            .put("bpm", bpm).put("valid", valid)
            .put("seq", hrSeq and 0xFF)
            .put("ts", System.currentTimeMillis())
        // v2.2: 워치 배터리 % (폰 기기 관리 탭 표시용, 폰이 모르면 무시)
        battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }?.let { json.put("batt", it) }
        hrSeq++
        send(PATH_HR, json)
    }

    // ------------------------------------------------------------------ 내부
    private fun send(path: String, json: JSONObject) {
        if (!running || nodes.isEmpty()) return   // 폰이 없으면 버린다 (다음 값이 곧 온다)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        for (n in nodes) {
            msgClient.sendMessage(n.id, path, bytes)
                .addOnSuccessListener {
                    okCount++
                    StatusBus.link("${n.displayName} 전송 $okCount 회")
                }
                .addOnFailureListener { e ->
                    failCount++
                    StatusBus.link("전송 실패 $failCount 회: ${e.message}")
                    refreshNodes()   // 폰이 멀어졌을 수 있다 → 목록 다시
                }
        }
    }

    private fun refreshNodes() {
        if (!running) return
        main.removeCallbacks(refreshTick)
        nodeClient.connectedNodes
            .addOnSuccessListener { list ->
                // isNearby = 블루투스로 직접 붙은 폰. 없으면 클라우드 경유 노드라도 쓴다.
                nodes = list.filter { it.isNearby }.ifEmpty { list }
                StatusBus.link(
                    if (nodes.isEmpty()) "폰 없음 (워치-폰 블루투스 확인)"
                    else "폰 찾음: ${nodes.joinToString { it.displayName }}"
                )
            }
            .addOnFailureListener { e ->
                nodes = emptyList()
                StatusBus.link("폰 확인 실패: ${e.message}")
            }
            .addOnCompleteListener {
                if (running) main.postDelayed(refreshTick, NODE_REFRESH_MS)
            }
    }

    private val refreshTick = Runnable { refreshNodes() }
}
