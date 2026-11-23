package com.example.embraceapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.embraceapp.data.*
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
                EmbraceApp()
            }
        }
    }
}
