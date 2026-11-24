package com.example.embraceapp.data

import android.content.Context
import com.example.embraceapp.ble.VestConnection
import com.example.embraceapp.ble.VestLink
import com.example.embraceapp.location.ChildLocationTracker
import com.example.embraceapp.model.AlertItem
import com.example.embraceapp.model.AlertLevel
import com.example.embraceapp.model.UserProfile
import com.example.embraceapp.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ───────── 서버 동기화 (HTTP) ─────────
// 로그인한 사람의 역할(role)에 따라 둘 중 하나만 돈다.
//  · 자녀(child) 폰 : 조끼 상태·워치 심박·폰 GPS 위치·알림을 서버로 올리고, 보호자의 원격 배기 명령을 받아 조끼로 전달
//  · 보호자(guardian) 폰 : 서버에서 자녀의 최신 값과 새 알림을 받아 화면에 반영
//
// 자녀 구분 키(child_id) = 자녀 전화번호의 숫자만 (예: "01012345678").
//  - 자녀 폰은 자기 phone, 보호자 폰은 가입 때 적은 partnerPhone 을 쓰므로 양쪽이 자동으로 같은 값이 된다.
//  - 그래서 가입할 때 보호자가 적은 자녀 번호와 자녀가 적은 자기 번호가 같아야 한다.
object ServerSync {
    private const val CHILD_PERIOD_MS = 3_000L     // 자녀 폰 업로드 주기
    private const val GUARDIAN_PERIOD_MS = 3_000L  // 보호자 폰 조회 주기
    private const val STALE_SEC = 15.0             // 이보다 오래된 서버 값은 "연결 끊김"으로 보고 표시하지 않음

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var runningFor: String? = null
    private var appCtx: Context? = null

    // 로그인 정보가 바뀔 때마다 호출 (MainActivity). 같은 사람이면 아무 일도 안 함
    fun onProfile(ctx: Context, p: UserProfile?) {
        appCtx = ctx.applicationContext
        val key = p?.let { "${it.role}:${childIdOf(it)}" }
        if (key == runningFor) return
        job?.cancel()
        runningFor = key
        if (p == null || !ServerApi.configured) return
        val childId = childIdOf(p)
        if (childId.isEmpty()) return
        job = scope.launch { if (p.role == "child") childLoop(childId) else guardianLoop(childId) }
    }

    fun childIdOf(p: UserProfile): String =
        (if (p.role == "child") p.phone else p.partnerPhone).filter { it.isDigit() }

    // 보호자 SOS → 서버에 원격 배기 요청. 서버에 전달됐으면 true
    suspend fun requestRemoteExhaust(): Boolean {
        val p = AuthRepository.profile.value ?: return false
        if (p.role == "child") return false
        val id = childIdOf(p).ifEmpty { return false }
        return withContext(Dispatchers.IO) { ServerApi.requestExhaust(id) }
    }

    // ── 자녀 폰 ──
    private suspend fun childLoop(childId: String) {
        var lastStatusCount = -1
        var lastHrAt = 0L
        var lastLocAt = 0L
        val uploadedAlerts = HashSet<Long>()
        while (scope.isActive) {
            val s = SensorRepository.state.value
            val now = System.currentTimeMillis()

            // 조끼 상태: 새로 받은 게 있을 때만
            val count = VestConnection.statusCount.value
            val vest = s.vest
            if (vest != null && count != lastStatusCount) {
                if (ServerApi.postVestStatus(childId, vest, now)) lastStatusCount = count
            }

            // 워치 심박
            val hr = s.watchHr
            if (hr != null && hr.receivedAt > lastHrAt) {
                if (ServerApi.postHeart(childId, hr.bpm, hr.valid, "watch", hr.receivedAt)) lastHrAt = hr.receivedAt
            }

            // 위치 (아동 폰 자신의 GPS — ChildLocationTracker). 권한을 나중에 허용했으면 여기서 켜진다
            appCtx?.let { ChildLocationTracker.ensureStarted(it) }
            val loc = s.childLocation
            if (loc != null && loc.receivedAt > lastLocAt) {
                if (ServerApi.postLocation(childId, loc.lat, loc.lon, loc.accuracyM, loc.receivedAt)) lastLocAt = loc.receivedAt
            }

            // 새 알림 (오래된 것부터)
            for (a in SensorRepository.alerts.value.reversed()) {
                if (a.id in uploadedAlerts) continue
                if (ServerApi.postAlert(childId, a.level, codeOf(a), a.title, a.message, a.id)) uploadedAlerts += a.id
                else break // 서버가 안 되면 다음 주기에
            }

            // 보호자의 원격 배기 명령 (조끼에 연결돼 있을 때만 가져감 → 못 보낼 명령을 지우지 않도록)
            if (VestConnection.link.value == VestLink.Connected && ServerApi.pollExhaustCommand(childId)) {
                val sent = withContext(Dispatchers.Main) { VestConnection.exhaust() }
                val (title, msg) = if (sent) "원격 비상 배기" to "보호자가 비상 배기를 요청해 조끼로 명령을 보냈어요."
                                   else "원격 비상 배기 실패" to "보호자가 비상 배기를 요청했지만 조끼로 보내지 못했어요."
                SensorRepository.addAlert(AlertLevel.Danger, title, msg)
                appCtx?.let { Notifier.send(it, title, msg) }
            }
            delay(CHILD_PERIOD_MS)
        }
    }

    // 앱 알림 제목 → 서버 code (API.md §1-2)
    private fun codeOf(a: AlertItem) = when (a.title) {
        "무호흡 감지" -> "apnea"; "과부하 징후" -> "overload"; "조끼 가압 시작" -> "inflate"
        "비상 배기" -> "emergency"; "조끼 잠금" -> "lockout"; "조끼 연결 끊김" -> "ble_lost"
        else -> if (a.title.contains("배기")) "sos" else "other"
    }

    // ── 보호자 폰 ──
    private suspend fun guardianLoop(childId: String) {
        var lastAlertId = -1L // -1 = 아직 처음: 예전 알림은 목록에만 넣고 푸시는 하지 않음
        while (scope.isActive) {
            val sum = ServerApi.getSummary(childId)
            if (sum != null) {
                val now = System.currentTimeMillis()
                fun fresh(key: String) = sum.optJSONObject(key)?.takeIf { it.optDouble("age_sec", 1e9) <= STALE_SEC }
                fun receivedAt(o: org.json.JSONObject) = now - (o.optDouble("age_sec", 0.0) * 1000).toLong()

                SensorRepository.setVestFromServer(fresh("vest")?.let { ServerApi.vestFromJson(it) })
                fresh("heart")?.let { h ->
                    if (h.optString("source") == "watch") SensorRepository.updateWatchHr(h.optInt("bpm"), h.optBoolean("valid"), receivedAt(h))
                }
                // 위치는 오래돼도 마지막 위치를 보여 준다 (받은 시각이 같이 표시됨)
                sum.optJSONObject("location")?.let { l ->
                    SensorRepository.updateChildLocation(
                        l.optDouble("lat"), l.optDouble("lon"),
                        if (l.isNull("acc_m")) null else l.optDouble("acc_m"), receivedAt(l)
                    )
                }

                val first = lastAlertId < 0
                ServerApi.getAlerts(childId, maxOf(lastAlertId, 0L))?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONObject(i)
                        val level = ServerApi.levelOf(a.optString("level"))
                        SensorRepository.addAlert(level, a.optString("title"), a.optString("message"))
                        if (!first && level != AlertLevel.Stable) appCtx?.let { Notifier.send(it, a.optString("title"), a.optString("message")) }
                        lastAlertId = maxOf(lastAlertId, a.optLong("id"))
                    }
                    if (lastAlertId < 0) lastAlertId = 0
                }
            }
            delay(GUARDIAN_PERIOD_MS)
        }
    }
}
