package com.example.embraceapp.ui.map

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
import com.example.embraceapp.ui.profile.*

// ───────── 지도 ─────────

// 지도 화면: OpenStreetMap 실시간 지도 + 내 위치 (위치 권한 필요)
@Composable
fun MapScreen() {
    val ctx = LocalContext.current
    val locPerms = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    var granted by remember { mutableStateOf(locPerms.any { hasPermission(ctx, it) }) } // 위치 권한 허용 여부
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var myOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    // 위치 권한 요청 (설명 팝업 → 시스템 팝업)
    val requestLocation = rememberPermissionRequester(
        permissions = locPerms, needAll = false,
        title = "위치 권한",
        message = "자녀의 현재 위치를 지도에 보여주려면 위치 권한이 필요해요."
    ) { granted = it }

    // 지도 화면에 들어오면 권한이 없을 때 한 번 요청
    LaunchedEffect(Unit) { if (!granted) requestLocation() }

    // 권한이 허용되면 내 위치(파란 표시) 켜기
    LaunchedEffect(granted, mapView) {
        val map = mapView ?: return@LaunchedEffect
        if (granted && myOverlay == null) {
            val overlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), map)
            overlay.enableMyLocation()
            // 처음 위치를 잡으면 그 위치로 지도 이동
            overlay.runOnFirstFix { map.post { overlay.myLocation?.let { map.controller.animateTo(it) } } }
            map.overlays.add(overlay)
            myOverlay = overlay
        }
    }
    // 화면을 나갈 때 위치 추적 끄기
    DisposableEffect(Unit) {
        onDispose { myOverlay?.disableMyLocation(); mapView?.onDetach() }
    }

    Box(Modifier.fillMaxSize().background(SurfaceWhite)) {
        // 실시간 지도
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                initOsm(c)
                MapView(c).apply {
                    setTileSource(CartoTiles) // 위에서 정의한 지도 서버 설정 사용
                    setMultiTouchControls(true) // 두 손가락 확대/축소
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(17.0)
                    controller.setCenter(GeoPoint(37.5047, 126.9570)) // 위치를 잡기 전 기본 위치 (흑석동 근처)
                    mapView = this
                }
            }
        )

        // 지도 출처 표시 (OpenStreetMap 이용 조건)
        Text(
            "© OpenStreetMap contributors", fontSize = 9.sp, color = Color.DarkGray,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 8.dp, top = 4.dp)
        )

        // 위치 권한이 없을 때 안내
        if (!granted) {
            Surface(
                onClick = { requestLocation() }, shape = RoundedCornerShape(24.dp),
                color = SurfaceWhite, shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 28.dp)
            ) {
                Text(
                    "위치 권한이 필요해요. 눌러서 허용하기", fontSize = 13.sp, color = TextCharcoal,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }

        // 내 위치 버튼 (오른쪽)
        Surface(
            onClick = {
                if (!granted) requestLocation()
                else myOverlay?.myLocation?.let { mapView?.controller?.animateTo(it) }
            },
            shape = CircleShape, color = SurfaceWhite, shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(20.dp).border(2.dp, IconGray, CircleShape))
                Box(Modifier.size(7.dp).clip(CircleShape).background(IconGray))
            }
        }

        // 최근 위치 알림: 알림 목록에서 위치가 있는 가장 최근 알림을 보여줌 (센서에서 위치가 들어오면 자동으로 바뀜)
        val alerts by SensorRepository.alerts.collectAsState()
        val lastPlace = alerts.firstOrNull { it.location != null }
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
                        Icon(Icons.Default.LocationOn, null, tint = IconGray)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(lastPlace?.location ?: "아직 위치 알림이 없어요", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextCharcoal)
                        Text(lastPlace?.let { "${it.title} ${it.message} · ${it.time}" } ?: "알림이 오면 여기에 위치가 표시돼요", fontSize = 12.sp, color = TextCharcoal)
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(96.dp)) // 하단 탭 바 뒤까지 흰색 유지
            }
        }
    }
}

// 지도 그림을 받아오는 서버 설정 (OpenStreetMap 공식 서버. 앱 이름은 initOsm에서 설정)
val CartoTiles = object : OnlineTileSourceBase(
    "OsmTiles", 0, 19, 256, ".png",
    arrayOf("https://tile.openstreetmap.org/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
}

// 지도 라이브러리 기본 설정 (캐시는 앱 전용 폴더에 저장해서 저장소 권한이 필요 없음)
private fun initOsm(ctx: Context) {
    val c = Configuration.getInstance()
    c.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    c.userAgentValue = "EmbraceCapstoneApp/1.0 (university capstone project)" // OSM 서버가 'com.example...' 같은 기본 이름은 차단해서, 앱을 구분할 이름으로 설정
    c.osmdroidBasePath = java.io.File(ctx.cacheDir, "osmdroid2") // 폴더 이름을 바꿔서, 이전에 저장된 '차단 화면' 캐시를 무시함
    c.osmdroidTileCache = java.io.File(ctx.cacheDir, "osmdroid2/tiles")
}
