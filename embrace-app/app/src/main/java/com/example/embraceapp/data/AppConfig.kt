package com.example.embraceapp.data

import com.example.embraceapp.BuildConfig

// ───────── 서버 주소 등 바뀔 수 있는 설정 모음 ─────────
// 통신 방식: HTTP (팀 결정 2026-11-23). 예전 MQTT 토픽(embrace/vest/vitals 등)은 폐기.
// 값은 embrace-app/local.properties 에 적는다 (저장소에 올리지 않음):
//   embrace.serverUrl=http://<EC2 공인 IP>:8080
//   embrace.apiKey=<서버 .env 의 API_KEY>
// 둘 중 하나라도 비어 있으면 서버 동기화는 꺼진 채로 앱이 그대로 동작한다.
object AppConfig {
    val SERVER_URL: String = BuildConfig.SERVER_URL
    val API_KEY: String = BuildConfig.API_KEY
}
