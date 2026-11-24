package com.embrace.watchhr

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 화면 하나: 심박 / 폰 연결 상태 / 시작·중지 버튼. (위치는 v2.1 에서 삭제 — 폰이 직접 잡음)
 * 레이아웃 XML 없이 코드로 만든다 → 템플릿이 무엇이든(Compose 든 아니든) 그대로 동작.
 */
class MainActivity : Activity() {

    private lateinit var hrView: TextView
    private lateinit var linkView: TextView
    private lateinit var button: Button

    // Wear OS 6(API 36) 부터 심박 권한 이름이 바뀌었다
    private val hrPermission: String
        get() = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE"
                else Manifest.permission.BODY_SENSORS

    // 꼭 필요한 것 = 심박. 알림은 없어도 심박 전송은 된다.
    private val requestedPermissions: Array<String>
        get() = arrayOf(
            hrPermission,
            Manifest.permission.POST_NOTIFICATIONS,
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hrView = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        linkView = TextView(this).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY) }
        button = Button(this).apply { setOnClickListener { onButton() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(hrView)
            addView(linkView)
            addView(button)
        })
    }

    override fun onResume() {
        super.onResume()
        StatusBus.listener = ::render
        render()
    }

    override fun onPause() {
        StatusBus.listener = null
        super.onPause()
    }

    private fun render() {
        hrView.text = StatusBus.hrText
        linkView.text = StatusBus.linkText
        button.text = if (HrService.isRunning) "중지" else "시작"
    }

    private fun onButton() {
        if (HrService.isRunning) {
            HrService.stop(this)
            button.postDelayed({ render() }, 300)
            return
        }
        val missing = requestedPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startHr() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(hrPermission) != PackageManager.PERMISSION_GRANTED) {
            StatusBus.link("심박 권한이 필요합니다 (설정 > 애플리케이션)")
            return
        }
        startHr()
    }

    private fun startHr() {
        HrService.start(this)
        button.postDelayed({ render() }, 300)
    }
}
