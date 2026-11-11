package com.embrace.watchhr

import android.os.Handler
import android.os.Looper

/**
 * 서비스(심박·BLE) → 화면(MainActivity) 으로 상태 글자를 넘기는 가장 단순한 통로.
 * 화면이 꺼져 있거나 앱이 닫혀 있으면 listener 가 null 이고, 마지막 값만 기억해 둔다.
 */
object StatusBus {
    private val main = Handler(Looper.getMainLooper())

    @Volatile var hrText: String = "심박: -"
        private set
    @Volatile var linkText: String = "조끼: 대기"
        private set

    var listener: (() -> Unit)? = null

    fun hr(bpm: Int, valid: Boolean) {
        hrText = if (valid) "심박: $bpm bpm" else "심박: 측정 준비 중"
        notifyUi()
    }

    fun link(text: String) {
        linkText = "조끼: $text"
        notifyUi()
    }

    private fun notifyUi() = main.post { listener?.invoke() }
}
