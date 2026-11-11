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
 * 화면 하나: 심박 / 조끼 연결 상태 / 시작·중지 버튼.
 * 레이아웃 XML 없이 코드로 만든다 → 템플릿이 무엇이든(Compose 든 아니든) 그대로 동작.
 */
class MainActivity : Activity() {

    private lateinit var hrView: TextView
    private lateinit var linkView: TextView
    private lateinit var button: Button

    // Wear OS 6(API 36) 부터 심박 권한 이름이 바뀌었다
    private val requiredPermissions: Array<String>
        get() = buildList {
            add(
                if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE"
                else Manifest.permission.BODY_SENSORS
            )
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hrView = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        linkView = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY) }
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
        val missing = requiredPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startHr() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        // 알림 권한은 없어도 동작한다. 나머지(심박·블루투스)는 꼭 필요하다.
        val essentialDenied = perms.indices.any {
            perms[it] != Manifest.permission.POST_NOTIFICATIONS &&
                results[it] != PackageManager.PERMISSION_GRANTED
        }
        if (essentialDenied) StatusBus.link("권한이 필요합니다 (설정 > 애플리케이션)")
        else startHr()
    }

    private fun startHr() {
        HrService.start(this)
        button.postDelayed({ render() }, 300)
    }
}
