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
// 서버 업로드/조회는 ServerSync.kt 가 이 값들을 읽고 써서 처리합니다 (HTTP, MQTT 아님).
object SensorRepository {

    private val _state = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = _state.asStateFlow()

    private val _alerts = MutableStateFlow<List<AlertItem>>(emptyList()) // 처음엔 비어 있음. 서버에서 알림이 오면 addAlert로 추가
    val alerts: StateFlow<List<AlertItem>> = _alerts.asStateFlow()

    // ── 센서 값 갱신 ──

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

    // 워치 심박 (WatchReceiver 가 호출)
    fun updateWatchHr(bpm: Int, valid: Boolean, receivedAt: Long = System.currentTimeMillis(), battery: Int? = null) {
        _state.update { it.copy(watchHr = WatchHr(bpm, valid, receivedAt, battery ?: it.watchHr?.battery)) }
    }

    // 자녀 위치: 자녀 폰은 ChildLocationTracker(폰 GPS), 보호자 폰은 ServerSync(서버 값)가 호출
    fun updateChildLocation(lat: Double, lon: Double, accuracyM: Double?, receivedAt: Long = System.currentTimeMillis()) {
        _state.update { it.copy(childLocation = ChildLocation(lat, lon, accuracyM, receivedAt)) }
    }

    // 보호자 폰: 서버에서 받은 자녀 조끼 상태 반영 (알림은 서버 알림 목록으로 따로 받으므로 여기서 만들지 않음)
    fun setVestFromServer(v: VestStatus?) {
        _state.update { it.copy(vest = v) }
    }

    // 조끼 상태 갱신 (조끼 연결에서 1초마다 호출. 끊기면 null).
    // 이전 상태와 비교해서 "새로 생긴" 위험 상황만 알림으로 추가하고, onAlert 로 푸시 알림도 보냄
    fun updateVest(v: VestStatus?, onAlert: (String, String) -> Unit = { _, _ -> }) {
        val prev = _state.value.vest
        _state.update { it.copy(vest = v) }
        if (v == null) return
        fun fire(level: AlertLevel, title: String, msg: String) { addAlert(level, title, msg); onAlert(title, msg) }
        if (v.apnea && prev?.apnea != true) fire(AlertLevel.Danger, "무호흡 감지", "호흡이 멈춘 것으로 감지됐어요. 바로 확인해 주세요.")
        if (v.overload && prev?.overload != true) fire(AlertLevel.Warning, "과부하 징후", "과부하 징후가 감지됐어요.")
        if (v.stateCode == 2 && prev?.stateCode != 2) fire(AlertLevel.Warning, "조끼 가압 시작", "조끼가 압박을 시작했어요.")
        if (v.stateCode == 6 && prev?.stateCode != 6) fire(AlertLevel.Danger, "비상 배기", "조끼가 비상 배기를 실행했어요.")
        if (v.stateCode == 7 && prev?.stateCode != 7) fire(AlertLevel.Danger, "조끼 잠금", "자동 가압이 잠겼어요. 조끼 앞에서 직접 확인해 주세요.")
    }

    // 호흡 그래프 점 목록과 호기:흡기 비율
    fun updateBreath(series: List<Float>, ieRatio: Float) {
        _state.update { it.copy(breathSeries = series, ieRatio = ieRatio) }
    }

    // ── 알림 ──

    // 새 알림 추가 (안 읽음 상태로 들어가서 벨에 숫자가 생김)
    fun addAlert(level: AlertLevel, title: String, message: String, location: String? = null) {
        val now = SimpleDateFormat("HH:mm - M월 d일", Locale.KOREA).format(Date())
        val item = AlertItem(
            id = nextAlertId(), level = level, title = title, message = message,
            time = now, day = "오늘", location = location, read = false
        )
        _alerts.update { listOf(item) + it }
    }

    // 알림 id: 같은 밀리초에 여러 개가 생겨도 겹치지 않게 (서버 업로드 때 중복 판단에도 씀)
    private var lastAlertId = 0L
    @Synchronized private fun nextAlertId(): Long {
        lastAlertId = maxOf(System.currentTimeMillis(), lastAlertId + 1)
        return lastAlertId
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
