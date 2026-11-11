package com.example.embraceapp.data

import com.example.embraceapp.model.*

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ───────── 로그인 / 회원가입 (Firebase) ─────────

// Firebase 로그인 담당. 결과는 onDone으로 돌려줌: null이면 성공, 글자가 있으면 실패 이유
object AuthRepository {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    // 로그인한 사람의 정보 (아직 못 불러왔으면 null)
    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    // DB(Firestore)에서 내 정보 불러오기: 로그인 직후, 앱 시작할 때 호출
    fun loadProfile() {
        val uid = auth.currentUser?.uid
        if (uid == null) { _profile.value = null; return }
        db.collection("users").document(uid).get().addOnSuccessListener { d ->
            if (d.exists()) {
                _profile.value = UserProfile(
                    role = d.getString("role") ?: "guardian",
                    name = d.getString("name") ?: "",
                    phone = d.getString("phone") ?: "",
                    birth = d.getString("birth") ?: "",
                    email = d.getString("email") ?: "",
                    partnerName = d.getString("partnerName") ?: "",
                    partnerPhone = d.getString("partnerPhone") ?: "",
                    partnerBirth = d.getString("partnerBirth") ?: ""
                )
            }
        }
    }

    // 이미 로그인된 상태인지 (앱을 다시 켜도 유지됨)
    val isLoggedIn: Boolean get() = auth.currentUser != null

    // 로그인
    fun login(email: String, password: String, onDone: (String?) -> Unit) {
        auth.signInWithEmailAndPassword(email.trim(), password)
            .addOnCompleteListener { t ->
                if (t.isSuccessful) loadProfile() // 로그인되면 내 정보 불러오기
                onDone(if (t.isSuccessful) null else friendly(t.exception))
            }
    }

    // 회원가입: 계정 만들기 → 이름, 전화번호 등을 DB(Firestore)의 users 문서에 저장
    fun signup(d: SignupDraft, onDone: (String?) -> Unit) {
        auth.createUserWithEmailAndPassword(d.email.trim(), d.password)
            .addOnCompleteListener { t ->
                if (!t.isSuccessful) { onDone(friendly(t.exception)); return@addOnCompleteListener }
                val uid = auth.currentUser?.uid
                if (uid == null) { onDone("가입 중 문제가 생겼어요. 다시 시도해 주세요."); return@addOnCompleteListener }
                val profile = hashMapOf(
                    "role" to (if (d.isGuardian) "guardian" else "child"),
                    "name" to d.name, "phone" to d.phone, "birth" to d.birth, "email" to d.email.trim(),
                    "partnerName" to d.partnerName, "partnerPhone" to d.partnerPhone, "partnerBirth" to d.partnerBirth
                )
                db.collection("users").document(uid).set(profile).addOnCompleteListener { p ->
                    auth.signOut() // 가입 후에는 직접 로그인하도록 로그아웃 상태로 둠
                    onDone(if (p.isSuccessful) null else "가입은 됐지만 정보 저장에 실패했어요. 다시 시도해 주세요.")
                }
            }
    }

    // 로그아웃
    fun logout() { auth.signOut(); _profile.value = null }

    // 오류를 한국어 문구로 바꾸기
    private fun friendly(e: Exception?): String = when (e) {
        is FirebaseAuthWeakPasswordException -> "비밀번호는 6자 이상으로 해주세요."
        is FirebaseAuthUserCollisionException -> "이미 가입된 이메일이에요."
        is FirebaseAuthInvalidUserException,
        is FirebaseAuthInvalidCredentialsException -> "이메일 또는 비밀번호가 올바르지 않아요."
        is FirebaseNetworkException -> "인터넷 연결을 확인해 주세요."
        else -> "문제가 생겼어요. (${e?.javaClass?.simpleName}: ${e?.message})" // 원인 확인용 (나중에 간단한 문구로 바꿀 예정)
    }
}
