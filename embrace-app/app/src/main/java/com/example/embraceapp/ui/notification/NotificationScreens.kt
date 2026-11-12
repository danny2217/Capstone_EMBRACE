package com.example.embraceapp.ui.notification

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
import com.example.embraceapp.ui.map.*
import com.example.embraceapp.ui.profile.*

// ───────── 알림 ─────────

// 알림 한 개 (아이콘 + 제목 + 내용 + 시간)
@Composable
fun NotiItem(icon: ImageVector, title: String, msg: String, time: String, extra: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(BgPeach), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = IconGray)
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

// 알림 화면 (SensorRepository의 알림 목록을 날짜별로 보여줌)
@Composable
fun NotificationScreen(nav: Nav) {
    val alerts by SensorRepository.alerts.collectAsState()
    // 화면을 열면 모두 읽음 처리 (벨의 빨간 숫자 사라짐)
    LaunchedEffect(Unit) { SensorRepository.markAllRead() }
    PinkPage("알림", onBack = { nav.back() }, onBell = { }) {
        if (alerts.isEmpty()) {
            Spacer(Modifier.height(80.dp))
            Text("새로운 알림이 없어요", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextCharcoal)
            Spacer(Modifier.height(6.dp))
            Text("과부하 징후나 비상 상황이 감지되면 여기에 표시돼요.", fontSize = 12.sp, color = IconGray, textAlign = TextAlign.Center)
        }
        alerts.groupBy { it.day }.forEach { (day, items) ->
            Text(day, Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 14.sp)
            items.forEach { a ->
                NotiItem(
                    if (a.level == AlertLevel.Danger) Icons.Default.Warning else Icons.Default.Notifications,
                    a.title, a.message, a.time, a.location?.let { "위치: $it" }
                )
            }
        }
    }
}

// 알림 설정 화면 (푸쉬 알림을 켤 때 알림 권한 요청)
@Composable
fun NotiSettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    // 안드로이드 13 이상에서만 알림 권한이 필요함
    val notiPerms = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    var push by remember { mutableStateOf(notiPerms.all { hasPermission(ctx, it) }) } // 푸쉬 알림 켜짐 여부
    val requestNoti = rememberPermissionRequester(
        permissions = notiPerms,
        title = "알림 권한",
        message = "과부하 징후나 비상 상황을 소리와 진동으로 알려드리려면 알림 권한이 필요해요."
    ) { push = it }

    PinkPage("알림 설정", onBack = { nav.back() }, onBell = { nav.go(Screen.Notifications) }) {
        Spacer(Modifier.height(8.dp))
        // 푸쉬 알림 스위치: 켜려고 하면 권한 확인
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("푸쉬 알림 설정", Modifier.weight(1f), fontSize = 15.sp, color = TextCharcoal)
            Switch(
                checked = push, onCheckedChange = { want -> if (want) requestNoti() else push = false },
                colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue, checkedThumbColor = Color.White)
            )
        }
        SwitchRow("무음 설정")
        SwitchRow("진동(햅틱) 설정")
        Spacer(Modifier.height(32.dp))
        // 소리/진동이 나는지 확인하는 테스트 버튼
        PillButton("테스트 알림 보내기") {
            SensorRepository.addAlert(AlertLevel.Warning, "경고!", "과부화 징후 발견") // 벨에 숫자 표시
            if (push) Notifier.send(ctx, "경고!", "과부화 징후 발견") else requestNoti()
        }
    }
}