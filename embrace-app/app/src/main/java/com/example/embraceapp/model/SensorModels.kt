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

// 조끼가 1초마다 보내는 상태 (STATUS 20바이트를 풀어 놓은 것. 규격: firmware hr_link.cpp)
data class VestStatus(
    val stateCode: Int,        // 0 시작배기 1 대기 2 가압 3 유지 4 배기 5 휴식 6 비상배기 7 잠금
    val pump: Boolean,         // 펌프 켜짐
    val valveOpen: Boolean,    // 밸브 열림
    val armed: Boolean,        // 실제 가압 허용 상태 (false 면 연습 모드)
    val overload: Boolean,     // 과부하 징후
    val breathValid: Boolean,  // 호흡 값이 믿을 만함
    val breathHealthy: Boolean,
    val apnea: Boolean,        // 무호흡 감지
    val framesOk: Boolean,     // 센서 데이터가 잘 들어오는 중
    val score: Int,            // 판단 점수
    val hrState: Int,          // 0 없음 1 끊김 2 재합류 3 정상
    val bpm: Int,              // 심박 (쓸 수 없으면 0)
    val breathRate: Float,     // 호흡수 (회/분)
    val ieRatio: Float,        // 들숨 ÷ 날숨 (조끼 정의). 화면의 "1 : x" 는 x = 1 / ieRatio
    val gripL: Int,
    val gripR: Int,
    val compMaxMv: Int,
    val inStateSec: Int,       // 현재 상태가 이어진 시간 (초)
    val cycles: Int,           // 가압 횟수
    val seq: Int = 0           // STATUS 순번 (바이트 1)
) {
    val stateName: String
        get() = when (stateCode) {
            0 -> "시작 배기"; 1 -> "대기"; 2 -> "가압 중"; 3 -> "유지 중"
            4 -> "배기 중"; 5 -> "휴식"; 6 -> "비상 배기"; 7 -> "잠금"
            else -> "알 수 없음"
        }
}

// 워치가 보낸 심박 (받은 시각을 같이 저장해서, 오래 안 오면 "끊김"으로 처리)
// battery: 워치 배터리 % (워치 앱 v2.2 부터 보냄, 모르면 null)
data class WatchHr(val bpm: Int, val valid: Boolean, val receivedAt: Long, val battery: Int? = null)

// 워치가 보낸 자녀 위치
data class ChildLocation(val lat: Double, val lon: Double, val accuracyM: Double?, val receivedAt: Long)

// 호흡 그래프 점 하나: 받은 시각(ms) + 호흡수(회/분)
data class BreathPoint(val t: Long, val rate: Float)

// 센서에서 온 값 전체 묶음
data class SensorState(
    val vitals: VitalSigns = VitalSigns(),
    val vest: VestStatus? = null, // 조끼에서 받은 실제 상태 (연결 전/끊기면 null)
    val watchHr: WatchHr? = null, // 워치에서 받은 마지막 심박
    val childLocation: ChildLocation? = null, // 자녀 마지막 위치 (자녀 폰: 내 GPS / 보호자 폰: 서버에서 받은 값)
    val ieRatio: Float = 2.0f, // 호기:흡기 비율 (1 : x)
    // 호흡 그래프: 조끼가 보낸 호흡수(회/분)를 받은 시각과 함께 쌓아 둔 목록 (최근 BREATH_HISTORY_MS 만 보관)
    // 예전의 예시 값(breathSeries) 은 실제 숨과 상관없이 움직여서 삭제
    val breathHistory: List<BreathPoint> = emptyList()
    // (예전의 하드코딩된 기기 목록 devices 는 삭제. 기기 관리 탭이 워치·조끼의 실제 상태를 직접 보여 준다)
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
