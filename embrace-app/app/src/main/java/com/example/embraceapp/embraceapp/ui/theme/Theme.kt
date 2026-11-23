package com.example.embraceapp.ui.theme

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
import com.example.embraceapp.ui.components.*
import com.example.embraceapp.ui.auth.*
import com.example.embraceapp.ui.home.*
import com.example.embraceapp.ui.notification.*
import com.example.embraceapp.ui.map.*
import com.example.embraceapp.ui.profile.*

// ───────── 색상 / 테마 ─────────

// true면 다크 테마. 값이 바뀌면 화면이 자동으로 다시 그려집니다
var isDarkTheme by mutableStateOf(false)

// 앱 전체에서 쓰는 색 (다크 모드면 어두운 색, 아니면 밝은 색)
// (이름은 예전 분홍 때 이름 그대로지만 색은 파란 계열로 바뀌었어요. 이름을 안 바꿔야 다른 코드를 안 고쳐도 돼요)
val PrimaryCoral: Color get() = if (isDarkTheme) Color(0xFF2E5C8F) else Color(0xFF4C8FD6) // 메인 파랑 (헤더, 선택된 탭, 시작 화면 배경)

val BgPeach: Color get() = if (isDarkTheme) Color(0xFF22364D) else Color(0xFFD9E9FA)      // 연한 파랑 (카드, 버튼)

val InactiveGray: Color get() = if (isDarkTheme) Color(0xFF2E2E31) else Color(0xFFECECEC) // 회색(입력칸)

val TextCharcoal: Color get() = if (isDarkTheme) Color(0xFFFFFFFF) else Color(0xFF1C1C1C) // 글자색

val SurfaceWhite: Color get() = if (isDarkTheme) Color(0xFF121A24) else Color(0xFFFFFFFF) // 배경

val AccentBlue = Color(0xFF1F5FA8) // 진한 파랑 (체크박스, 스위치 등 켜진 상태)

val LinkBlue = Color(0xFF1F5FA8) // 파란 글씨(시간, 배터리)

val SosRed = Color(0xFFD64541)   // SOS 버튼

val GreenOk = Color(0xFF2E9E6B)  // 초록(안정 상태 등)

val IconGray = Color(0xFF7D7D7D) // 아이콘 색 (아이콘은 전부 이 회색)

val ChartLine = Color.White // 호흡 그래프 색 (파란 배경 위라 흰색이 가장 잘 보임. 바꾸고 싶으면 이 줄만 수정)
