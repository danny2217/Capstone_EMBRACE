package com.example.embraceapp.data

// ───────── 서버 주소 등 바뀔 수 있는 설정 모음 ─────────
// 나중에 AWS 서버를 띄우면 여기 주소만 바꾸면 돼요. 화면 코드는 건드리지 않아도 됩니다.
object AppConfig {
    // MQTT 브로커 주소 (AWS IoT Core 등). 서버가 생기면 실제 주소로 교체
    const val MQTT_HOST = "TODO-서버주소"
    const val MQTT_PORT = 8883

    // 센서 값이 들어오는 주제(topic). 팀과 정한 이름으로 맞추기
    const val TOPIC_VITALS = "embrace/vest/vitals"
    const val TOPIC_ALERT = "embrace/vest/alert"
}
