package com.example.embraceapp.data

import com.example.embraceapp.model.AlertLevel
import com.example.embraceapp.model.VestStatus
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ───────── EMBRACE 백엔드 HTTP 호출 ─────────
// 규격: 팀 저장소 embrace-backend 브랜치의 API.md (v2).
// 모든 함수는 네트워크를 쓰므로 반드시 백그라운드(Dispatchers.IO)에서 부를 것. 실패하면 null / false.
object ServerApi {

    val configured: Boolean
        get() = AppConfig.SERVER_URL.isNotBlank() && AppConfig.API_KEY.isNotBlank()

    // ── 공통 ──
    private fun request(method: String, path: String, body: JSONObject? = null): String? {
        if (!configured) return null
        return try {
            val conn = URL(AppConfig.SERVER_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("X-API-Key", AppConfig.API_KEY)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            conn.disconnect()
            if (code in 200..299) text else null
        } catch (e: Exception) {
            null // 서버가 꺼져 있거나 인터넷이 없을 때: 다음 주기에 다시 시도
        }
    }

    private fun q(v: String) = URLEncoder.encode(v, "UTF-8")

    // ── 아동용 폰 → 서버 ──
    fun postVestStatus(childId: String, v: VestStatus, ts: Long): Boolean = request(
        "POST", "/api/vest/status", JSONObject()
            .put("child_id", childId).put("seq", v.seq).put("state", v.stateCode)
            .put("pump", v.pump).put("valve_open", v.valveOpen).put("armed", v.armed)
            .put("overload", v.overload).put("breath_valid", v.breathValid)
            .put("breath_healthy", v.breathHealthy).put("apnea", v.apnea).put("frames_ok", v.framesOk)
            .put("score", v.score).put("hr_state", v.hrState).put("bpm", v.bpm)
            .put("breath_rate", v.breathRate.toDouble()).put("ie_ratio", v.ieRatio.toDouble())
            .put("grip_l", v.gripL).put("grip_r", v.gripR).put("comp_max_mv", v.compMaxMv)
            .put("in_state_sec", v.inStateSec).put("cycles", v.cycles).put("ts", ts)
    ) != null

    fun postHeart(childId: String, bpm: Int, valid: Boolean, source: String, ts: Long): Boolean = request(
        "POST", "/api/heart", JSONObject()
            .put("child_id", childId).put("bpm", bpm.coerceIn(0, 255)).put("valid", valid)
            .put("source", source).put("ts", ts)
    ) != null

    fun postLocation(childId: String, lat: Double, lon: Double, accM: Double?, ts: Long): Boolean {
        val body = JSONObject().put("child_id", childId).put("lat", lat).put("lon", lon).put("ts", ts)
        if (accM != null) body.put("acc_m", accM)
        return request("POST", "/api/location", body) != null
    }

    fun postAlert(childId: String, level: AlertLevel, code: String, title: String, message: String, ts: Long): Boolean = request(
        "POST", "/api/alerts", JSONObject()
            .put("child_id", childId).put("level", levelName(level)).put("code", code)
            .put("title", title).put("message", message).put("ts", ts)
    ) != null

    // 보호자가 보낸 원격 배기 명령이 있으면 true (한 번 가져가면 서버에서 지워짐)
    fun pollExhaustCommand(childId: String): Boolean {
        val text = request("GET", "/api/command/next?child_id=${q(childId)}") ?: return false
        return try { JSONObject(text).optString("action") == "exhaust" } catch (e: Exception) { false }
    }

    // ── 보호자 앱 ← 서버 ──
    fun requestExhaust(childId: String): Boolean = request(
        "POST", "/api/command", JSONObject().put("child_id", childId).put("action", "exhaust")
    ) != null

    fun getSummary(childId: String): JSONObject? =
        request("GET", "/api/summary?child_id=${q(childId)}")?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun getAlerts(childId: String, afterId: Long): JSONArray? =
        request("GET", "/api/alerts?child_id=${q(childId)}&after_id=$afterId")?.let { runCatching { JSONArray(it) }.getOrNull() }

    // ── 변환 ──
    fun levelName(l: AlertLevel) = when (l) {
        AlertLevel.Danger -> "danger"; AlertLevel.Warning -> "warning"; AlertLevel.Stable -> "stable"
    }

    fun levelOf(s: String) = when (s) {
        "danger" -> AlertLevel.Danger; "warning" -> AlertLevel.Warning; else -> AlertLevel.Stable
    }

    // 서버의 조끼 상태 JSON → 앱 VestStatus
    fun vestFromJson(o: JSONObject) = VestStatus(
        stateCode = o.optInt("state"), pump = o.optBoolean("pump"), valveOpen = o.optBoolean("valve_open"),
        armed = o.optBoolean("armed"), overload = o.optBoolean("overload"),
        breathValid = o.optBoolean("breath_valid"), breathHealthy = o.optBoolean("breath_healthy"),
        apnea = o.optBoolean("apnea"), framesOk = o.optBoolean("frames_ok"),
        score = o.optInt("score"), hrState = o.optInt("hr_state"), bpm = o.optInt("bpm"),
        breathRate = o.optDouble("breath_rate", 0.0).toFloat(), ieRatio = o.optDouble("ie_ratio", 0.0).toFloat(),
        gripL = o.optInt("grip_l"), gripR = o.optInt("grip_r"), compMaxMv = o.optInt("comp_max_mv"),
        inStateSec = o.optInt("in_state_sec"), cycles = o.optInt("cycles"), seq = o.optInt("seq")
    )
}
