package com.example.embraceapp.ui.auth

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
import com.example.embraceapp.ui.home.*
import com.example.embraceapp.ui.notification.*
import com.example.embraceapp.ui.map.*
import com.example.embraceapp.ui.profile.*

// ───────── 시작 / 로그인 / 회원가입 / 비밀번호 찾기 ─────────

// 앱을 켜면 처음 2초간 보이는 로고 화면 (로고를 화면 정중앙에 배치)
@Composable
fun LaunchScreen() {
    Box(Modifier.fillMaxSize().background(PrimaryCoral), contentAlignment = Alignment.Center) {
        Image(painterResource(id = R.drawable.embrace_logo), "앱 로고", Modifier.size(320.dp))
    }
}

// 로그인 / 회원가입 선택 화면
@Composable
fun AuthLaunchScreen(nav: Nav) {
    // 위 칸과 아래 칸의 높이를 똑같이(weight 1f) 해서, 로고가 첫 화면과 같은 정중앙에 오도록 함
    Column(
        Modifier.fillMaxSize().background(PrimaryCoral),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Image(painterResource(id = R.drawable.embrace_logo), "앱 로고", Modifier.size(320.dp))
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { nav.go(Screen.Login) },
                modifier = Modifier.fillMaxWidth(0.48f).height(40.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BgPeach, contentColor = TextCharcoal),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) { Text("로그인", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { nav.go(Screen.SignupType) },
                modifier = Modifier.fillMaxWidth(0.48f).height(40.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = InactiveGray, contentColor = TextCharcoal),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) { Text("회원 가입", fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            Text(
                "비밀번호를 잊으셨나요?",
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextCharcoal,
                modifier = Modifier.clickable { nav.go(Screen.ForgotEmail) }.padding(top = 8.dp, bottom = 8.dp)
            )
        }
    }
}

// 로그인 화면
@Composable
fun LoginScreen(nav: Nav) {
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) } // 로그인 실패 이유
    var loading by remember { mutableStateOf(false) }        // 로그인 중 여부
    PinkPage("로그인", onBack = { nav.back() }, headerHeight = 150.dp) {
        Spacer(Modifier.height(40.dp))
        SoftField("이메일 계정", "이메일을 입력해 주세요", value = email, onChange = { email = it })
        SoftField("비밀번호", "비밀번호 입력", password = true, value = pw, onChange = { pw = it })
        // 아이디 저장 / 자동 로그인 체크박스
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            var a by remember { mutableStateOf(false) }
            var b by remember { mutableStateOf(false) }
            val cbColors = CheckboxDefaults.colors(checkedColor = AccentBlue, uncheckedColor = IconGray, checkmarkColor = Color.White) // 체크박스도 파랑으로 통일
            Checkbox(a, { a = it }, colors = cbColors); Text("아이디 저장", fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            Checkbox(b, { b = it }, colors = cbColors); Text("자동 로그인", fontSize = 11.sp)
        }
        Spacer(Modifier.height(20.dp))
        error?.let { Text(it, color = SosRed, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        Spacer(Modifier.height(14.dp))
        PillButton(if (loading) "로그인 중..." else "로그인", enabled = !loading && email.isNotBlank() && pw.isNotBlank()) {
            loading = true; error = null
            AuthRepository.login(email, pw) { err ->
                loading = false
                if (err == null) nav.root(Screen.Home) else error = err
            }
        }
        Spacer(Modifier.height(12.dp))
        PillButton("회원가입", color = InactiveGray) { nav.go(Screen.SignupType) }
        SmallLink("비밀번호를 잊으셨나요?") { nav.go(Screen.ForgotEmail) }
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallLink("회원가입") { nav.go(Screen.SignupType) }
            Text("·", fontSize = 12.sp, color = TextCharcoal)
            SmallLink("비밀번호 찾기") { nav.go(Screen.ForgotEmail) }
        }
    }
}

// 가입 유형(보호자/자녀)을 두 화면에서 같이 쓰기 위한 변수
private var signupIsGuardian = true

// 회원가입 1단계: 가입 유형 + 기본 정보
@Composable
fun SignupTypeScreen(nav: Nav) {
    var type by remember { mutableStateOf(if (signupIsGuardian) 0 else 1) }
    // 입력칸 6개 (성함, 전화번호, 생년월일, 이메일, 비밀번호, 비밀번호 재확인)
    val f = remember { mutableStateListOf("", "", "", "", "", "") }
    PinkPage(
        "회원가입", onBack = { nav.back() }, headerHeight = 130.dp,
        bottomContent = {
            PillButton("다 음", enabled = f.all { it.isNotBlank() } && f[4].length >= 6 && f[4] == f[5]) {
                signupIsGuardian = type == 0
                signupDraft.isGuardian = type == 0
                signupDraft.name = f[0]; signupDraft.phone = f[1]; signupDraft.birth = f[2]
                signupDraft.email = f[3]; signupDraft.password = f[4]
                nav.go(Screen.SignupInfo)
            }
            SmallLink("이미 계정이 있으신가요?") { nav.root(Screen.Login) }
        }
    ) {
        Text("가입 유형", fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp))
        Box(Modifier.fillMaxWidth(0.6f)) { SegTabs(listOf("보호자", "자녀"), type) { type = it } }
        Spacer(Modifier.height(16.dp))
        SoftField("성함", "홍길동", value = f[0], onChange = { f[0] = it })
        SoftField("전화번호", "010-0000-0000", value = f[1], onChange = { f[1] = it })
        SoftField("생년월일", "YYYY / DD / MM", value = f[2], onChange = { f[2] = it })
        SoftField("이메일", "example@example.com", value = f[3], onChange = { f[3] = it })
        SoftField("비밀번호", "비밀번호 입력", password = true, value = f[4], onChange = { f[4] = it })
        SoftField("비밀번호 재확인", "비밀번호 재입력", password = true, value = f[5], onChange = { f[5] = it })
        // 비밀번호 안내 (6자 이상, 두 칸이 같아야 함)
        if (f[4].isNotEmpty() && f[4].length < 6) Text("비밀번호는 6자 이상이어야 해요.", color = SosRed, fontSize = 11.sp)
        else if (f[5].isNotEmpty() && f[4] != f[5]) Text("비밀번호가 서로 달라요.", color = SosRed, fontSize = 11.sp)
    }
}

// 완료 화면: 점이 1개 → 2개 → 3개로 늘어나다가 체크 표시로 바뀌고, 잠시 뒤 다음 화면으로 이동
@Composable
fun DoneScreen(text: String, onFinish: () -> Unit) {
    var step by remember { mutableStateOf(1) } // 1~3: 보이는 점 개수, 4: 체크 표시
    LaunchedEffect(Unit) {
        delay(450); step = 2
        delay(450); step = 3
        delay(600); step = 4
        delay(1200); onFinish()
    }
    val soft = Color.White.copy(alpha = 0.6f)
    Box(Modifier.fillMaxSize().background(PrimaryCoral), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(110.dp).border(8.dp, soft, CircleShape), contentAlignment = Alignment.Center) {
                if (step < 4) {
                    // 점 3개 자리는 고정, 순서대로 나타남
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(3) { i ->
                            Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < step) soft else Color.Transparent))
                        }
                    }
                } else {
                    // 체크 표시
                    Canvas(Modifier.size(52.dp)) {
                        val w = 8.dp.toPx()
                        drawLine(soft, Offset(size.width * 0.05f, size.height * 0.55f), Offset(size.width * 0.38f, size.height * 0.88f), w, StrokeCap.Round)
                        drawLine(soft, Offset(size.width * 0.38f, size.height * 0.88f), Offset(size.width * 0.95f, size.height * 0.15f), w, StrokeCap.Round)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(text, color = TextCharcoal, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

// 이메일 입력
@Composable
fun ForgotEmailScreen(nav: Nav) {
    var email by remember { mutableStateOf("") }
    PinkPage("비밀번호 잊으셨나요?", onBack = { nav.back() }, headerHeight = 150.dp) {
        Spacer(Modifier.height(48.dp))
        Text("비밀번호를 잊으셨나요?", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.fillMaxWidth().padding(start = 4.dp))
        Spacer(Modifier.height(24.dp))
        SoftField("이메일", "가입할 때 쓴 이메일을 입력해 주세요", value = email, onChange = { email = it })
        Spacer(Modifier.height(32.dp))
        // 이메일을 입력해야 다음으로 넘어감
        PillButton("다음", Modifier.fillMaxWidth(0.5f), enabled = email.isNotBlank()) { nav.go(Screen.ForgotNew) }
    }
}

// 새 비밀번호 입력
@Composable
fun ForgotNewScreen(nav: Nav) {
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    PinkPage("새 비밀번호", onBack = { nav.back() }, headerHeight = 150.dp) {
        Spacer(Modifier.height(48.dp))
        SoftField("새 비밀번호", "비밀번호 입력", password = true, value = pw1, onChange = { pw1 = it })
        SoftField("새 비밀번호 재확인", "비밀번호 재입력", password = true, value = pw2, onChange = { pw2 = it })
        if (pw1.isNotEmpty() && pw1.length < 6) Text("비밀번호는 6자 이상으로 해주세요.", color = SosRed, fontSize = 12.sp)
        else if (pw2.isNotEmpty() && pw1 != pw2) Text("비밀번호가 일치하지 않아요.", color = SosRed, fontSize = 12.sp)
        Spacer(Modifier.height(80.dp))
        // 6자 이상이고 두 칸이 같아야 넘어감
        PillButton("비밀번호 변경", Modifier.fillMaxWidth(), enabled = pw1.length >= 6 && pw1 == pw2) { nav.go(Screen.ForgotDone) }
    }
}

// 회원가입 2단계: 보호자면 자녀 정보, 자녀면 보호자 정보 입력
@Composable
fun SignupInfoScreen(nav: Nav) {
    val who = if (signupIsGuardian) "자녀분" else "보호자"
    val g = remember { mutableStateListOf("", "", "") } // 성함, 전화번호, 생년월일
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    PinkPage(
        "회원가입", onBack = { nav.back() }, headerHeight = 130.dp,
        bottomContent = {
            error?.let { Text(it, color = SosRed, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            PillButton(if (loading) "가입 중..." else "회원가입", enabled = !loading && g.all { it.isNotBlank() }) {
                loading = true; error = null
                signupDraft.partnerName = g[0]; signupDraft.partnerPhone = g[1]; signupDraft.partnerBirth = g[2]
                AuthRepository.signup(signupDraft) { err ->
                    loading = false
                    if (err == null) nav.go(Screen.SignupDone) else error = err
                }
            }
            SmallLink("이미 계정이 있으신가요?") { nav.root(Screen.Login) }
        }
    ) {
        Spacer(Modifier.height(8.dp))
        SoftField("$who 성함", "홍길동", value = g[0], onChange = { g[0] = it })
        SoftField("$who 전화번호", "010-0000-0000", value = g[1], onChange = { g[1] = it })
        SoftField("$who 생년월일", "YYYY / DD / MM", value = g[2], onChange = { g[2] = it })
    }
}