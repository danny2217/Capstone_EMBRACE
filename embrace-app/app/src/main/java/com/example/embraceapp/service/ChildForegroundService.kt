package com.example.embraceapp.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.embraceapp.MainActivity
import com.example.embraceapp.location.ChildLocationTracker

// ───────── 자녀 폰 백그라운드 유지 서비스 ─────────
// 왜 필요한가: 안드로이드는 화면이 꺼지거나 다른 앱으로 가면 몇 분 안에 앱을 멈추거나 죽인다.
//   그러면 ① 폰 GPS 수집 ② 서버 업로드(ServerSync) ③ 조끼 BLE 연결(VestConnection)이 전부 멈춘다.
//   "포그라운드 서비스" = 상단에 알림을 하나 띄워 두는 대신 안드로이드가 앱을 살려 두는 공식 방법.
//
// 이 서비스가 직접 하는 일은 거의 없다. 프로세스를 살려 두기만 하면
//   ChildLocationTracker / ServerSync / VestConnection 은 지금 코드 그대로 계속 돈다 (셋 다 앱 전체 싱글톤).
//
// 언제 켜지나: 자녀 계정 로그인 후, 앱이 화면에 떠 있을 때 MainActivity 가 start() 를 부른다.
//   (안드로이드 14+ 는 위치 포그라운드 서비스를 "앱이 화면에 있을 때"만 시작하게 허용한다)
//   조끼에 새로 연결되면 다시 start() → 서비스 종류(위치/블루투스)를 다시 계산해 갱신.
// 언제 꺼지나: 로그아웃하거나 보호자 계정이면 stop().
class ChildForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "embrace_child_fgs"
        private const val NOTI_ID = 4201

        @Volatile var isRunning = false
            private set

        fun start(ctx: Context) {
            // 위치·블루투스 권한이 둘 다 없으면 시작하지 않는다
            // (startForegroundService 후 startForeground 를 못 부르면 앱이 강제 종료되기 때문)
            val hasBle = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!ChildLocationTracker.hasPermission(ctx) && !hasBle) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ChildForegroundService::class.java))
            } catch (e: Exception) {
                // 앱이 백그라운드일 때 부르면 안드로이드 12+ 가 막는다 → 다음에 화면에 올라왔을 때 다시 시도됨
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ChildForegroundService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        ChildLocationTracker.ensureStarted(this) // 위치 권한이 있으면 GPS 수집 시작 (이미 켜져 있으면 그대로)
        return START_STICKY
    }

    // 지금 가진 권한으로 가능한 서비스 종류만 골라 포그라운드로 전환. 하나도 없으면 false
    private fun enterForeground(): Boolean {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // 안드로이드 9 이하는 종류 구분이 없다
            startForeground(NOTI_ID, notification)
            return true
        }
        val hasLoc = ChildLocationTracker.hasPermission(this)
        val hasBle = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        // 위치+블루투스 → 블루투스만 순서로 시도 (위치 권한 문제로 거절되면 조끼 연결이라도 살린다)
        val candidates = buildList {
            var both = 0
            if (hasLoc) both = both or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (hasBle) both = both or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (both != 0) add(both)
            if (hasLoc && hasBle) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        for (type in candidates) {
            try {
                startForeground(NOTI_ID, notification, type)
                return true
            } catch (e: Exception) {
                // SecurityException / ForegroundServiceStartNotAllowedException → 다음 후보
            }
        }
        return false
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "EMBRACE 보호 기능 실행 중", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        // 알림을 누르면 앱 화면으로
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EMBRACE")
            .setContentText("조끼 연결·위치 공유 중")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
