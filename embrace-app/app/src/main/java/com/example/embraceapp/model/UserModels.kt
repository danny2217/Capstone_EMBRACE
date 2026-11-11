package com.example.embraceapp.model

// ───────── 사용자 정보 모양 (model) ─────────

// 회원가입 도중 입력한 값을 두 화면(1단계, 2단계)에서 같이 쓰기 위한 보관함
class SignupDraft {
    var isGuardian = true   // true: 보호자로 가입, false: 자녀로 가입
    var name = ""
    var phone = ""
    var birth = ""
    var email = ""
    var password = ""
    // 2단계에서 입력하는 상대방(보호자면 자녀, 자녀면 보호자) 정보
    var partnerName = ""
    var partnerPhone = ""
    var partnerBirth = ""
}
val signupDraft = SignupDraft()

// 로그인한 사람의 정보 (가입할 때 저장한 값을 불러와서 화면에 표시)
data class UserProfile(
    val role: String = "guardian", // "guardian"(보호자) 또는 "child"(자녀)
    val name: String = "",
    val phone: String = "",
    val birth: String = "",
    val email: String = "",
    val partnerName: String = "",  // 보호자면 자녀 이름, 자녀면 보호자 이름
    val partnerPhone: String = "",
    val partnerBirth: String = ""
)

// "등록 아동: 이름" 또는 "보호자: 이름" 문구 만들기
fun relationLabel(p: UserProfile?): String =
    if (p?.role == "child") "보호자: ${p.partnerName}" else "등록 아동: ${p?.partnerName ?: ""}"
