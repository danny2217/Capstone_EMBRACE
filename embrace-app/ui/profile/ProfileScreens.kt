package com.example.embraceapp.ui.profile

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
import com.example.embraceapp.ui.*
import com.example.embraceapp.ui.theme.*
import com.example.embraceapp.ui.components.*
import com.example.embraceapp.ui.auth.*
import com.example.embraceapp.ui.home.*
import com.example.embraceapp.ui.notification.*
import com.example.embraceapp.ui.map.*

// ───────── 마이페이지 ─────────

// 프로필 화면 (아바타는 분홍/흰색 경계에 걸치게 배치)
@Composable
fun ProfileScreen(nav: Nav) {
    val me by AuthRepository.profile.collectAsState() // 로그인한 사람 정보
    var logoutDialog by remember { mutableStateOf(false) } // 로그아웃 팝업 표시 여부

    Column(Modifier.fillMaxSize().background(PrimaryCoral)) {
        // 분홍 헤더
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().height(84.dp).padding(horizontal = 16.dp)
        ) {
            IconButton(onClick = { nav.back() }, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = IconGray)
            }
            Text(
                "프로필", modifier = Modifier.align(Alignment.Center),
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextCharcoal
            )
            BellButton({ nav.go(Screen.Notifications) }, Modifier.align(Alignment.CenterEnd))
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            // 흰 영역 (위에서 56dp 아래부터 시작)
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp)
                    .clip(RoundedCornerShape(topStart = 56.dp, topEnd = 56.dp))
                    .background(SurfaceWhite)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(66.dp))
                Text(me?.name ?: "", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextCharcoal)
                Text(relationLabel(me), fontSize = 12.sp)
                Spacer(Modifier.height(32.dp))
                // 메뉴 4개
                MenuRowVec(Icons.Filled.Person, "프로필 수정") { nav.go(Screen.EditProfile) }
                MenuRowVec(Icons.Filled.Lock, "비밀번호 변경") { nav.go(Screen.ChangePw) }
                MenuRowVec(Icons.Filled.Settings, "설정") { nav.go(Screen.Settings) }
                MenuRowVec(Icons.AutoMirrored.Filled.ExitToApp, "로그아웃") { logoutDialog = true }
            }

            // 아바타 (분홍과 흰색 경계에 걸쳐 있음)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(104.dp)
                    .shadow(8.dp, RoundedCornerShape(30.dp))
                    .clip(RoundedCornerShape(30.dp)) // 둥근 사각형 배경
                    .background(BgPeach), // 메뉴의 아이콘 상자와 같은 연한 하늘색
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Person, null, Modifier.size(64.dp), tint = IconGray) // 프로필 수정 메뉴와 같은 사람 아이콘
            }
        }
    }

    // 로그아웃 확인 팝업
    if (logoutDialog) {
        ConfirmDialog(
            title = "로그아웃 알림",
            question = "정말로 로그아웃 하시겠습니까?",
            confirmText = "로그아웃",
            onConfirm = { logoutDialog = false; AuthRepository.logout(); nav.root(Screen.AuthLaunch) },
            onCancel = { logoutDialog = false }
        )
    }
}

// 프로필 수정 화면
@Composable
fun EditProfileScreen(nav: Nav) {
    val me by AuthRepository.profile.collectAsState() // 로그인한 사람 정보
    // 입력칸 기본값은 지금 저장된 내 정보
    var name by remember(me) { mutableStateOf(me?.name ?: "") }
    var phone by remember(me) { mutableStateOf(me?.phone ?: "") }
    var email by remember(me) { mutableStateOf(me?.email ?: "") }
    PinkPage("프로필 수정", onBack = { nav.back() }, onBell = { nav.go(Screen.Notifications) }) {
        Box(Modifier.size(84.dp).shadow(6.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(BgPeach), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Person, null, Modifier.size(52.dp), tint = IconGray)
        }
        Spacer(Modifier.height(10.dp))
        Text(me?.name ?: "", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(relationLabel(me), fontSize = 11.sp)
        Spacer(Modifier.height(20.dp))
        Text("계정 설정", Modifier.fillMaxWidth().padding(bottom = 12.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        SoftField("사용자 이름", "이름을 입력해 주세요", value = name, onChange = { name = it })
        SoftField("전화번호", "전화번호를 입력해 주세요", value = phone, onChange = { phone = it })
        SoftField("이메일 주소", "이메일을 입력해 주세요", value = email, onChange = { email = it })
        Spacer(Modifier.height(24.dp))
        // 세 칸이 모두 채워져 있어야 넘어감
        PillButton("프로필 수정", enabled = name.isNotBlank() && phone.isNotBlank() && email.isNotBlank()) { nav.go(Screen.EditDone) }
    }
}

// 비밀번호 변경 화면 (프로필에서 접근)
@Composable
fun ChangePwScreen(nav: Nav) {
    PinkPage("비밀번호 변경", onBack = { nav.back() }, onBell = { nav.go(Screen.Notifications) }) {
        PasswordChangeForm { nav.go(Screen.ChangePwDone) }
    }
}

// 비밀번호 변경 입력칸 + 버튼 (비밀번호 변경 / 비밀번호 설정 화면에서 같이 사용)
// 현재 비밀번호를 입력하고, 새 비밀번호가 6자 이상이며 두 칸이 같아야 버튼이 눌림
@Composable
fun PasswordChangeForm(onDone: () -> Unit) {
    var cur by remember { mutableStateOf("") }
    var n1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }
    SoftField("현재 비밀번호", "현재 비밀번호", password = true, value = cur, onChange = { cur = it })
    SoftField("새 비밀번호", "새 비밀번호", password = true, value = n1, onChange = { n1 = it })
    SoftField("새 비밀번호 확인", "새 비밀번호 확인", password = true, value = n2, onChange = { n2 = it })
    if (n1.isNotEmpty() && n1.length < 6) Text("비밀번호는 6자 이상으로 해주세요.", color = SosRed, fontSize = 12.sp)
    else if (n2.isNotEmpty() && n1 != n2) Text("비밀번호가 일치하지 않아요.", color = SosRed, fontSize = 12.sp)
    Spacer(Modifier.height(24.dp))
    PillButton("비밀번호 변경", enabled = cur.isNotBlank() && n1.length >= 6 && n1 == n2) { onDone() }
}

// 설정 화면 (알림 설정, 비밀번호 설정, 계정 삭제, 다크 테마)
@Composable
fun SettingsScreen(nav: Nav) {
    PinkPage("설정", onBack = { nav.back() }, onBell = { nav.go(Screen.Notifications) }) {
        MenuRow(Icons.Default.Notifications, "알림 설정") { nav.go(Screen.NotiSettings) }
        MenuRow(Icons.Default.Lock, "비밀번호 설정") { nav.go(Screen.PwSettings) }
        MenuRow(Icons.Default.Person, "계정 삭제") { nav.go(Screen.DeleteAccount) }
        // 다크 테마 스위치 (켜면 앱 전체 색이 바뀜)
        MenuRow(Icons.Default.Star, "다크 테마", trailing = {
            Switch(isDarkTheme, { isDarkTheme = it }, colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue, checkedThumbColor = Color.White))
        }) { isDarkTheme = !isDarkTheme }
        Spacer(Modifier.height(48.dp))
        Text("앱 정보", Modifier.fillMaxWidth().padding(bottom = 8.dp), fontSize = 13.sp)
        Surface(shape = RoundedCornerShape(16.dp), color = BgPeach, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("앱버전  1.0.0", fontSize = 12.sp)
                Text("개발자", fontSize = 12.sp)
                Text("기기정보", fontSize = 12.sp)
            }
        }
    }
}

// 비밀번호 설정 화면 (설정에서 접근)
@Composable
fun PwSettingsScreen(nav: Nav) {
    PinkPage("비밀번호 설정", onBack = { nav.back() }, onBell = { nav.go(Screen.Notifications) }) {
        PasswordChangeForm { nav.go(Screen.PwSettingsDone) }
    }
}

// 계정 삭제 화면
@Composable
fun DeleteAccountScreen(nav: Nav) {
    var dialog by remember { mutableStateOf(false) } // 확인 팝업 표시 여부
    var pw by remember { mutableStateOf("") }         // 입력한 비밀번호
    PinkPage("계정 삭제", onBack = { nav.back() }, onBell = { nav.go(Screen.Notifications) }) {
        Text("정말로 계정을 삭제하시겠습니까?", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = InactiveGray, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("이 작업을 수행하면 모든 데이터가 영구적으로 삭제되며, 이를 복구할 수 없게 됩니다.", fontSize = 11.sp)
                Text("• 귀하의 모든 지출, 수입 및 관련 거래 내역이 삭제됩니다.", fontSize = 10.sp)
                Text("• 계정이나 그와 관련된 어떠한 정보에도 접근할 수 없게 됩니다.", fontSize = 10.sp)
                Text("• 이 작업은 되돌릴 수 없습니다.", fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("계정 삭제를 하시려면 비밀번호를 입력해 주세요.", fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        SoftField("비밀번호", "비밀번호 입력", password = true, value = pw, onChange = { pw = it })
        Spacer(Modifier.height(8.dp))
        // 비밀번호를 입력해야 눌림
        PillButton("계정 삭제", enabled = pw.isNotBlank()) { dialog = true }
        Spacer(Modifier.height(10.dp))
        PillButton("취 소", color = InactiveGray) { nav.back() }
    }

    // 계정 삭제 확인 팝업
    if (dialog) {
        ConfirmDialog(
            title = "계정 삭제",
            question = "정말로 삭제하시겠습니까?",
            body = "계정을 삭제함으로써, 귀하는 이러한 조치로 인한 결과를 이해하고 있으며, 계정 및 그와 관련된 모든 데이터를 영구적으로 삭제하는 데 동의하는 것으로 간주됩니다.",
            confirmText = "계정 삭제",
            onConfirm = { dialog = false; nav.root(Screen.AuthLaunch) },
            onCancel = { dialog = false }
        )
    }
}