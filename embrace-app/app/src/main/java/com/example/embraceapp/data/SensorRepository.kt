package com.example.embraceapp.data

import com.example.embraceapp.model.*
import com.example.embraceapp.R

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────── 센서 / 알림 데이터 관리 ─────────
// 앱 전체에서 하나만 쓰는 데이터 보관소입니다.
// 화면은 여기 값을 "읽기만" 하고, 값이 바뀌면 화면이 자동으로 다시 그려져요.
// 나중에 MQTT로 값을 받으면 아래 update~ / addAlert 함수만 호출하면 됩니다.
object SensorRepository {

    private val _state = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = _state.asStateFlow()

    private val _alerts = MutableStateFlow<List<AlertItem>>(emptyList()) // 처음엔 비어 있음. 서버에서 알림이 오면 addAlert로 추가
    val alerts: StateFlow<List<AlertItem>> = _alerts.asStateFlow()

    // ── 센서 값 갱신 (MQTT 수신 시 호출) ──

    // 생체 정보: 바꿀 값만 넣으면 됨 (예: updateVitals(heartRate = 90))
    fun updateVitals(heartRate: Int? = null, breathRate: Int? = null, vestPressure: Int? = null) {
        _state.update { s ->
            s.copy(
                vitals = s.vitals.copy(
                    heartRate = heartRate ?: s.vitals.heartRate,
                    breathRate = breathRate ?: s.vitals.breathRate,
                    vestPressure = vestPressure ?: s.vitals.vestPressure
                )
            )
        }
    }

    // 호흡 그래프 점 목록과 호기:흡기 비율
    fun updateBreath(series: List<Float>, ieRatio: Float) {
        _state.update { it.copy(breathSeries = series, ieRatio = ieRatio) }
    }

    // 연결된 기기 목록 (배터리 포함)
    fun updateDevices(devices: List<DeviceInfo>) {
        _state.update { it.copy(devices = devices) }
    }

    // ── 알림 ──

    // 새 알림 추가 (안 읽음 상태로 들어가서 벨에 숫자가 생김)
    fun addAlert(level: AlertLevel, title: String, message: String, location: String? = null) {
        val now = SimpleDateFormat("HH:mm - M월 d일", Locale.KOREA).format(Date())
        val item = AlertItem(
            id = System.currentTimeMillis(), level = level, title = title, message = message,
            time = now, day = "오늘", location = location, read = false
        )
        _alerts.update { listOf(item) + it }
    }

    // 알림 화면을 열면 전부 읽음 처리 (벨의 숫자 사라짐)
    fun markAllRead() {
        _alerts.update { list -> list.map { it.copy(read = true) } }
    }

    // 알림 전부 지우기 (필요할 때 호출)
    fun clearAlerts() {
        _alerts.value = emptyList()
    }
}