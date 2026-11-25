package com.example.embraceapp.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ───────── 워치 연결 상태 (기기 관리 탭 표시용) ─────────
// 워치는 BLE "연결" 버튼으로 붙이는 기기가 아니다.
// Galaxy Wearable 앱으로 폰과 페어링되면 Wear OS Data Layer 가 알아서 연결되고,
// 워치 앱(EMBRACE HR)에서 [시작]을 누르면 심박이 WatchReceiver 로 들어온다.
// 여기서는 (1) 폰과 연결된 워치가 있는지, (2) 워치 앱에서 메시지가 실제로 오는지만 확인한다.
object WatchLink {
    private val _nodeName = MutableStateFlow<String?>(null) // 연결된 워치 이름 (없으면 null)
    val nodeName: StateFlow<String?> = _nodeName.asStateFlow()

    private val _checked = MutableStateFlow(false) // 한 번이라도 확인했는지
    val checked: StateFlow<Boolean> = _checked.asStateFlow()

    private val _msgCount = MutableStateFlow(0) // 워치 앱에서 받은 메시지 수
    val msgCount: StateFlow<Int> = _msgCount.asStateFlow()

    // [연결 해제] 를 누르면 true → 워치 심박을 받지도, 조끼로 넘기지도 않는다.
    // (워치와 폰의 블루투스 페어링 자체는 Galaxy Wearable 앱이 관리하므로 여기서 끊지 않는다)
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun disconnect() { _paused.value = true }
    fun connect(ctx: Context) { _paused.value = false; refresh(ctx) }

    // 폰과 연결된 워치 목록 확인 (결과는 nodeName 으로)
    fun refresh(ctx: Context) {
        Wearable.getNodeClient(ctx.applicationContext).connectedNodes
            .addOnSuccessListener { list ->
                val n = list.firstOrNull { it.isNearby } ?: list.firstOrNull()
                _nodeName.value = n?.displayName
                _checked.value = true
            }
            .addOnFailureListener {
                _nodeName.value = null // Google Play 서비스/Wear OS 앱이 없는 폰
                _checked.value = true
            }
    }

    // WatchReceiver 가 메시지를 받을 때마다 호출
    fun onMessage(@Suppress("UNUSED_PARAMETER") nodeId: String) {
        _msgCount.value = _msgCount.value + 1
    }
}
