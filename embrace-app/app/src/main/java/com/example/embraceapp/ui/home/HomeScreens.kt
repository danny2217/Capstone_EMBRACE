package com.example.embraceapp.ui.home

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
import androidx.compose.ui.graphics.StrokeJoin
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
import kotlinx.coroutines.launch
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
import com.example.embraceapp.wear.WatchLink
import com.example.embraceapp.util.*
import com.example.embraceapp.ui.*
import com.example.embraceapp.ui.theme.*
import com.example.embraceapp.ui.components.*
import com.example.embraceapp.ui.auth.*
import com.example.embraceapp.ui.notification.*
import com.example.embraceapp.ui.map.*
import com.example.embraceapp.ui.profile.*

// ───────── 홈 (호흡 / 기기 관리 / 비상 배기) ─────────

// 홈 화면: 호흡 / 기기 관리 / 비상 배기
@Composable
fun HomeScreen(onBell: () -> Unit) {
    val me by AuthRepository.profile.collectAsState() // 로그인한 사람 정보
    var tab by remember { mutableStateOf(0) }            // 선택된 탭 번호
    var sosDialog by remember { mutableStateOf(false) }  // SOS 팝업 표시 여부
    var sosSent by remember { mutableStateOf(false) }    // 배기 명령을 조끼에 실제로 보냈는지
    var sosRemote by remember { mutableStateOf(false) }  // 보호자 폰에서 서버로 보낸 원격 배기인지
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(PrimaryCoral)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("안녕하세요, ${me?.name ?: ""}님", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = TextCharcoal)
                Text(relationLabel(me), fontSize = 13.sp, color = TextCharcoal)
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
                // 옆으로 밀면 탭 넘기기: 왼쪽으로 밀면 다음 탭, 오른쪽으로 밀면 이전 탭
                .pointerInput(Unit) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onHorizontalDrag = { _, amount -> dragged += amount },
                        onDragEnd = {
                            if (dragged < -120f && tab < 2) tab += 1
                            else if (dragged > 120f && tab > 0) tab -= 1
                        }
                    )
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 26.dp)
        ) {
            SegTabs(listOf("호흡", "기기 관리", "비상 배기"), tab) { tab = it }
            Spacer(Modifier.height(26.dp))
            // 탭 내용이 옆으로 미끄러지며 바뀜 (다음 탭이면 오른쪽에서, 이전 탭이면 왼쪽에서 들어옴)
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    if (targetState > initialState)
                        (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it } + fadeOut())
                    else
                        (slideInHorizontally { -it } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                },
                label = "homeTab"
            ) { t ->
                // 탭 안의 내용이 위에서 아래로 쌓이도록 Column으로 감싸야 함 (안 감싸면 겹쳐 보임)
                Column(Modifier.fillMaxWidth()) {
                    when (t) {
                        0 -> BreathTab()
                        1 -> DeviceTab()
                        else -> SosTab {
                            if (me?.role == "child") { sosRemote = false; sosSent = VestConnection.exhaust(); sosDialog = true } // 자녀 폰: 조끼로 바로 배기 명령
                            else scope.launch { sosRemote = true; sosSent = ServerSync.requestRemoteExhaust(); sosDialog = true } // 보호자 폰: 서버 경유 원격 배기
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    // SOS 팝업
    if (sosDialog) {
        AlertDialog(
            onDismissRequest = { sosDialog = false },
            confirmButton = { TextButton(onClick = { sosDialog = false }) { Text("확인") } },
            title = { Text(if (sosSent) (if (sosRemote) "원격 비상 배기를 요청했어요" else "비상 배기 명령을 보냈어요") else "명령을 보내지 못했어요") },
            text = {
                Text(
                    when {
                        sosSent && sosRemote -> "자녀 폰이 조끼에 연결되어 있으면 몇 초 안에 조끼로 전달돼요. 1분이 지나도 전달되지 않으면 요청은 취소돼요."
                        sosSent -> "조끼의 압력을 해제하고 있습니다."
                        sosRemote -> "서버에 연결하지 못했어요. 자녀 곁에서 조끼의 비상 배기 방법으로 직접 압력을 해제해 주세요."
                        else -> "조끼와 연결되어 있지 않아요. 조끼의 비상 배기 방법으로 직접 압력을 해제해 주세요."
                    }
                )
            }
        )
    }
}

// 위쪽 생체 정보 카드 (심박수, 호흡수, 조끼 압력)
@Composable
fun VitalsCard() {
    val sensor by SensorRepository.state.collectAsState()
    val vest = sensor.vest // 조끼에서 받은 실제 값 (연결 전엔 null → "--" 표시)
    // 심박수: 워치 값을 우선 사용 (12초 넘게 새 값이 없으면 끊김으로 보고 조끼가 보낸 값으로)
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    val w = sensor.watchHr
    val watchBpm = if (w != null && w.valid && now - w.receivedAt < 12_000) w.bpm else 0
    val bpm = if (watchBpm > 0) watchBpm else (vest?.bpm ?: 0)
    val breath = if (vest != null && vest.breathValid) Math.round(vest.breathRate) else null
    Surface(
        shape = RoundedCornerShape(28.dp), color = BgPeach, shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            // 왼쪽: 심박수 원형 그래프
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { (bpm / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF6E6E6E), trackColor = Color.White, strokeWidth = 4.dp
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (bpm > 0) "$bpm" else "--", fontSize = 22.sp, color = Color(0xFF7D7D7D), fontWeight = FontWeight.Bold)
                        Text("BPM", fontSize = 7.sp, color = Color(0xFF7D7D7D), modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("심박수", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextCharcoal)
            }
            Spacer(Modifier.width(16.dp))
            Box(Modifier.width(1.5.dp).height(116.dp).background(Color.White)) // 세로 구분선
            Spacer(Modifier.width(16.dp))
            // 오른쪽: 호흡수, 조끼 상태
            Column(Modifier.weight(1f)) {
                VitalLine(R.drawable.ic_breath, "호흡수", breath?.toString() ?: "--", "회/분")
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Color.White, thickness = 1.5.dp)
                Spacer(Modifier.height(16.dp))
                VitalLine(R.drawable.ic_pressure, "조끼 상태", vest?.stateName ?: "--", "")
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

// 호흡 그래프: 조끼가 25 Hz 로 보내는 호흡 센서 값을 PC 프로그램과 같은 필터
//   (0.08~0.7 Hz 대역통과, BreathWave.kt)로 걸러서 최근 20 초를 그린다. 위로 올라가면 들숨, 내려가면 날숨.
//   ⚠ 조끼 펌웨어가 호흡 파형(WAVE 특성 e3b70005)을 보내야 그려진다. 안 보내면 이유를 글로 보여 준다.
const val BREATH_WAVE_SEC = 20

@Composable
fun BreathChart() {
    val wave by BreathWave.wave.collectAsState()
    val link by VestConnection.link.collectAsState()
    val hasWaveChar by VestConnection.hasWave.collectAsState()
    // 화면이 새로 그려질 때마다 현재 시각 갱신 → 파형이 부드럽게 왼쪽으로 흐름
    val now by produceState(System.currentTimeMillis()) {
        while (true) withFrameMillis { value = System.currentTimeMillis() }
    }
    val live = wave.ys.size >= 2 && now - wave.lastAt < 2_000

    Column {
        Box(Modifier.fillMaxWidth().height(115.dp), contentAlignment = Alignment.Center) {
            when {
                live -> BreathWaveCanvas(wave, now)
                link != VestLink.Connected -> Text("조끼를 연결하면 호흡 그래프가 나와요", fontSize = 13.sp, color = TextCharcoal)
                !hasWaveChar -> Text("조끼 펌웨어가 호흡 파형을 보내지 않아요\n(펌웨어에 파형 전송 기능 추가 필요)",
                                     fontSize = 12.sp, color = TextCharcoal, textAlign = TextAlign.Center)
                else -> Text("호흡 파형 받는 중...", fontSize = 13.sp, color = TextCharcoal)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("20초 전", "15초", "10초", "5초", "지금").forEach { Text(it, fontSize = 10.sp, color = TextCharcoal) }
        }
    }
}

// 실시간 파형 (필터 거친 값, 위 = 들숨) — 예전 예시 그래프와 같은 모양: 부드러운 곡선 + 아래로 옅어지는 채우기
@Composable
private fun BreathWaveCanvas(wave: BreathWave.Snapshot, now: Long) {
    val per = BreathWave.FS.toInt()                    // 초당 샘플 수
    val span = BREATH_WAVE_SEC * per                   // 화면에 보이는 샘플 수
    val ys = wave.ys.copyOfRange(maxOf(0, wave.ys.size - span - per), wave.ys.size)
    // 마지막 패킷 이후 흐른 시간만큼 왼쪽으로 밀어서 부드럽게 흐르게 (최대 한 패킷 분량)
    val shift = ((now - wave.lastAt) / (1000f / per)).coerceIn(0f, per / 5f + 1f)
    val finite = ys.filter { !it.isNaN() }
    if (finite.isEmpty()) {
        Text("호흡 센서를 확인해 주세요", fontSize = 13.sp, color = TextCharcoal)
        return
    }
    // 세로 범위: 보이는 값의 최소~최대 (최소 폭 20 mV → 숨을 안 쉴 때 잡음이 크게 부풀지 않게)
    val mid = (finite.max() + finite.min()) / 2f
    val half = maxOf(10f, (finite.max() - finite.min()) / 2f) * 1.25f
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val dx = w / span
        val last = ys.size - 1
        fun x(i: Int) = w - (last - i + shift) * dx
        fun y(v: Float) = h * (0.5f - (v - mid) / (2f * half))

        // 점 목록을 구간으로 나눔. 1 초보다 짧은 빈칸(센서 순간 이상)은 이어서 그리고, 긴 빈칸에서만 끊는다
        val segments = mutableListOf<MutableList<Offset>>()
        var cur = mutableListOf<Offset>()
        var gap = 0
        for (i in ys.indices) {
            val v = ys[i]
            val xi = x(i)
            if (xi < -dx) continue
            if (v.isNaN()) { gap++; continue }
            if (gap > per && cur.isNotEmpty()) { segments.add(cur); cur = mutableListOf() }
            gap = 0
            cur.add(Offset(xi, y(v)))
        }
        if (cur.isNotEmpty()) segments.add(cur)

        for (pts in segments) {
            if (pts.size < 2) continue
            // 부드러운 곡선 (예전 예시 그래프와 같은 3차 곡선 연결)
            val line = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (k in 1 until pts.size) {
                    val p0 = pts[k - 1]; val p1 = pts[k]
                    val cx = (p0.x + p1.x) / 2
                    cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                }
            }
            // 곡선 아래를 위에서 아래로 옅어지게 채움
            val fill = Path().apply {
                addPath(line)
                lineTo(pts.last().x, h)
                lineTo(pts.first().x, h)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(ChartLine.copy(alpha = 0.40f), Color.Transparent)))
            drawPath(line, ChartLine, style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// [호흡] 탭
@Composable
fun BreathTab() {
    val sensor by SensorRepository.state.collectAsState()
    // 그래프
    Surface(
        shape = RoundedCornerShape(48.dp), color = PrimaryCoral, shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
            BreathChart()
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
                    Text(sensor.vest?.takeIf { it.breathValid }?.let { Math.round(it.breathRate).toString() } ?: "--", fontSize = 48.sp, color = TextCharcoal)
                    Text(" 회/분", fontSize = 13.sp, color = TextCharcoal, modifier = Modifier.padding(bottom = 10.dp))
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
                Text(sensor.vest?.takeIf { it.breathValid && it.ieRatio > 0f }?.let { String.format("1 : %.1f", 1f / it.ieRatio) } ?: "--", fontSize = 48.sp, color = TextCharcoal, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                Text("호흡비 (최근 1분)", fontSize = 13.sp, color = TextCharcoal)
                Text("(흡기:호기)", fontSize = 13.sp, color = TextCharcoal)
            }
        }
    }
}

// [기기 관리] 탭
//  · 자녀 폰: 조끼(EMBRACE-VEST)를 BLE 로 찾아 연결. 워치는 BLE 로 연결하지 않는다
//            (Galaxy Wearable 페어링 + 워치 앱 [시작] → Data Layer 로 심박이 자동으로 들어옴)
//  · 보호자 폰: 조끼·워치에 직접 붙지 않는다. 서버에 올라온 자녀 쪽 상태만 보여 줌
@Composable
fun DeviceTab() {
    val me by AuthRepository.profile.collectAsState()
    val isChild = me?.role == "child"
    val sensor by SensorRepository.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    // 블루투스 권한 (안드로이드 12 이상: 주변 기기 검색/연결, 11 이하: 위치)
    val btPerms = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val requestBt = rememberPermissionRequester(
        permissions = btPerms,
        title = "블루투스 권한",
        message = "조끼를 찾고 연결하려면 주변 기기 권한이 필요해요."
    ) { ok ->
        // 권한이 허용되면 기기 검색 시작. 블루투스가 꺼져 있으면 안내
        if (ok && !BleScanner.start(ctx))
            android.widget.Toast.makeText(ctx, "블루투스를 켜 주세요", android.widget.Toast.LENGTH_SHORT).show()
    }
    val scanning by BleScanner.scanning.collectAsState() // 검색 중인지
    val found by BleScanner.found.collectAsState()       // 검색된 기기 목록
    val link by VestConnection.link.collectAsState()           // 조끼 연결 상태
    val connAddress by VestConnection.address.collectAsState() // 연결 중/연결된 조끼 주소
    val vestMsg by VestConnection.message.collectAsState()     // 연결 실패 등 안내 문구
    val statusCount by VestConnection.statusCount.collectAsState() // 조끼 상태 수신 횟수
    val watchNode by WatchLink.nodeName.collectAsState()   // 폰과 연결된 워치 이름
    val watchChecked by WatchLink.checked.collectAsState()
    val watchPaused by WatchLink.paused.collectAsState()    // [연결 해제] 눌렀는지
    var scanned by remember { mutableStateOf(false) }    // 한 번이라도 검색했는지
    // 자녀 폰: 워치 연결 여부를 10초마다 확인
    LaunchedEffect(isChild) { while (isChild) { WatchLink.refresh(ctx); delay(10_000) } }

    if (isChild) {
        // 조끼 검색 바
        Surface(
            shape = RoundedCornerShape(30.dp), color = BgPeach, shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_bluetooth), null, tint = IconGray, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Text("조끼 찾기", Modifier.weight(1f), fontSize = 14.sp, color = TextCharcoal)
                // 다시 스캔 버튼: 검색 중에는 빙글빙글 돌고 눌리지 않음
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = !scanning) { scanned = true; requestBt() }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (scanning) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = AccentBlue, strokeWidth = 2.dp)
                    } else {
                        Icon(painterResource(R.drawable.ic_refresh), null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(if (scanning) "스캔 중..." else "다시 스캔", fontSize = 14.sp, color = TextCharcoal)
                }
            }
        }
        // 검색 결과: 조끼만 연결 버튼을 보여 준다 (워치·에어컨 등 다른 BLE 기기는 조끼가 아니라 연결하면 실패함)
        val vests = found.filter { it.isVest }.sortedByDescending { it.signal }
        val others = found.size - vests.size
        if (scanned || found.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            if (vests.isEmpty()) {
                Text(
                    if (scanning) "조끼(${VestConnection.VEST_NAME})를 찾는 중이에요..."
                    else "조끼(${VestConnection.VEST_NAME})가 보이지 않아요. 조끼 전원이 켜져 있는지, 다른 폰이 이미 조끼에 연결돼 있지 않은지 확인해 주세요.",
                    fontSize = 12.sp, color = TextCharcoal, modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            vests.forEach { d ->
                FoundDeviceRow(d.name, d.address, d.signal, link, connAddress)
                Spacer(Modifier.height(10.dp))
            }
            if (others > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "조끼가 아닌 블루투스 기기 ${others}개는 숨겼어요. 워치는 여기서 연결하지 않아요 (아래 워치 항목 참고).",
                    fontSize = 11.sp, color = IconGray, modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
        // 연결 안내 (실패 이유 / 상태 수신 확인)
        vestMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = SosRed, modifier = Modifier.padding(horizontal = 8.dp))
        }
        Spacer(Modifier.height(22.dp))
    }

    // ── 워치 / 조끼 상태 카드 (실제 값) ──
    val w = sensor.watchHr
    val hrFresh = w != null && now - w.receivedAt < 15_000 // 15초 안에 심박이 왔으면 "수신 중"
    val hrAgo = w?.let { ((now - it.receivedAt) / 1000).coerceAtLeast(0) }
    val watchStatus = when {
        isChild && watchPaused -> "연결 해제됨 · 심박을 받지 않아요 ([연결]을 누르면 다시 받아요)"
        hrFresh && w!!.valid -> "심박 수신 중 · ${w.bpm} BPM"
        hrFresh -> "워치 앱 연결됨 · 심박 측정 중..."
        !isChild -> if (w == null) "자녀 워치 심박이 아직 없어요" else "최근 심박 ${hrAgo}초 전 · 자녀 폰 확인 필요"
        watchNode == null && watchChecked -> "연결 안 됨 · Galaxy Wearable 앱에서 워치를 폰에 연결해 주세요"
        watchNode == null -> "워치 확인 중..."
        w == null -> "워치 연결됨 · 워치 앱(EMBRACE)에서 [시작]을 눌러 주세요"
        else -> "워치 연결됨 · 심박이 ${hrAgo}초째 안 와요. 워치 앱을 확인해 주세요"
    }
    DeviceRow(
        iconRes = R.drawable.ic_watch,
        name = if (isChild) (watchNode ?: "Galaxy Watch") else "자녀 워치",
        status = watchStatus,
        ok = hrFresh && !(isChild && watchPaused),
        battery = w?.battery?.takeIf { hrFresh && !(isChild && watchPaused) }?.let { "$it%" },
        buttonText = when {
            !isChild -> null
            watchPaused -> "연결"
            watchNode != null -> "연결 해제"
            else -> "새로고침"          // 폰과 연결된 워치가 아직 없을 때만: 다시 찾아보기
        },
        onButton = {
            when {
                watchPaused -> WatchLink.connect(ctx)
                watchNode != null -> WatchLink.disconnect()
                else -> WatchLink.refresh(ctx)
            }
        }
    )
    Spacer(Modifier.height(14.dp))
    val vest = sensor.vest
    val vestStatus = if (isChild) when (link) {
        VestLink.Connected -> "연결됨 · ${vest?.stateName ?: "상태 대기"} · 수신 ${statusCount}회"
        VestLink.Connecting -> "연결 중..."
        VestLink.Disconnected -> "연결 안 됨 · 위 [다시 스캔]으로 조끼를 찾아 주세요"
    } else {
        if (vest != null) "자녀 폰과 연결됨 · ${vest.stateName}" else "최근 조끼 상태가 없어요 (자녀 폰·조끼 확인)"
    }
    DeviceRow(
        iconRes = R.drawable.ic_vest,
        name = VestConnection.VEST_NAME,
        status = vestStatus,
        ok = if (isChild) link == VestLink.Connected else vest != null,
        battery = null, // 조끼 펌웨어가 배터리 값을 보내지 않음
        buttonText = if (isChild && link != VestLink.Disconnected) "연결 해제" else null,
        onButton = { VestConnection.disconnect() }
    )
    if (isChild) {
        Spacer(Modifier.height(24.dp))
        // 자동 재연결 스위치
        Surface(
            shape = RoundedCornerShape(28.dp), color = BgPeach, shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("자동 재연결 활성화", Modifier.weight(1f), fontSize = 14.sp, color = TextCharcoal)
                var on by remember { mutableStateOf(true) }
                Switch(on, { on = it }, colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue, checkedThumbColor = Color.White))
            }
        }
    } else {
        Spacer(Modifier.height(16.dp))
        Text(
            "보호자 폰은 조끼·워치에 직접 연결하지 않아요. 자녀 폰(자녀 계정으로 로그인)이 조끼와 워치를 연결하고 서버로 올린 값을 여기서 봐요.",
            fontSize = 11.sp, color = IconGray, modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

// 검색된 기기 한 줄 (이름 + 주소 + 신호 세기 + 연결 버튼)
@Composable
fun FoundDeviceRow(name: String, address: String, signal: Int, link: VestLink, connAddress: String?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val mine = connAddress == address // 지금 이 줄의 기기가 연결 중/연결된 기기인지
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_bluetooth), null, tint = IconGray, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextCharcoal, maxLines = 1)
            Text("$address · ${signal}dBm", fontSize = 10.sp, color = IconGray, maxLines = 1)
        }
        Button(
            onClick = {
                if (mine && link != VestLink.Disconnected) VestConnection.disconnect() // 연결됨 → 해제
                else if (!VestConnection.connect(ctx, address))                         // 연결 시작
                    android.widget.Toast.makeText(ctx, "블루투스를 켜 주세요", android.widget.Toast.LENGTH_SHORT).show()
            },
            enabled = if (mine) link != VestLink.Connecting else link == VestLink.Disconnected, // 연결 중엔 누를 수 없음
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryCoral, contentColor = TextCharcoal),
            modifier = Modifier.height(30.dp)
        ) {
            Text(
                when {
                    mine && link == VestLink.Connecting -> "연결 중..."
                    mine && link == VestLink.Connected -> "연결 해제"
                    else -> "연결"
                },
                fontSize = 11.sp, maxLines = 1
            )
        }
    }
}

// [비상 배기] 탭: 길게 누르면 작동하는 SOS 버튼
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SosTab(onSos: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(190.dp).clip(CircleShape).background(BgPeach), // SOS 바깥 원: 연한 하늘색
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
