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
                        else -> SosTab { sosDialog = true }
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
            title = { Text("비상 배기 실행") },
            text = { Text("조끼의 압력을 해제하고 있습니다.") }
        )
    }
}

// 위쪽 생체 정보 카드 (심박수, 호흡수, 조끼 압력)
@Composable
fun VitalsCard() {
    val sensor by SensorRepository.state.collectAsState()
    val v = sensor.vitals // 센서 값
    Surface(
        shape = RoundedCornerShape(28.dp), color = BgPeach, shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            // 왼쪽: 심박수 원형 그래프
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { (v.heartRate / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF6E6E6E), trackColor = Color.White, strokeWidth = 4.dp
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${v.heartRate}", fontSize = 22.sp, color = Color(0xFF7D7D7D), fontWeight = FontWeight.Bold)
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
                VitalLine(R.drawable.ic_breath, "호흡수", "${v.breathRate}", "회/분")
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Color.White, thickness = 1.5.dp)
                Spacer(Modifier.height(16.dp))
                VitalLine(R.drawable.ic_pressure, "조끼 압력", "${v.vestPressure}", "mmHg")
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
    val pts = SensorRepository.state.collectAsState().value.breathSeries // 그래프 높이 값 (0~1)
    // 그래프가 오른쪽에서 왼쪽으로 계속 흐르게 하는 값 (0 ~ 점 개수까지 계속 증가하며 반복)
    val n = pts.size.coerceAtLeast(2)
    val flow by rememberInfiniteTransition(label = "flow").animateFloat(
        initialValue = 0f, targetValue = n.toFloat(),
        animationSpec = infiniteRepeatable(tween(n * 700, easing = LinearEasing), RepeatMode.Restart),
        label = "flowValue"
    )
    Canvas(Modifier.fillMaxWidth().height(115.dp).clip(RoundedCornerShape(0.dp))) {
        if (pts.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val step = w / (pts.size - 1)
        val k = flow.toInt()        // 몇 칸 흘렀는지
        val frac = flow - k         // 한 칸 안에서 얼마나 흘렀는지 (0~1)
        // 점 n+1개를 왼쪽으로 조금씩 밀면서 그림. 값은 돌려가며 사용 (나중에 실제 센서 값이 들어오면 이 부분을 최신 값 목록으로 교체)
        val xs = (0..pts.size).map { it * step - frac * step }
        val ys = (0..pts.size).map { h * (1 - pts[(it + k) % pts.size]) }
        // 점들을 부드러운 곡선으로 연결
        val line = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 1..pts.size) {
                val x0 = xs[i - 1]; val x1 = xs[i]
                cubicTo((x0 + x1) / 2, ys[i - 1], (x0 + x1) / 2, ys[i], x1, ys[i])
            }
        }
        // 곡선 아래 색 채우기
        val fill = Path().apply { addPath(line); lineTo(xs[pts.size], h); lineTo(xs[0], h); close() }
        drawPath(fill, Brush.verticalGradient(listOf(ChartLine.copy(alpha = 0.40f), Color.Transparent)))
        drawPath(line, ChartLine, style = Stroke(width = 7f, cap = StrokeCap.Round)) // 선을 굵고 둥글게
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
                    Text("${sensor.vitals.breathRate}", fontSize = 48.sp, color = TextCharcoal)
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
                Text(String.format("1 : %.1f", sensor.ieRatio), fontSize = 48.sp, color = TextCharcoal, maxLines = 1)
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
    val sensor by SensorRepository.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 블루투스 권한 (안드로이드 12 이상: 주변 기기 검색/연결, 11 이하: 위치)
    val btPerms = if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val requestBt = rememberPermissionRequester(
        permissions = btPerms,
        title = "블루투스 권한",
        message = "조끼와 워치를 찾고 연결하려면 주변 기기 권한이 필요해요."
    ) { ok ->
        // 권한이 허용되면 기기 검색 시작. 블루투스가 꺼져 있으면 안내
        if (ok && !BleScanner.start(ctx))
            android.widget.Toast.makeText(ctx, "블루투스를 켜 주세요", android.widget.Toast.LENGTH_SHORT).show()
    }
    val scanning by BleScanner.scanning.collectAsState() // 검색 중인지
    val found by BleScanner.found.collectAsState()       // 검색된 기기 목록
    var scanned by remember { mutableStateOf(false) }    // 한 번이라도 검색했는지
    // 기기 검색 바
    Surface(
        shape = RoundedCornerShape(30.dp), color = BgPeach, shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_bluetooth), null, tint = IconGray, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text("검색된 기기 목록", Modifier.weight(1f), fontSize = 14.sp, color = TextCharcoal)
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
    // 검색된 기기 목록 (신호가 센 순서)
    if (scanned || found.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        if (found.isEmpty()) {
            Text(
                if (scanning) "주변 기기를 찾는 중이에요..." else "검색된 기기가 없어요. 블루투스가 켜져 있는지 확인해 주세요.",
                fontSize = 12.sp, color = TextCharcoal, modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        found.sortedByDescending { it.signal }.forEach { d ->
            FoundDeviceRow(d.name, d.address, d.signal)
            Spacer(Modifier.height(10.dp))
        }
    }
    Spacer(Modifier.height(22.dp))
    // 연결된 기기 (지금은 예시)
    sensor.devices.forEachIndexed { i, d ->
        if (i > 0) Spacer(Modifier.height(14.dp))
        DeviceRow(d.iconRes, d.name, "${d.battery}%")
    }
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
}

// 검색된 기기 한 줄 (이름 + 주소 + 신호 세기 + 연결 버튼)
@Composable
fun FoundDeviceRow(name: String, address: String, signal: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_bluetooth), null, tint = IconGray, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextCharcoal, maxLines = 1)
            Text("$address · ${signal}dBm", fontSize = 10.sp, color = IconGray, maxLines = 1)
        }
        Button(
            // 실제 연결(GATT)은 센서 담당과 정한 뒤 BleScanner에 이어서 추가
            onClick = { android.widget.Toast.makeText(ctx, "연결 기능은 준비 중이에요", android.widget.Toast.LENGTH_SHORT).show() },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryCoral, contentColor = TextCharcoal),
            modifier = Modifier.height(30.dp)
        ) { Text("연결", fontSize = 11.sp, maxLines = 1) }
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