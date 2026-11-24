package com.example.embraceapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.embraceapp.data.SensorRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

// ───────── 아동용 폰 자기 위치 수집 ─────────
// 2026-11-23 팀 결정: 위치는 워치가 아니라 "아동용 폰"이 직접 잡는다.
//   아동 폰 GPS ─▶ SensorRepository.childLocation ─▶ ServerSync.childLoop ─▶ POST /api/location
//                                                                         ─▶ 보호자 폰 GET /api/summary ─▶ 지도 핀
//
// · 자녀(child) 로그인일 때만 켠다. 보호자 폰은 서버에서 받은 값을 쓰므로 여기서 켜면 안 된다 (값이 덮어써짐).
// · Google Fused Location: GPS + Wi-Fi + 기지국을 섞어서 실내에서도 위치가 나온다 (GPS 단독보다 빠르고 배터리 적게 씀).
// · 주기 10 s (워치 앱이 쓰던 값과 동일). 서버 업로드 주기는 ServerSync 의 3 s 이고, 새 위치가 있을 때만 올라간다.
// · 사용자가 "대략적 위치"만 허용하면 오차가 수 km 가 될 수 있다 → 정확도(acc_m)를 같이 올려서 보호자 화면에 ±m 로 보여 준다.
//
// · 화면이 꺼져도 계속 받으려면 service/ChildForegroundService 가 떠 있어야 한다 (상단 알림 "조끼 연결·위치 공유 중").
//   그 서비스가 위치 종류로 떠 있는 동안은 "앱 사용 중" 권한만으로 백그라운드 위치가 허용된다 (항상 허용 권한 불필요).
object ChildLocationTracker {
    private const val INTERVAL_MS = 10_000L
    private const val MIN_INTERVAL_MS = 5_000L

    private var client: FusedLocationProviderClient? = null
    @Volatile var running = false
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val l = result.lastLocation ?: return
            SensorRepository.updateChildLocation(
                l.latitude, l.longitude,
                if (l.hasAccuracy()) l.accuracy.toDouble() else null,
                l.time // 실제로 위치를 잡은 시각 (받은 시각이 아니라). 서버 ts·보호자 화면 시각으로 쓰임
            )
        }
    }

    fun hasPermission(ctx: Context): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }

    // 여러 번 불러도 안전: 이미 켜져 있거나 권한이 없으면 아무것도 안 함.
    // 권한을 나중에(지도 화면, 설정 앱) 허용해도 ServerSync 가 3 s 마다 불러 주므로 자동으로 켜진다.
    @SuppressLint("MissingPermission") // 바로 위 hasPermission 으로 확인함
    @Synchronized
    fun ensureStarted(ctx: Context) {
        if (running || !hasPermission(ctx)) return
        val c = LocationServices.getFusedLocationProviderClient(ctx.applicationContext)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .build()
        try {
            c.requestLocationUpdates(req, callback, Looper.getMainLooper())
                .addOnFailureListener { stop() } // 위치 서비스가 꺼져 있는 등 → 다음 ensureStarted 때 재시도
            // 첫 위치를 기다리는 동안(수 초~수십 초) 마지막으로 알려진 위치라도 먼저 보여 준다
            c.lastLocation.addOnSuccessListener { l ->
                if (l != null && SensorRepository.state.value.childLocation == null) {
                    SensorRepository.updateChildLocation(
                        l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy.toDouble() else null, l.time
                    )
                }
            }
            client = c
            running = true
        } catch (e: SecurityException) {
            running = false // 확인과 요청 사이에 권한이 취소된 경우
        }
    }

    // 로그아웃하거나 보호자 계정으로 바뀌면 끈다
    @Synchronized
    fun stop() {
        client?.removeLocationUpdates(callback)
        client = null
        running = false
    }
}
