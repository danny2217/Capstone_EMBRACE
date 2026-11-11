package com.example.embraceapp.model

import com.example.embraceapp.R

// ───────── 센서 / 알림 데이터 모양 (model) ─────────
// 화면에서 쓰는 데이터의 "틀"만 모아둔 파일입니다. 값을 바꾸는 코드는 SensorRepository.kt에 있어요.

// 생체 정보 (홈 위쪽 카드)
data class VitalSigns(
    val heartRate: Int = 85,    // 심박수 (BPM)
    val breathRate: Int = 20,   // 호흡수 (회/분)
    val vestPressure: Int = 25  // 조끼 압력 (mmHg)
)

// 연결된 기기 한 개 (기기 관리 탭)
data class DeviceInfo(
    val name: String,    // 기기 이름
    val battery: Int,    // 배터리 잔량 (%)
    val iconRes: Int     // 아이콘 이미지
)

// 센서에서 온 값 전체 묶음
data class SensorState(
    val vitals: VitalSigns = VitalSigns(),
    val ieRatio: Float = 2.0f, // 호기:흡기 비율 (1 : x)
    // 호흡 그래프 점들 (0~1 사이 높이 값)
    val breathSeries: List<Float> = listOf(0.35f, 0.6f, 0.3f, 0.55f, 0.35f, 0.4f, 0.95f, 0.5f),
    val devices: List<DeviceInfo> = listOf(
        DeviceInfo("Galaxy Watch", 88, R.drawable.ic_watch),
        DeviceInfo("ESP 32-Vest", 70, R.drawable.ic_vest)
    )
)

// 알림 단계
enum class AlertLevel { Warning, Stable, Danger }

// 알림 한 개
data class AlertItem(
    val id: Long,
    val level: AlertLevel,
    val title: String,
    val message: String,
    val time: String,            // 예: "17:00 - 10월 25일"
    val day: String,             // 묶음 이름: "오늘" / "어제"
    val location: String? = null,
    val read: Boolean = false    // 읽었는지 (안 읽은 알림만 벨에 숫자로 표시)
)
