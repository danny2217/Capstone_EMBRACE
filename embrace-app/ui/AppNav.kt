package com.example.embraceapp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import com.example.embraceapp.R
import com.example.embraceapp.data.*
import com.example.embraceapp.model.*
import com.example.embraceapp.ble.*
import com.example.embraceapp.util.*
import com.example.embraceapp.ui.theme.*
import com.example.embraceapp.ui.components.*
import com.example.embraceapp.ui.auth.*
import com.example.embraceapp.ui.home.*
import com.example.embraceapp.ui.notification.*
import com.example.embraceapp.ui.map.*
import com.example.embraceapp.ui.profile.*

// ───────── 화면 목록 / 이동 / 앱 전체 틀 ─────────

// 화면 목록
enum class Screen {
    Splash, AuthLaunch, Login,
    SignupType, SignupInfo, SignupDone,
    ForgotEmail, ForgotNew, ForgotDone,
    Home, Notifications, Map,
    Profile, EditProfile, EditDone, ChangePw, ChangePwDone,
    Settings, NotiSettings, PwSettings, PwSettingsDone, DeleteAccount
}

// 화면 이동 담당 (뒤로가기 기록을 쌓아둠)
class Nav {
    val stack = mutableStateListOf(Screen.Splash)
    val current: Screen get() = stack.last()                                    // 지금 보이는 화면
    fun go(s: Screen) { stack.add(s) }                                          // 다음 화면으로
    fun root(s: Screen) { stack.clear(); stack.add(s) }                         // 기록 지우고 이동
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }          // 뒤로가기
}

// 앱 전체 틀: 어떤 화면을 보여줄지 결정
@Composable
fun EmbraceApp() {
    val nav = remember { Nav() }
    val s = nav.current

    // 시작 화면 2초 뒤 로그인/가입 선택 화면으로 이동
    LaunchedEffect(Unit) {
        delay(2000)
        if (nav.current == Screen.Splash) nav.root(if (AuthRepository.isLoggedIn) Screen.Home else Screen.AuthLaunch) // 이미 로그인돼 있으면 바로 홈
    }
    BackHandler(enabled = nav.stack.size > 1) { nav.back() } // 폰의 뒤로가기 버튼 처리

    // 하단 탭 바를 보여줄 화면들
    val profileGroup = setOf(
        Screen.Profile, Screen.EditProfile, Screen.ChangePw, Screen.Settings,
        Screen.NotiSettings, Screen.PwSettings, Screen.DeleteAccount
    )
    val homeGroup = setOf(Screen.Home, Screen.Notifications)
    val showBar = s in profileGroup || s in homeGroup || s == Screen.Map
    // 선택된 탭 번호 (0: 지도, 1: 홈, 2: 프로필)
    val tabIndex = when {
        s == Screen.Map -> 0
        s in profileGroup -> 2
        else -> 1
    }

    Scaffold(
        containerColor = SurfaceWhite,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) BottomBar(tabIndex) { i ->
                nav.root(listOf(Screen.Map, Screen.Home, Screen.Profile)[i])
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().then(if (s == Screen.Map) Modifier else Modifier.padding(pad))) {
            // 현재 화면에 맞는 함수 호출
            when (s) {
                Screen.Splash -> LaunchScreen()
                Screen.AuthLaunch -> AuthLaunchScreen(nav)
                Screen.Login -> LoginScreen(nav)
                Screen.SignupType -> SignupTypeScreen(nav)
                Screen.SignupInfo -> SignupInfoScreen(nav)
                Screen.SignupDone -> DoneScreen("회원가입 완료") { nav.root(Screen.Login) }
                Screen.ForgotEmail -> ForgotEmailScreen(nav)
                Screen.ForgotNew -> ForgotNewScreen(nav)
                Screen.ForgotDone -> DoneScreen("비밀번호 변경 성공") { nav.root(Screen.Login) }
                Screen.Home -> HomeScreen { nav.go(Screen.Notifications) }
                Screen.Notifications -> NotificationScreen(nav)
                Screen.Map -> MapScreen()
                Screen.Profile -> ProfileScreen(nav)
                Screen.EditProfile -> EditProfileScreen(nav)
                Screen.EditDone -> DoneScreen("프로필 수정 완료") { nav.root(Screen.Profile) }
                Screen.ChangePw -> ChangePwScreen(nav)
                Screen.ChangePwDone -> DoneScreen("비밀번호 변경 성공") { nav.root(Screen.Profile) }
                Screen.Settings -> SettingsScreen(nav)
                Screen.NotiSettings -> NotiSettingsScreen(nav)
                Screen.PwSettings -> PwSettingsScreen(nav)
                Screen.PwSettingsDone -> DoneScreen("비밀번호 변경 성공") { nav.root(Screen.Profile) }
                Screen.DeleteAccount -> DeleteAccountScreen(nav)
            }
        }
    }
}
