package com.example.embraceapp


import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ───────── 색상 / 테마 ─────────

// true면 다크 테마. 값이 바뀌면 화면이 자동으로 다시 그려집니다
var isDarkTheme by mutableStateOf(false)

// 앱 전체에서 쓰는 색 (다크 모드면 어두운 색, 아니면 밝은 색)
val PrimaryCoral: Color get() = if (isDarkTheme) Color(0xFF9C5B62) else Color(0xFFFFC0C2) // 메인 분홍
val BgPeach: Color get() = if (isDarkTheme) Color(0xFF4A3235) else Color(0xFFFCE4E5)      // 연한 분홍(카드, 버튼)
val InactiveGray: Color get() = if (isDarkTheme) Color(0xFF2E2E31) else Color(0xFFECECEC) // 회색(입력칸)
val TextCharcoal: Color get() = if (isDarkTheme) Color(0xFFFFFFFF) else Color(0xFF1C1C1C) // 글자색
val SurfaceWhite: Color get() = if (isDarkTheme) Color(0xFF1B1B1D) else Color(0xFFFFFFFF) // 배경
val LinkBlue = Color(0xFF3866A9) // 파란 글씨(시간, 배터리)
val SosRed = Color(0xFFD64541)   // SOS 버튼
val GreenOk = Color(0xFF6FCF97)  // 초록(그래프, 스위치)

// ───────── 앱 시작점 ─────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 상태바 영역까지 화면 사용
        setContent {
            // 색을 따로 안 정한 글씨의 기본 색 지정
            CompositionLocalProvider(LocalContentColor provides TextCharcoal) {
                EmbraceApp()
            }
        }
    }
}

// 화면 목록
enum class Screen {
    Splash, AuthLaunch, Login,
    SignupType, SignupInfo, SignupDone,
    ForgotEmail, ForgotNew, ForgotDone,
    Home, Notifications, Map,
    Profile, EditProfile, EditDone, ChangePw, ChangePwDone,
    Settings, NotiSettings, PwSettings, PwSettingsDone, DeleteAccount
}

// 화면 이동 (뒤로가기 기록을 쌓아둠)
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
        if (nav.current == Screen.Splash) nav.root(Screen.AuthLaunch)
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
                Screen.ChangePwDone -> DoneScreen("비밀번호 변경 완료") { nav.root(Screen.Profile) }
                Screen.Settings -> SettingsScreen(nav)
                Screen.NotiSettings -> NotiSettingsScreen(nav)
                Screen.PwSettings -> PwSettingsScreen(nav)
                Screen.PwSettingsDone -> DoneScreen("비밀번호 변경 완료") { nav.root(Screen.Profile) }
                Screen.DeleteAccount -> DeleteAccountScreen(nav)
            }
        }
    }
}

// ───────── 공통 부품 ─────────

// 하단 탭 바 (지도 / 홈 / 프로필)
@Composable
fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val icons = listOf(Icons.Default.LocationOn, Icons.Default.Home, Icons.Default.Person)
    Surface(
        color = BgPeach,
        shape = RoundedCornerShape(topStart = 56.dp, topEnd = 56.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.navigationBarsPadding().padding(vertical = 14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            icons.forEachIndexed { i, icon ->
                Box(
                    Modifier
                        .size(56.dp)
                        .then(if (i == selected) Modifier.shadow(6.dp, RoundedCornerShape(18.dp)) else Modifier)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (i == selected) PrimaryCoral else Color.Transparent) // 선택된 탭만 분홍 박스
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) { Icon(icon, null, tint = TextCharcoal, modifier = Modifier.size(30.dp)) }
            }
        }
    }
}

// 오른쪽 위 알림 벨 버튼
@Composable
fun BellButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick, shape = CircleShape, color = BgPeach,
        shadowElevation = 4.dp, modifier = modifier.size(38.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Notifications, "알림", tint = Color(0xFF7D7D7D), modifier = Modifier.size(20.dp))
        }
    }
}

// 분홍 헤더 + 흰색 둥근 본문 틀 (대부분의 화면이 사용)
// bottomContent: 화면 아래에 고정할 버튼 영역
@Composable
fun PinkPage(
    title: String,
    onBack: (() -> Unit)? = null,
    onBell: (() -> Unit)? = null,
    headerHeight: androidx.compose.ui.unit.Dp = 84.dp,
    bottomContent: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(PrimaryCoral)) {
        // 헤더 (뒤로가기, 제목, 벨)
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().height(headerHeight).padding(horizontal = 16.dp)
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White)
                }
            }
            Text(
                title, modifier = Modifier.align(Alignment.Center),
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextCharcoal
            )
            if (onBell != null) BellButton(onBell, Modifier.align(Alignment.CenterEnd))
        }
        // 흰색 본문 영역
        Column(
            Modifier
                .fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(topStart = 56.dp, topEnd = 56.dp))
                .background(SurfaceWhite)
        ) {
            // 스크롤되는 내용
            Column(
                Modifier
                    .weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content
            )
            // 아래 고정 버튼
            if (bottomContent != null) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = bottomContent
                )
            }
        }
    }
}

// 회색 입력칸 (password = true면 "보기/숨김" 버튼 표시)
@Composable
fun SoftField(label: String, placeholder: String, password: Boolean = false) {
    var text by remember { mutableStateOf("") }   // 입력한 글자
    var show by remember { mutableStateOf(false) } // 비밀번호 보이기 여부
    val fieldShape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(label, fontSize = 12.sp, color = TextCharcoal, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
        TextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            placeholder = { Text(placeholder, fontSize = 13.sp, color = Color.Gray) },
            visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = {
                if (password) TextButton(onClick = { show = !show }) {
                    Text(if (show) "숨김" else "보기", fontSize = 12.sp, color = TextCharcoal)
                }
            },
            shape = fieldShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = InactiveGray, unfocusedContainerColor = InactiveGray,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = TextCharcoal, unfocusedTextColor = TextCharcoal
            ),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 10.dp, shape = fieldShape,
                    ambientColor = Color(0xFF000000), spotColor = Color(0xFF000000)
                )
        )
    }
}

// 둥근 알약 모양 버튼
@Composable
fun PillButton(
    text: String, modifier: Modifier = Modifier.fillMaxWidth(0.6f),
    color: Color = BgPeach, onClick: () -> Unit
) {
    Button(
        onClick = onClick, modifier = modifier.height(46.dp), shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = TextCharcoal),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

// 작은 글씨 링크 (비밀번호를 잊으셨나요 등등)
@Composable
fun SmallLink(text: String, onClick: () -> Unit) {
    Text(
        text, fontSize = 12.sp, color = TextCharcoal,
        modifier = Modifier.clickable { onClick() }.padding(8.dp)
    )
}

// 탭 선택 바 (호흡 / 기기 관리 / 비상 배기)
@Composable
fun SegTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val barShape = RoundedCornerShape(28.dp)
    val pillShape = RoundedCornerShape(23.dp)
    Row(
        Modifier.fillMaxWidth().shadow(5.dp, barShape).background(InactiveGray, barShape).padding(5.dp)
    ) {
        options.forEachIndexed { i, t ->
            val isSel = i == selected
            // 선택되면 색과 그림자가 부드럽게 바뀜
            val bg by androidx.compose.animation.animateColorAsState(
                if (isSel) BgPeach else Color.Transparent, label = "tabBg"
            )
            val elev by androidx.compose.animation.core.animateDpAsState(
                if (isSel) 6.dp else 0.dp, label = "tabElevation"
            )
            Box(
                Modifier
                    .weight(1f)
                    .shadow(elev, pillShape)
                    .clip(pillShape)
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(t, fontSize = 14.sp, color = TextCharcoal)
            }
        }
    }
}

// 메뉴 한 줄
@Composable
fun MenuRow(
    icon: ImageVector, text: String,
    trailing: @Composable () -> Unit = { Text("›", fontSize = 22.sp, color = TextCharcoal) },
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(BgPeach), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = TextCharcoal, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(text, Modifier.weight(1f), fontSize = 15.sp, color = TextCharcoal)
        trailing()
    }
}

// 켜고 끄는 스위치 한 줄 (알림 설정 화면)
@Composable
fun SwitchRow(text: String, initial: Boolean = true) {
    var on by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 15.sp, color = TextCharcoal)
        Switch(
            checked = on, onCheckedChange = { on = it },
            colors = SwitchDefaults.colors(checkedTrackColor = GreenOk, checkedThumbColor = Color.White)
        )
    }
}

// ───────── 시작 / 로그인 / 회원가입 ─────────

// 앱을 켜면 처음 2초간 보이는 로고 화면
@Composable
fun LaunchScreen() {
    Column(
        Modifier.fillMaxSize().background(PrimaryCoral).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Image(painterResource(id = R.drawable.embrace_logo), "앱 로고", Modifier.size(320.dp))
        Spacer(Modifier.height(124.dp)) // 다음 화면과 로고 위치를 맞추기 위한 빈 공간
    }
}

// 로그인 / 회원가입 선택 화면
@Composable
fun AuthLaunchScreen(nav: Nav) {
    Column(
        Modifier.fillMaxSize().background(PrimaryCoral).navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Image(painterResource(id = R.drawable.embrace_logo), "앱 로고", Modifier.size(320.dp))
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
            fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2F3B3B),
            modifier = Modifier.clickable { nav.go(Screen.ForgotEmail) }.padding(top = 8.dp, bottom = 8.dp)
        )
    }
}

// 로그인 화면
@Composable
fun LoginScreen(nav: Nav) {
    PinkPage("로그인", onBack = { nav.back() }, headerHeight = 150.dp) {
        Spacer(Modifier.height(40.dp))
        SoftField("이메일 계정", "example@example.com")
        SoftField("비밀번호", "비밀번호 입력", password = true)
        // 아이디 저장 / 자동 로그인 체크박스
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            var a by remember { mutableStateOf(false) }
            var b by remember { mutableStateOf(false) }
            Checkbox(a, { a = it }); Text("아이디 저장", fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            Checkbox(b, { b = it }); Text("자동 로그인", fontSize = 11.sp)
        }
        Spacer(Modifier.height(34.dp))
        PillButton("로그인") { nav.root(Screen.Home) } // 지금은 누르면 바로 홈으로 이동
        Spacer(Modifier.height(12.dp))
        PillButton("회원가입", color = InactiveGray) { nav.go(Screen.SignupType) }
        SmallLink("비밀번호를 잊으셨나요?") { nav.go(Screen.ForgotEmail) }
        Spacer(Modifier.height(40.dp))
        // 간편 로그인
        Text("또는 간편 로그인", fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("f", "G").forEach {
                Box(
                    Modifier.size(40.dp).border(1.5.dp, Color(0xFF5B8A8A), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text(it, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF5B8A8A)) }            }
        }
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallLink("회원가입") { nav.go(Screen.SignupType) }
            Text("·", fontSize = 12.sp, color = Color.Gray)
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
    PinkPage(
        "회원가입", onBack = { nav.back() }, headerHeight = 130.dp,
        bottomContent = {
            PillButton("다 음") { signupIsGuardian = type == 0; nav.go(Screen.SignupInfo) }
            SmallLink("이미 계정이 있으신가요?") { nav.root(Screen.Login) }
        }
    ) {
        Text("가입 유형", fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp))
        Box(Modifier.fillMaxWidth(0.6f)) { SegTabs(listOf("보호자", "자녀"), type) { type = it } }
        Spacer(Modifier.height(16.dp))
        SoftField("성함", "홍길동")
        SoftField("전화번호", "010-0000-0000")
        SoftField("생년월일", "YYYY / DD / MM")
        SoftField("이메일", "example@example.com")
        SoftField("비밀번호", "비밀번호 입력", password = true)
        SoftField("비밀번호 재확인", "비밀번호 재입력", password = true)
    }
}

// 완료 화면 (1.5초 뒤 자동으로 다음 화면 이동)
@Composable
fun DoneScreen(text: String, onFinish: () -> Unit) {
    LaunchedEffect(Unit) { delay(1500); onFinish() }
    Box(Modifier.fillMaxSize().background(PrimaryCoral), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(110.dp).border(8.dp, Color.White.copy(alpha = 0.6f), CircleShape)) {
                Box(Modifier.padding(start = 22.dp, top = 40.dp).size(14.dp).clip(CircleShape).background(Color.White))
            }
            Spacer(Modifier.height(24.dp))
            Text(text, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

// ───────── 비밀번호 찾기 ─────────

// 이메일 입력
@Composable
fun ForgotEmailScreen(nav: Nav) {
    PinkPage("비밀번호 잊으셨나요?", onBack = { nav.back() }, headerHeight = 150.dp) {
        Spacer(Modifier.height(48.dp))
        Text("비밀번호를 잊으셨나요?", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.fillMaxWidth().padding(start = 4.dp))
        Spacer(Modifier.height(24.dp))
        SoftField("이메일을 입력하세요", "example@example.com")
        Spacer(Modifier.height(32.dp))
        PillButton("다음", Modifier.fillMaxWidth(0.5f)) { nav.go(Screen.ForgotNew) }
    }
}

// 새 비밀번호 입력
@Composable
fun ForgotNewScreen(nav: Nav) {
    PinkPage("새 비밀번호", onBack = { nav.back() }, headerHeight = 150.dp) {
        Spacer(Modifier.height(48.dp))
        SoftField("새 비밀번호", "비밀번호 입력", password = true)
        SoftField("새 비밀번호 재확인", "비밀번호 재입력", password = true)
        Spacer(Modifier.height(80.dp))
        PillButton("비밀번호 변경", Modifier.fillMaxWidth()) { nav.go(Screen.ForgotDone) }
    }
}


// 홈 화면: 호흡 / 기기 관리 / 비상 배기
@Composable
fun HomeScreen(onBell: () -> Unit) {
    var tab by remember { mutableStateOf(0) }            // 선택된 탭 번호
    var sosDialog by remember { mutableStateOf(false) }  // SOS 팝업 표시 여부

    Column(Modifier.fillMaxSize().background(PrimaryCoral)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("안녕하세요, 이민주님", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextCharcoal)
                Text("등록아동: 이민우", fontSize = 13.sp, color = TextCharcoal)
            }
            BellButton(onBell)
        }
        VitalsCard()
        Spacer(Modifier.height(30.dp))
        // 흰색 영역: 탭 + 탭 내용
        Column(
            Modifier
                .fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(topStart = 56.dp, topEnd = 56.dp))
                .background(SurfaceWhite)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 26.dp)
        ) {
            SegTabs(listOf("호흡", "기기 관리", "비상 배기"), tab) { tab = it }
            Spacer(Modifier.height(26.dp))
            when (tab) {
                0 -> BreathTab()
                1 -> DeviceTab()
                else -> SosTab { sosDialog = true }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    // SOS 팝업
    if (sosDialog) {
        AlertDialog(
            onDismissRequest = { sosDialog = false },
            confirmButton = { TextButton(onClick = { sosDialog = false }) { Text("확인") } },
            title = { Text("비상 배기 실행") },
            text = { Text("조끼의 압력을 해제하고 있습니다.") }
        )
    }
}

// 위쪽 생체 정보 카드 (심박수, 호흡수, 조끼 압력)
@Composable
fun VitalsCard() {
    Surface(
        shape = RoundedCornerShape(28.dp), color = BgPeach, shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            // 왼쪽: 심박수 원형 그래프
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { 0.85f }, modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF6E6E6E), trackColor = Color.White, strokeWidth = 4.dp
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("85", fontSize = 22.sp, color = Color(0xFF7D7D7D), fontWeight = FontWeight.Bold)
                        Text("BPM", fontSize = 7.sp, color = Color(0xFF7D7D7D), modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("심박수", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextCharcoal)
            }
            Spacer(Modifier.width(16.dp))
            Box(Modifier.width(1.5.dp).height(116.dp).background(Color.White)) // 세로 구분선
            Spacer(Modifier.width(16.dp))
            // 오른쪽: 호흡수, 조끼 압력
            Column(Modifier.weight(1f)) {
                VitalLine(R.drawable.ic_breath, "호흡수", "20", "회/분")
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Color.White, thickness = 1.5.dp)
                Spacer(Modifier.height(16.dp))
                VitalLine(R.drawable.ic_pressure, "조끼 압력", "25", "mmHg")
            }
        }
    }
}

// 생체 정보 한 줄 (아이콘 + 이름 + 값 + 단위)
@Composable
fun VitalLine(iconRes: Int, label: String, value: String, unit: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(iconRes), null, tint = Color.Unspecified, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontSize = 12.sp, color = TextCharcoal)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextCharcoal)
                Spacer(Modifier.width(3.dp))
                Text(unit, fontSize = 12.sp, color = TextCharcoal, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

// 호흡 그래프 (초록 곡선, 지금은 예시 값)
@Composable
fun BreathChart() {
    val pts = listOf(0.35f, 0.6f, 0.3f, 0.55f, 0.35f, 0.4f, 0.95f, 0.5f) // 그래프 높이 값 (0~1)
    Canvas(Modifier.fillMaxWidth().height(115.dp)) {
        val w = size.width
        val h = size.height
        val step = w / (pts.size - 1)
        // 점들을 부드러운 곡선으로 연결
        val line = Path().apply {
            moveTo(0f, h * (1 - pts[0]))
            for (i in 1 until pts.size) {
                val x0 = (i - 1) * step; val x1 = i * step
                val y0 = h * (1 - pts[i - 1]); val y1 = h * (1 - pts[i])
                cubicTo((x0 + x1) / 2, y0, (x0 + x1) / 2, y1, x1, y1)
            }
        }
        // 곡선 아래 색 채우기
        val fill = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(fill, Brush.verticalGradient(listOf(GreenOk, Color.Transparent)))
        drawPath(line, GreenOk, style = Stroke(width = 4f))
    }
}

// [호흡] 탭
@Composable
fun BreathTab() {
    // 그래프
    Surface(
        shape = RoundedCornerShape(48.dp), color = PrimaryCoral, shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
            BreathChart()
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00.00", "04.00", "08.00", "12.00", "16.00", "20.00", "23.59").forEach {
                    Text(it, fontSize = 10.sp, color = TextCharcoal)
                }
            }
        }
    }
    Spacer(Modifier.height(26.dp))
    // 왼쪽: 호흡 수, 오른쪽: 호흡비
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Surface(
            shape = RoundedCornerShape(48.dp), color = InactiveGray, shadowElevation = 6.dp,
            modifier = Modifier.weight(1f).height(170.dp)
        ) {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("20", fontSize = 48.sp, color = TextCharcoal)
                    Text(" BPM", fontSize = 13.sp, color = TextCharcoal, modifier = Modifier.padding(bottom = 10.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text("호흡 수", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextCharcoal)
            }
        }
        Surface(
            shape = RoundedCornerShape(48.dp), color = BgPeach, shadowElevation = 6.dp,
            modifier = Modifier.weight(1f).height(170.dp)
        ) {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("1 : 2.0", fontSize = 48.sp, color = TextCharcoal, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                Text("시간당 호흡비", fontSize = 13.sp, color = TextCharcoal)
                Text("(호기:흡기)", fontSize = 13.sp, color = TextCharcoal)
            }
        }
    }
}

// [기기 관리] 탭: 기기 검색 바 + 연결된 기기 목록 + 자동 재연결
@Composable
fun DeviceTab() {
    // 기기 검색 바
    Surface(
        shape = RoundedCornerShape(30.dp), color = Color(0xFFF8DADA), shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_bluetooth), null, tint = Color.Unspecified, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text("검색된 기기 목록", Modifier.weight(1f), fontSize = 14.sp, color = Color(0xFF8A8A8A))
            Row(Modifier.clickable { }, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_refresh), null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text("다시 스캔", fontSize = 14.sp, color = Color(0xFF8A8A8A))
            }
        }
    }
    Spacer(Modifier.height(22.dp))
    // 연결된 기기 (지금은 예시)
    DeviceRow(R.drawable.ic_watch, "Galaxy Watch", "88%")
    Spacer(Modifier.height(14.dp))
    DeviceRow(R.drawable.ic_vest, "ESP 32-Vest", "70%")
    Spacer(Modifier.height(24.dp))
    // 자동 재연결 스위치
    Surface(
        shape = RoundedCornerShape(28.dp), color = BgPeach, shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("자동 재연결 활성화", Modifier.weight(1f), fontSize = 14.sp, color = Color(0xFF8A8A8A))
            var on by remember { mutableStateOf(true) }
            Switch(on, { on = it }, colors = SwitchDefaults.colors(checkedTrackColor = GreenOk, checkedThumbColor = Color.White))
        }
    }
}

// [비상 배기] 탭: 길게 누르면 작동하는 SOS 버튼
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SosTab(onSos: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(190.dp).clip(CircleShape).background(PrimaryCoral.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(150.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(SosRed)
                    .combinedClickable(onClick = { }, onLongClick = onSos), // 길게 눌렀을 때만 실행
                contentAlignment = Alignment.Center
            ) { Text("SOS", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold) }
        }
        Spacer(Modifier.height(12.dp))
        Text("길게 누르면 비상배기", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextCharcoal)
    }
}

// ───────── 알림 / 지도 ─────────

// 알림 한 개 (아이콘 + 제목 + 내용 + 시간)
@Composable
fun NotiItem(icon: ImageVector, title: String, msg: String, time: String, extra: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(BgPeach), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = TextCharcoal)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextCharcoal)
            Text(msg, fontSize = 14.sp, color = TextCharcoal)
            if (extra != null) Text(extra, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = LinkBlue) // 위치 정보
            Text(time, fontSize = 11.sp, color = LinkBlue, modifier = Modifier.align(Alignment.End).padding(top = 6.dp))
        }
    }
    HorizontalDivider(color = Color(0xFF9DBDF0))
}

// 알림 화면 (지금은 예시 알림)
@Composable
fun NotificationScreen(nav: Nav) {
    PinkPage("알림", onBack = { nav.back() }, onBell = { }) {
        Text("오늘", Modifier.fillMaxWidth(), fontSize = 14.sp)
        NotiItem(Icons.Default.Notifications, "경고!", "과부화 징후 발견", "17:00 - 10월 25일")
        Text("어제", Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 14.sp)
        NotiItem(Icons.Default.Notifications, "안정", "감각 과부하 감지 후 자동 압박이 실행되어\n심박수가 정상 범위로 복귀되었습니다.", "16:58 - 10월 24일")
        NotiItem(Icons.Default.Warning, "위험!", "과부화 발생", "17:00 - 10월 24일", extra = "위치: 흑석동 흑석로3길 29-1")
        NotiItem(Icons.Default.Notifications, "경고!", "과부화 징후 발견", "16:58 - 10월 24일")
    }
}

// 지도 화면 (지금은 사진임)
@Composable
fun MapScreen() {
    Box(Modifier.fillMaxSize().background(SurfaceWhite)) {
        // 지도 사진
        Image(
            painter = painterResource(id = R.drawable.map_sample),
            contentDescription = "지도",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 현재 위치
        Box(
            Modifier.align(androidx.compose.ui.BiasAlignment(0.09f, 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(80.dp).clip(CircleShape).background(Color(0xFF4A7BFF).copy(alpha = 0.28f)))
            Box(
                Modifier.size(16.dp).clip(CircleShape)
                    .background(Color(0xFF3B78F5))
                    .border(2.dp, Color.White, CircleShape)
            )
        }

        // 내 위치 버튼 (오른쪽)
        Surface(
            shape = CircleShape, color = SurfaceWhite, shadowElevation = 6.dp,
            modifier = Modifier
                .align(androidx.compose.ui.BiasAlignment(0.88f, 0.17f))
                .size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(20.dp).border(2.dp, TextCharcoal, CircleShape))
                Box(Modifier.size(7.dp).clip(CircleShape).background(TextCharcoal))
            }
        }

        // 최근 위치 알림
        Surface(
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = SurfaceWhite,
            shadowElevation = 12.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(CircleShape).background(Color.LightGray))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(BgPeach),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.LocationOn, null, tint = Color.Gray)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("솔빛초 통학로 진입", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextCharcoal)
                        Text("5분 전", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(96.dp)) // 하단 탭 바 뒤까지 흰색 유지
            }
        }
    }
}

// ───────── 마이페이지 ─────────

// 프로필 화면 (아바타는 분홍/흰색 경계에 걸치게 배치)
@Composable
fun ProfileScreen(nav: Nav) {
    var logoutDialog by remember { mutableStateOf(false) } // 로그아웃 팝업 표시 여부

    Column(Modifier.fillMaxSize().background(PrimaryCoral)) {
        // 분홍 헤더
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().height(84.dp).padding(horizontal = 16.dp)
        ) {
            IconButton(onClick = { nav.back() }, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White)
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
                Text("이민주", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextCharcoal)
                Text("등록 아동: 25030024 이민우", fontSize = 12.sp)
                Spacer(Modifier.height(32.dp))
                // 메뉴 4개
                MenuRowRes(R.drawable.ic_profile_edit, "프로필 수정") { nav.go(Screen.EditProfile) }
                MenuRowRes(R.drawable.ic_shield, "비밀번호 변경") { nav.go(Screen.ChangePw) }
                MenuRowRes(R.drawable.ic_settings, "설정") { nav.go(Screen.Settings) }
                MenuRowRes(R.drawable.ic_logout, "로그아웃") { logoutDialog = true }
            }

            // 아바타 (분홍과 흰색 경계에 걸쳐 있음)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(104.dp)
                    .shadow(8.dp, RoundedCornerShape(30.dp))
                    .clip(RoundedCornerShape(30.dp))
                    .background(SurfaceWhite),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, null, Modifier.size(64.dp), tint = Color.Gray)
            }
        }
    }

    // 로그아웃 확인 팝업
    if (logoutDialog) {
        ConfirmDialog(
            title = "로그아웃 알림",
            question = "정말로 로그아웃 하시겠습니까?",
            confirmText = "로그아웃",
            onConfirm = { logoutDialog = false; nav.root(Screen.AuthLaunch) },
            onCancel = { logoutDialog = false }
        )
    }
}

// 프로필 수정 화면
@Composable
fun EditProfileScreen(nav: Nav) {
    PinkPage("프로필 수정", onBack = { nav.back() }, onBell = { nav.go(Screen.Notifications) }) {
        Box(Modifier.size(84.dp).shadow(6.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(BgPeach), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Person, null, Modifier.size(52.dp), tint = Color.Gray)
        }
        Spacer(Modifier.height(10.dp))
        Text("이민주", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text("등록 아동: 25030024 이민우", fontSize = 11.sp)
        Spacer(Modifier.height(20.dp))
        Text("계정 설정", Modifier.fillMaxWidth().padding(bottom = 12.dp), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        SoftField("사용자 이름", "이민주")
        SoftField("전화번호", "+82 10-1111-2222")
        SoftField("이메일 주소", "example@example.com")
        Spacer(Modifier.height(24.dp))
        PillButton("프로필 수정") { nav.go(Screen.EditDone) }
    }
}

// 비밀번호 변경 화면 (프로필에서 접근)
@Composable
fun ChangePwScreen(nav: Nav) {
    PinkPage("비밀번호 변경", onBack = { nav.back() }, onBell = { nav.go(Screen.Notifications) }) {
        SoftField("현재 비밀번호", "현재 비밀번호", password = true)
        SoftField("새 비밀번호", "새 비밀번호", password = true)
        SoftField("새 비밀번호 확인", "새 비밀번호 확인", password = true)
        Spacer(Modifier.height(24.dp))
        PillButton("비밀번호 변경") { nav.go(Screen.ChangePwDone) }
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
            Switch(isDarkTheme, { isDarkTheme = it }, colors = SwitchDefaults.colors(checkedTrackColor = GreenOk, checkedThumbColor = Color.White))
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

// 알림 설정 화면
@Composable
fun NotiSettingsScreen(nav: Nav) {
    PinkPage("알림 설정", onBack = { nav.back() }, onBell = { nav.go(Screen.Notifications) }) {
        Spacer(Modifier.height(8.dp))
        SwitchRow("푸쉬 알림 설정")
        SwitchRow("무음 설정")
        SwitchRow("진동(햅틱) 설정")
    }
}

// 비밀번호 설정 화면 (설정에서 접근)
@Composable
fun PwSettingsScreen(nav: Nav) {
    PinkPage("비밀번호 설정", onBack = { nav.back() }, onBell = { nav.go(Screen.Notifications) }) {
        SoftField("현재 비밀번호", "현재 비밀번호", password = true)
        SoftField("새 비밀번호", "새 비밀번호", password = true)
        SoftField("새 비밀번호 확인", "새 비밀번호 확인", password = true)
        Spacer(Modifier.height(24.dp))
        PillButton("비밀번호 변경") { nav.go(Screen.PwSettingsDone) }
    }
}

// 계정 삭제 화면
@Composable
fun DeleteAccountScreen(nav: Nav) {
    var dialog by remember { mutableStateOf(false) } // 확인 팝업 표시 여부
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
        SoftField("비밀번호", "비밀번호 입력", password = true)
        Spacer(Modifier.height(8.dp))
        PillButton("계정 삭제") { dialog = true }
        Spacer(Modifier.height(10.dp))
        PillButton("취 소", color = InactiveGray) { nav.back() }
    }
}

// 회원가입 2단계: 보호자면 자녀 정보, 자녀면 보호자 정보 입력
@Composable
fun SignupInfoScreen(nav: Nav) {
    val who = if (signupIsGuardian) "자녀분" else "보호자"
    PinkPage(
        "회원가입", onBack = { nav.back() }, headerHeight = 130.dp,
        bottomContent = {
            PillButton("회원가입") { nav.go(Screen.SignupDone) }
            SmallLink("이미 계정이 있으신가요?") { nav.root(Screen.Login) }
        }
    ) {
        Spacer(Modifier.height(8.dp))
        SoftField("$who 성함", "홍길동")
        SoftField("$who 전화번호", "010-0000-0000")
        SoftField("$who 생년월일", "YYYY / DD / MM")
    }
}

// 메뉴 한 줄 (내가 넣은 이미지 아이콘 사용, 프로필 화면용)
@Composable
fun MenuRowRes(iconRes: Int, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFF9E6E8)),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(iconRes), null, tint = Color.Unspecified, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextCharcoal)
    }
}

// 확인/취소 팝업 (로그아웃, 계정 삭제에서 사용)
@Composable
fun ConfirmDialog(
    title: String,
    question: String,
    body: String? = null,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(20.dp), color = SurfaceWhite) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextCharcoal)
                Spacer(Modifier.height(16.dp))
                Text(question, fontSize = 13.sp, textAlign = TextAlign.Center, color = TextCharcoal)
                if (body != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(body, fontSize = 12.sp, textAlign = TextAlign.Center, color = TextCharcoal)
                }
                Spacer(Modifier.height(20.dp))
                PillButton(confirmText, Modifier.fillMaxWidth(0.8f), onClick = onConfirm)
                Spacer(Modifier.height(10.dp))
                PillButton("취소", Modifier.fillMaxWidth(0.8f), InactiveGray, onClick = onCancel)
            }
        }
    }
}

// 기기 한 줄 (아이콘 + 이름 + 배터리 + 연결 해제 버튼)
@Composable
fun DeviceRow(iconRes: Int, name: String, battery: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(50.dp).shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(BgPeach),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(iconRes), null, tint = Color.Unspecified, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(name, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextCharcoal, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(1.5.dp).height(40.dp).background(PrimaryCoral)) // 세로 구분선
        Spacer(Modifier.width(8.dp))
        Icon(painterResource(R.drawable.ic_battery), null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text(battery, fontSize = 14.sp, color = LinkBlue, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(1.5.dp).height(40.dp).background(PrimaryCoral)) // 세로 구분선
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryCoral, contentColor = Color.White),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
            modifier = Modifier.height(32.dp)
        ) { Text("연결 해제", fontSize = 11.sp, maxLines = 1) }
    }
}