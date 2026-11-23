package com.embrace.watchhr

import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * 서비스(심박·위치·폰 연결) → 화면(MainActivity) 으로 상태 글자를 넘기는 가장 단순한 통로.
 * 화면이 꺼져 있거나 앱이 닫혀 있으면 listener 가 null 이고, 마지막 값만 기억해 둔다.
 */
object StatusBus {
    private val main = Handler(Looper.getMainLooper())

    @Volatile var hrText: String = "심박: -"
        private set
    @Volatile var linkText: String = "폰: 대기"
        private set
    @Volatile var locText: String = "위치: -"
        private set

    var listener: (() -> Unit)? = null

    fun hr(bpm: Int, valid: Boolean) {
        hrText = if (valid) "심박: $bpm bpm" else "심박: 측정 준비 중"
        notifyUi()
    }

    fun link(text: String) {
        linkText = "폰: $text"
        notifyUi()
    }

    fun loc(lat: Double, lon: Double, accM: Double?) {
        val acc = if (accM != null) String.format(Locale.US, " ±%.0fm", accM) else ""
        locText = String.format(Locale.US, "위치: %.5f, %.5f%s", lat, lon, acc)
        notifyUi()
    }

    fun locStatus(text: String) {
        locText = text
        notifyUi()
    }

    private fun notifyUi() = main.post { listener?.invoke() }
}
