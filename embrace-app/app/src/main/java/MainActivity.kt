package com.example.embraceapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.embraceapp.data.*
import com.example.embraceapp.location.ChildLocationTracker
import com.example.embraceapp.service.ChildForegroundService
import com.example.embraceapp.ble.VestConnection
import com.example.embraceapp.ble.VestLink
import com.example.embraceapp.util.*
import com.example.embraceapp.ui.*
import com.example.embraceapp.ui.theme.*

// ───────── 앱 시작점 ─────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 상태바 영역까지 화면 사용
        Notifier.createChannel(this) // 알림 채널(소리/진동) 만들기
        AuthRepository.loadProfile() // 이미 로그인돼 있으면 내 정보 불러오기
        setContent {
            // 로그인한 사람이 바뀌면 서버 동기화(자녀: 업로드 / 보호자: 조회)를 맞춰서 시작
            val me by AuthRepository.profile.collectAsState()
            LaunchedEffect(me) { ServerSync.onProfile(applicationContext, me) }
            // 색을 따로 안 정한 글씨의 기본 색 지정
            CompositionLocalProvider(LocalContentColor provides TextCharcoal) {
                // 자녀 폰: 로그인하면 위치 권한을 받고 내 위치 수집 시작 (보호자 앱으로 보낼 위치)
                val requestChildLocation = rememberPermissionRequester(
                    permissions = listOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    needAll = false,
                    title = "위치 권한",
                    message = "보호자가 지도에서 내 위치를 볼 수 있도록 위치 권한이 필요해요.\n" +
                        "'정확한 위치'를 허용해 주세요."
                ) { ok ->
                    if (ok) ChildLocationTracker.ensureStarted(applicationContext)
                    // 위치를 거부해도 조끼 BLE 는 살려야 하므로 서비스는 시작 시도 (가능한 종류만 켜짐)
                    ChildForegroundService.start(applicationContext)
                }
                LaunchedEffect(me?.role) {
                    if (me?.role == "child") requestChildLocation() // 이미 허용돼 있으면 팝업 없이 바로 시작
                    else {                                          // 로그아웃·보호자 계정이면 끔
                        ChildLocationTracker.stop()
                        ChildForegroundService.stop(applicationContext)
                    }
                }
                // 자녀 폰이 조끼에 새로 연결되면 서비스를 다시 불러 "블루투스 연결 유지" 종류를 추가
                val vestLink by VestConnection.link.collectAsState()
                LaunchedEffect(vestLink, me?.role) {
                    if (me?.role == "child" && vestLink == VestLink.Connected) ChildForegroundService.start(applicationContext)
                }
                EmbraceApp()
            }
        }
    }
}
