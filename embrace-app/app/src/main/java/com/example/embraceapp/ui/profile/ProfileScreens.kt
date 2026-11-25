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

// 프로필 수정 화면 (이름, 전화번호는 수정 가능. 이메일은 로그인 계정이라 보기만 가능)
@Composable
fun EditProfileScreen(nav: Nav) {
    val me by AuthRepository.profile.collectAsState() // 로그인한 사람 정보
    // 입력칸 기본값은 지금 저장된 내 정보
    var name by remember(me) { mutableStateOf(me?.name ?: "") }
    var phone by remember(me) { mutableStateOf(me?.phone ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
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
        SoftField("이메일 주소 (변경할 수 없어요)", "", value = me?.email ?: "", readOnly = true)
        error?.let { Text(it, color = SosRed, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        Spacer(Modifier.height(24.dp))
        // 이름과 전화번호가 채워져 있어야 저장됨
        PillButton(if (loading) "저장 중..." else "프로필 수정", enabled = !loading && me != null && name.isNotBlank() && phone.isNotBlank()) {
            loading = true; error = null
            AuthRepository.updateProfile(name, phone) { err ->
                loading = false
                if (err == null) nav.go(Screen.EditDone) else error = err
            }
        }
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
// 현재 비밀번호를 입력하고, 새 비밀번호가 6자 이상이며 두 칸이 같아야 버튼이 눌림. 현재 비밀번호가 틀리면 변경되지 않음
@Composable
fun PasswordChangeForm(onDone: () -> Unit) {
    var cur by remember { mutableStateOf("") }
    var n1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    SoftField("현재 비밀번호", "현재 비밀번호", password = true, value = cur, onChange = { cur = it })
    SoftField("새 비밀번호", "새 비밀번호", password = true, value = n1, onChange = { n1 = it })
    SoftField("새 비밀번호 확인", "새 비밀번호 확인", password = true, value = n2, onChange = { n2 = it })
    if (n1.isNotEmpty() && n1.length < 6) Text("비밀번호는 6자 이상으로 해주세요.", color = SosRed, fontSize = 12.sp)
    else if (n2.isNotEmpty() && n1 != n2) Text("비밀번호가 일치하지 않아요.", color = SosRed, fontSize = 12.sp)
    error?.let { Text(it, color = SosRed, fontSize = 12.sp) }
    Spacer(Modifier.height(24.dp))
    PillButton(if (loading) "변경 중..." else "비밀번호 변경", enabled = !loading && cur.isNotBlank() && n1.length >= 6 && n1 == n2) {
        loading = true; error = null
        AuthRepository.changePassword(cur, n1) { err ->
            loading = false
            if (err == null) onDone() else error = err
        }
    }
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
        AppInfoSection()
    }
}

// 앱 정보 한 줄 (왼쪽 항목 이름 + 오른쪽 값)
@Composable
private fun InfoLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = IconGray, modifier = Modifier.width(72.dp))
        Text(value, fontSize = 12.sp, color = TextCharcoal)
    }
}

// ───────── 설정 하단: 앱 정보 / 개발자 정보 / 기기 정보 (+ 숨은 개발자 도구) ─────────
// 맨 아래 [개발자 도구] 메뉴는 항상 보인다 (시연용). 누르면 경고를 한 번 보여 주고 연다.
@Composable
fun AppInfoSection() {
    val ctx = LocalContext.current
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "-" } catch (e: Exception) { "-" }
    }
    val link by VestConnection.link.collectAsState()
    var devOpen by remember { mutableStateOf(false) }
    var devWarn by remember { mutableStateOf(false) }

    Text("앱 정보", Modifier.fillMaxWidth().padding(bottom = 8.dp), fontSize = 13.sp)
    Surface(shape = RoundedCornerShape(16.dp), color = BgPeach, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoLine("앱 이름", "EMBRACE")
            InfoLine("앱 버전", version)
            InfoLine("설명", "자폐 스펙트럼 아동을 위한 스마트 압박 조끼 보조 앱")
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("개발자 정보", Modifier.fillMaxWidth().padding(bottom = 8.dp), fontSize = 13.sp)
    Surface(shape = RoundedCornerShape(16.dp), color = BgPeach, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoLine("팀", "EMBRACE (캡스톤디자인)")
            InfoLine("신희", "앱 UI · 화면 설계 · API 명세")
            InfoLine("지후", "센서 · BLE 통신")
            InfoLine("성환", "하드웨어 · DB · 서버")
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("기기 정보", Modifier.fillMaxWidth().padding(bottom = 8.dp), fontSize = 13.sp)
    Surface(shape = RoundedCornerShape(16.dp), color = BgPeach, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoLine("휴대폰", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoLine("조끼", when (link) {
                VestLink.Connected -> "${VestConnection.VEST_NAME} 연결됨"
                VestLink.Connecting -> "연결 중..."
                VestLink.Disconnected -> "연결 안 됨"
            })
            InfoLine("서버", if (AppConfig.SERVER_URL.isNotBlank() && AppConfig.API_KEY.isNotBlank()) "설정됨" else "설정 안 됨")
        }
    }

    Spacer(Modifier.height(20.dp))
    if (devOpen) {
        DevToolsSection(onClose = { devOpen = false })
    } else {
        // 개발자 도구 메뉴 (항상 보임)
        Surface(
            shape = RoundedCornerShape(16.dp), color = BgPeach,
            modifier = Modifier.fillMaxWidth().clickable { devWarn = true }
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("개발자 도구", fontSize = 13.sp, color = TextCharcoal)
                    Text("수동 가압 · 배기 · 에어펌프/밸브 상태 확인", fontSize = 11.sp, color = IconGray)
                }
                Text("›", fontSize = 22.sp, color = TextCharcoal)
            }
        }
    }
    if (devWarn) {
        ConfirmDialog(
            title = "개발자 도구",
            question = "개발자 테스트용 기능이에요. 아이가 조끼를 입지 않은 상태에서만 사용해 주세요.",
            confirmText = "열기",
            onConfirm = { devWarn = false; devOpen = true },
            onCancel = { devWarn = false }
        )
    }
    Spacer(Modifier.height(24.dp))
}

// ───────── 개발자 도구 (시연용) ─────────
// [가압 시작] = 'i' (펌프 ON + 밸브 닫힘 → 정해진 시간 뒤 자동 유지 → 배기)
// [배기]      = 'd' (펌프 OFF + 밸브 열림)
// [비상 배기] = 'x' (즉시 배기 후 잠금, 잠금 해제는 조끼에서)
// ⚠ 'i' 는 펌웨어 hr_link.cpp onCommand 가 받도록 바뀐 뒤에 동작한다.
//   PNEUMATIC_ARMED=false 인 동안은 상태 값만 바뀌고 실제 펌프·밸브는 움직이지 않는다.
@Composable
fun DevToolsSection(onClose: () -> Unit) {
    val sensor by SensorRepository.state.collectAsState()
    val link by VestConnection.link.collectAsState()
    val v = sensor.vest
    var confirmExhaust by remember { mutableStateOf(false) }
    var confirmInflate by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("개발자 도구", fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("닫기", fontSize = 12.sp, color = LinkBlue, modifier = Modifier.clickable { onClose() })
    }
    Spacer(Modifier.height(8.dp))
    Surface(shape = RoundedCornerShape(16.dp), color = BgPeach, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("아이가 조끼를 입지 않은 상태에서만 사용하세요.", fontSize = 11.sp, color = SosRed)
            if (link != VestLink.Connected || v == null) {
                Text("조끼가 연결되지 않았어요. 홈 > 기기 관리에서 먼저 연결해 주세요.", fontSize = 12.sp, color = IconGray)
            } else {
                Text("조끼 상태: ${v.stateName} (${v.inStateSec}초째)", fontSize = 12.sp)
                Text("에어펌프: ${if (v.pump) "켜짐" else "꺼짐"}   ·   솔레노이드 밸브: ${if (v.valveOpen) "열림" else "닫힘"}", fontSize = 12.sp)
                Text(if (v.armed) "실제 구동 모드" else "연습 모드 (펌프·밸브가 실제로 움직이지 않음)", fontSize = 11.sp, color = IconGray)
            }

            // 수동 가압 / 배기
            val connected = link == VestLink.Connected
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { confirmInflate = true },
                    enabled = connected,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryCoral, contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) { Text("가압 시작", fontSize = 12.sp) }
                Button(
                    onClick = { result = if (VestConnection.deflate()) "배기 명령을 보냈어요." else "보내지 못했어요. 조끼 연결을 확인해 주세요." },
                    enabled = connected,
                    colors = ButtonDefaults.buttonColors(containerColor = BgPeach, contentColor = TextCharcoal),
                    modifier = Modifier.weight(1f)
                ) { Text("배기", fontSize = 12.sp) }
            }
            Text("가압은 '대기' 상태에서만 시작돼요. 정해진 시간이 지나면 조끼가 알아서 유지 → 배기로 넘어가요.", fontSize = 11.sp, color = IconGray)

            // 지금 펌웨어가 받는 명령: 비상 배기
            Button(
                onClick = { confirmExhaust = true },
                enabled = link == VestLink.Connected,
                colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) { Text("비상 배기 보내기", fontSize = 12.sp) }
            result?.let { Text(it, fontSize = 11.sp, color = IconGray) }
        }
    }

    if (confirmInflate) {
        ConfirmDialog(
            title = "가압 시작",
            question = "조끼에 공기를 넣어요. 아이가 입지 않은 상태인지 확인해 주세요.",
            confirmText = "가압",
            onConfirm = {
                confirmInflate = false
                result = if (VestConnection.inflate()) "가압 명령을 보냈어요. 조끼 상태가 '가압 중'으로 바뀌는지 확인해 주세요." else "보내지 못했어요. 조끼 연결을 확인해 주세요."
            },
            onCancel = { confirmInflate = false }
        )
    }

    if (confirmExhaust) {
        ConfirmDialog(
            title = "비상 배기",
            question = "조끼의 공기를 모두 빼요. 배기 후에는 조끼에서 잠금 해제를 해야 다시 가압할 수 있어요.",
            confirmText = "보내기",
            onConfirm = {
                confirmExhaust = false
                result = if (VestConnection.exhaust()) "비상 배기 명령을 보냈어요." else "보내지 못했어요. 조끼 연결을 확인해 주세요."
            },
            onCancel = { confirmExhaust = false }
        )
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
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
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
        error?.let { Text(it, color = SosRed, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        Spacer(Modifier.height(8.dp))
        // 비밀번호를 입력해야 눌림
        PillButton(if (loading) "삭제 중..." else "계정 삭제", enabled = !loading && pw.isNotBlank()) { dialog = true }
        Spacer(Modifier.height(10.dp))
        PillButton("취 소", color = InactiveGray) { nav.back() }
    }

    // 계정 삭제 확인 팝업 (확인을 누르면 비밀번호를 확인하고 실제로 삭제)
    if (dialog) {
        ConfirmDialog(
            title = "계정 삭제",
            question = "정말로 삭제하시겠습니까?",
            body = "계정을 삭제함으로써, 귀하는 이러한 조치로 인한 결과를 이해하고 있으며, 계정 및 그와 관련된 모든 데이터를 영구적으로 삭제하는 데 동의하는 것으로 간주됩니다.",
            confirmText = "계정 삭제",
            onConfirm = {
                dialog = false; loading = true; error = null
                AuthRepository.deleteAccount(pw) { err ->
                    loading = false
                    if (err == null) nav.root(Screen.AuthLaunch) else error = err
                }
            },
            onCancel = { dialog = false }
        )
    }
}
