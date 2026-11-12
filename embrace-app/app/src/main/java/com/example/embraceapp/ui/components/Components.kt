package com.example.embraceapp.ui.components

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
import com.example.embraceapp.ui.auth.*
import com.example.embraceapp.ui.home.*
import com.example.embraceapp.ui.notification.*
import com.example.embraceapp.ui.map.*
import com.example.embraceapp.ui.profile.*

// ───────── 공통 부품 ─────────

// 하단 탭 바 (지도 / 홈 / 프로필)
@Composable
fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val icons = listOf(Icons.Default.LocationOn, Icons.Default.Home, Icons.Default.Person)
    // 화면 아래에 떠 있는 둥근 알약 모양 바 (양옆과 아래에 여백을 둬서 구석 문제가 없음)
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp)) {
    Surface(
        color = BgPeach,
        shape = RoundedCornerShape(40.dp),
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(vertical = 10.dp).fillMaxWidth(),
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
                ) { Icon(icon, null, tint = IconGray, modifier = Modifier.size(30.dp)) }
            }
        }
    }
    }
}

// 오른쪽 위 알림 벨 버튼 (안 읽은 알림이 있으면 왼쪽 아래에 빨간 숫자 표시)
@Composable
fun BellButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val alerts by SensorRepository.alerts.collectAsState()
    val unread = alerts.count { !it.read }
    Box(modifier.size(38.dp)) {
        Surface(
            onClick = onClick, shape = CircleShape, color = BgPeach,
            shadowElevation = 4.dp, modifier = Modifier.fillMaxSize()
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Notifications, "알림", tint = IconGray, modifier = Modifier.size(20.dp))
            }
        }
        if (unread > 0) {
            Box(
                Modifier.align(Alignment.BottomStart).offset(x = (-5).dp, y = 5.dp)
                    .size(18.dp).clip(CircleShape).background(SosRed),
                contentAlignment = Alignment.Center
            ) {
                Text(if (unread > 9) "9+" else "$unread", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
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
    Column(Modifier.fillMaxSize().background(PrimaryCoral).imePadding()) {
        // 헤더 (뒤로가기, 제목, 벨)
        Box(
            Modifier.fillMaxWidth().statusBarsPadding().height(headerHeight).padding(horizontal = 16.dp)
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = IconGray)
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
fun SoftField(
    label: String, placeholder: String, password: Boolean = false,
    value: String? = null, onChange: ((String) -> Unit)? = null // 입력값을 밖에서 관리하고 싶을 때만 사용
) {
    var inner by remember { mutableStateOf("") }   // 입력한 글자 (밖에서 안 넘겨줄 때 사용)
    val text = value ?: inner
    var show by remember { mutableStateOf(false) } // 비밀번호 보이기 여부
    val fieldShape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(label, fontSize = 12.sp, color = TextCharcoal, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
        TextField(
            value = text, onValueChange = { v -> if (onChange != null) onChange(v) else inner = v }, singleLine = true,
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
    color: Color = BgPeach, enabled: Boolean = true, onClick: () -> Unit
) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(46.dp), shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = color, contentColor = TextCharcoal,
            disabledContainerColor = color, disabledContentColor = TextCharcoal // 입력 전에도 똑같은 모양 (눌러도 안 넘어가기만 함)
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, disabledElevation = 4.dp)
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
            Icon(icon, null, tint = IconGray, modifier = Modifier.size(20.dp))
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
            colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue, checkedThumbColor = Color.White)
        )
    }
}

// 메뉴 한 줄 (내가 넣은 이미지 아이콘 사용, 프로필 화면용)
@Composable
fun MenuRowVec(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(BgPeach),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = IconGray, modifier = Modifier.size(26.dp)) // 아이콘은 전부 회색 (크기는 숫자로 조절)
        }
        Spacer(Modifier.width(16.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextCharcoal)
    }
}

// (예전 방식: 이미지 파일 아이콘. 지금은 안 쓰지만 나중에 직접 만든 아이콘을 넣을 때 사용)
@Composable
fun MenuRowRes(iconRes: Int, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(BgPeach),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(iconRes), null, tint = Color.Unspecified, modifier = Modifier.size(34.dp))
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
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryCoral, contentColor = TextCharcoal),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
            modifier = Modifier.height(32.dp)
        ) { Text("연결 해제", fontSize = 11.sp, maxLines = 1) }
    }
}
