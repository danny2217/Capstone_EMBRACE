package com.example.embraceapp.data

import com.example.embraceapp.model.*

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
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

    // 비밀번호 찾기: 가입한 이메일로 재설정 메일 보내기 (메일 안의 링크에서 새 비밀번호를 정함)
    fun sendResetEmail(email: String, onDone: (String?) -> Unit) {
        auth.sendPasswordResetEmail(email.trim())
            .addOnCompleteListener { t -> onDone(if (t.isSuccessful) null else friendly(t.exception)) }
    }

    // 비밀번호 변경: 현재 비밀번호로 한 번 더 확인한 뒤 새 비밀번호로 바꿈
    fun changePassword(current: String, newPassword: String, onDone: (String?) -> Unit) {
        val user = auth.currentUser
        val email = user?.email
        if (user == null || email == null) { onDone("로그인이 필요해요."); return }
        user.reauthenticate(EmailAuthProvider.getCredential(email, current)).addOnCompleteListener { r ->
            if (!r.isSuccessful) { onDone(reauthError(r.exception)); return@addOnCompleteListener }
            user.updatePassword(newPassword).addOnCompleteListener { u ->
                onDone(if (u.isSuccessful) null else friendly(u.exception))
            }
        }
    }

    // 프로필 수정: 이름, 전화번호를 DB(Firestore)에 저장 (이메일은 로그인 계정이라 여기서 바꾸지 않음)
    fun updateProfile(name: String, phone: String, onDone: (String?) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid == null) { onDone("로그인이 필요해요."); return }
        db.collection("users").document(uid)
            .update(mapOf("name" to name.trim(), "phone" to phone.trim()))
            .addOnCompleteListener { t ->
                if (t.isSuccessful) {
                    _profile.value = _profile.value?.copy(name = name.trim(), phone = phone.trim()) // 화면에 바로 반영
                    onDone(null)
                } else onDone("저장에 실패했어요. 다시 시도해 주세요.")
            }
    }

    // 계정 삭제: 비밀번호로 확인한 뒤 DB 정보와 계정을 지움
    fun deleteAccount(password: String, onDone: (String?) -> Unit) {
        val user = auth.currentUser
        val email = user?.email
        if (user == null || email == null) { onDone("로그인이 필요해요."); return }
        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).addOnCompleteListener { r ->
            if (!r.isSuccessful) { onDone(reauthError(r.exception)); return@addOnCompleteListener }
            db.collection("users").document(user.uid).delete().addOnCompleteListener { d ->
                if (!d.isSuccessful) { onDone("정보 삭제에 실패했어요. 다시 시도해 주세요."); return@addOnCompleteListener }
                user.delete().addOnCompleteListener { u ->
                    if (u.isSuccessful) { _profile.value = null; onDone(null) }
                    else onDone(friendly(u.exception))
                }
            }
        }
    }

    // 비밀번호 재확인이 실패했을 때 문구 (인터넷 문제만 따로 안내)
    private fun reauthError(e: Exception?): String =
        if (e is FirebaseNetworkException || e is FirebaseTooManyRequestsException) friendly(e) else "비밀번호가 올바르지 않아요."

    // 오류를 한국어 문구로 바꾸기
    private fun friendly(e: Exception?): String = when (e) {
        is FirebaseAuthWeakPasswordException -> "비밀번호는 6자 이상으로 해주세요."
        is FirebaseAuthUserCollisionException -> "이미 가입된 이메일이에요."
        is FirebaseAuthInvalidUserException,
        is FirebaseAuthInvalidCredentialsException -> "이메일 또는 비밀번호가 올바르지 않아요."
        is FirebaseNetworkException -> "인터넷 연결을 확인해 주세요."
        is FirebaseTooManyRequestsException -> "시도가 너무 많아요. 잠시 후 다시 해 주세요."
        else -> "문제가 생겼어요. (${e?.javaClass?.simpleName}: ${e?.message})" // 원인 확인용 (나중에 간단한 문구로 바꿀 예정)
    }
}
