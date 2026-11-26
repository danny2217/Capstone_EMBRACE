package com.example.embraceapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.embraceapp.data.SensorRepository
import com.example.embraceapp.model.AlertLevel
import com.example.embraceapp.model.VestStatus
import com.example.embraceapp.util.Notifier
import java.util.UUID

// ───────── 조끼(ESP32) BLE 연결 ─────────
// 규격: docs/ble_protocol.md (팀 저장소). 조끼 = 서버(peripheral), 이 앱 = 클라이언트(central).
// 워치도 같은 조끼에 붙을 수 있어서, 폰은 "두 번째 손님"으로 접속합니다.
//
// 하는 일
//  1) connect()      : 조끼에 접속 → 서비스/특성 찾기 → 상태(STATUS) 알림 켜기
//  2) sendHeartRate(): 심박 4바이트 패킷을 HR_IN 에 쓰기 (워치 값을 폰이 전달할 때 사용)
//  3) exhaust()      : 비상 배기 명령(CMD 'x') 보내기
//  4) inflate() / deflate() : 개발자 도구용 수동 가압('i') / 배기('d')
//     → 조끼 상태 머신이 가압 시간·과압·센서 이상을 그대로 검사한다 (안전장치는 펌웨어 쪽)
//     → 펌웨어 hr_link.cpp onCommand 가 'i' 를 받도록 바뀌어야 가압이 동작한다 (2026-11-25 방법 A)
//
// ※ STATUS 20바이트의 항목 구성은 아직 문서에 없어서 "받은 원본(lastStatus)"만 보관합니다.
//    항목이 정해지면 onStatus() 안에서 해석해 SensorRepository 로 넘기면 돼요.

enum class VestLink { Disconnected, Connecting, Connected }

object VestConnection {
    // 규격의 UUID (ble_protocol.md §2). 뒤 4자리(0003, 0004)는 PROJECT_STATUS.md 기준
    private val SERVICE: UUID = UUID.fromString("e3b70001-5c3a-4f6e-9a1d-2b8f0c4d7e10")
    private val HR_IN: UUID = UUID.fromString("e3b70002-5c3a-4f6e-9a1d-2b8f0c4d7e10")   // 심박 쓰기
    private val STATUS: UUID = UUID.fromString("e3b70003-5c3a-4f6e-9a1d-2b8f0c4d7e10")  // 조끼 상태 알림(1초마다 20바이트)
    private val CMD: UUID = UUID.fromString("e3b70004-5c3a-4f6e-9a1d-2b8f0c4d7e10")     // 명령 쓰기(배기만)
    private val WAVE: UUID = UUID.fromString("e3b70005-5c3a-4f6e-9a1d-2b8f0c4d7e10")    // 호흡 파형 알림 (실시간 그래프, 새 펌웨어만)
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")     // 알림 켜기용 표준 UUID

    // 비상 배기 명령 값. 조끼는 'd'(배기) / 'x'(비상 배기)만 받음 → SOS 는 'x' 로 확정 (2026-11-23 팀 결정)
    private const val CMD_EXHAUST: Char = 'x'
    private const val CMD_INFLATE: Char = 'i' // 수동 가압 (개발자 도구)
    private const val CMD_DEFLATE: Char = 'd' // 배기 (개발자 도구)
    private const val CMD_UNLOCK: Char = 'r'  // 잠금(LOCKOUT) 해제 (개발자 도구)

    const val VEST_NAME = "EMBRACE-VEST" // 조끼 광고 이름

    private val _link = MutableStateFlow(VestLink.Disconnected)
    val link: StateFlow<VestLink> = _link.asStateFlow()

    private val _address = MutableStateFlow<String?>(null) // 연결 중/연결된 기기 주소
    val address: StateFlow<String?> = _address.asStateFlow()

    private val _message = MutableStateFlow<String?>(null) // 화면에 보여줄 안내(실패 이유 등)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _lastStatus = MutableStateFlow<ByteArray?>(null) // 마지막으로 받은 STATUS 원본
    val lastStatus: StateFlow<ByteArray?> = _lastStatus.asStateFlow()

    private val _statusCount = MutableStateFlow(0) // STATUS 받은 횟수 (잘 오는지 확인용)
    val statusCount: StateFlow<Int> = _statusCount.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var hrChar: BluetoothGattCharacteristic? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var waveChar: BluetoothGattCharacteristic? = null // STATUS 알림을 켠 다음에 이어서 켠다
    private val _hasWave = MutableStateFlow(false) // 연결된 조끼 펌웨어가 호흡 파형을 지원하는지
    val hasWave: StateFlow<Boolean> = _hasWave.asStateFlow()
    private var seq = 0
    private var appCtx: Context? = null   // 푸시 알림용
    private var userClosing = false       // 사용자가 직접 끊는 중인지 (이때는 "끊어졌어요" 안내를 안 띄움)
    private val handler = Handler(Looper.getMainLooper())

    // ── 연결 ──

    // 조끼에 접속 시작. 시작했으면 true (결과는 link / message 로 확인)
    @SuppressLint("MissingPermission")
    fun connect(ctx: Context, address: String): Boolean {
        if (_link.value != VestLink.Disconnected) return false
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return false
        val device = try { adapter.getRemoteDevice(address) } catch (e: IllegalArgumentException) { null } ?: return false

        BleScanner.stop(ctx) // 검색 중이면 멈추고 접속
        appCtx = ctx.applicationContext
        userClosing = false
        _message.value = null
        _address.value = address
        _link.value = VestLink.Connecting
        gatt = device.connectGatt(ctx.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        return true
    }

    // 연결 끊기
    @SuppressLint("MissingPermission")
    fun disconnect() {
        val g = gatt ?: return
        userClosing = true
        g.disconnect()
        handler.postDelayed({ cleanup(g) }, 500) // 끊김 알림이 안 와도 정리되도록
    }

    // ── 보내기 ──

    // 심박 패킷(4바이트) 쓰기: [버전=1, bpm, flags(bit0=유효), 순번]. 보냈으면 true
    fun sendHeartRate(bpm: Int, valid: Boolean): Boolean {
        val g = gatt ?: return false
        val ch = hrChar ?: return false
        if (_link.value != VestLink.Connected) return false
        val packet = byteArrayOf(
            1,
            bpm.coerceIn(0, 255).toByte(),
            if (valid) 1 else 0,
            (seq++ and 0xFF).toByte()
        )
        return write(g, ch, packet, preferNoResponse = true)
    }

    // 비상 배기 명령 보내기. 명령을 조끼에 보냈으면 true, 연결이 안 됐거나 못 보냈으면 false
    fun exhaust(): Boolean = sendCommand(CMD_EXHAUST)

    // 개발자 도구: 수동 가압 / 배기. 보냈으면 true (조끼가 거부하면 상태가 안 바뀜)
    fun inflate(): Boolean = sendCommand(CMD_INFLATE)
    fun deflate(): Boolean = sendCommand(CMD_DEFLATE)
    // 비상 배기 뒤 잠금 상태 풀기 → 조끼가 대기 상태로 돌아가 다시 가압할 수 있음
    fun unlock(): Boolean = sendCommand(CMD_UNLOCK)

    // 명령 1바이트 보내기. 워치 심박 쓰기와 겹쳐 "바쁨"으로 실패하면 0.15 초 간격으로 최대 5번 다시 보냄.
    // 조끼와 연결돼 있고 명령 특성이 있으면 true (실제로 조끼가 받아들였는지는 상태 변화로 확인)
    private fun sendCommand(c: Char): Boolean {
        val g = gatt ?: return false
        val ch = cmdChar ?: return false
        if (_link.value != VestLink.Connected) return false
        trySend(g, ch, c, tries = 6)
        return true
    }

    private fun trySend(g: BluetoothGatt, ch: BluetoothGattCharacteristic, c: Char, tries: Int) {
        if (gatt !== g || _link.value != VestLink.Connected) return
        val ok = write(g, ch, byteArrayOf(c.code.toByte()), preferNoResponse = false)
        if (!ok && tries > 1) handler.postDelayed({ trySend(g, ch, c, tries - 1) }, 150)
    }

    // ── 내부 처리 ──

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices() // 접속되면 서비스 찾기
                return
            }
            // 여기로 오면 끊겼거나 접속 실패
            when (_link.value) {
                VestLink.Connected -> if (!userClosing) {
                    _message.value = "조끼와 연결이 끊어졌어요."
                    SensorRepository.addAlert(AlertLevel.Warning, "조끼 연결 끊김", "조끼와 연결이 끊어졌어요. 조끼는 안전한 상태를 유지해요.")
                    appCtx?.let { Notifier.send(it, "조끼 연결 끊김", "조끼와 연결이 끊어졌어요.") }
                }
                VestLink.Connecting -> if (!userClosing) _message.value = "조끼에 연결하지 못했어요. 가까이에서 다시 시도해 주세요."
                else -> {}
            }
            cleanup(g)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(SERVICE) else null
            val hr = service?.getCharacteristic(HR_IN)
            if (service == null || hr == null) {
                _message.value = "EMBRACE 조끼가 아니에요. 다른 기기를 선택해 주세요."
                g.disconnect()
                handler.postDelayed({ cleanup(g) }, 500)
                return
            }
            hrChar = hr
            cmdChar = service.getCharacteristic(CMD) // 배기 명령을 지원하는 펌웨어에만 있음
            _statusCount.value = 0
            _lastStatus.value = null
            _link.value = VestLink.Connected

            // 상태(STATUS) 알림 켜기 → 끝나면 onDescriptorWrite 에서 호흡 파형(WAVE) 알림도 켠다
            // (안드로이드는 GATT 작업을 한 번에 하나만 처리하므로 차례로 켜야 함)
            waveChar = service.getCharacteristic(WAVE) // 예전 펌웨어에는 없음 → 그래프 자리에 안내 문구
            _hasWave.value = waveChar != null
            BreathWave.clear()
            val st = service.getCharacteristic(STATUS)
            if (st != null && st.getDescriptor(CCCD) != null) enableNotify(g, st, tries = 3)
            else enableWave(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.characteristic.uuid == STATUS) enableWave(g)
        }

        // 안드로이드 13 이상: 값이 바로 들어옴
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == STATUS) onStatus(value)
            else if (c.uuid == WAVE) BreathWave.onPacket(value)
        }

        // 안드로이드 12 이하
        @Deprecated("안드로이드 12 이하용")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == STATUS) c.value?.let { onStatus(it) }
            else if (c.uuid == WAVE) c.value?.let { BreathWave.onPacket(it) }
        }
    }

    // 호흡 파형 알림 켜기 (새 펌웨어에만 있음)
    private fun enableWave(g: BluetoothGatt) {
        val w = waveChar ?: return
        if (w.getDescriptor(CCCD) != null) enableNotify(g, w, tries = 3)
    }

    // 알림 켜기. 워치 심박 쓰기와 겹쳐서 "바쁨"으로 실패하면 0.3 초 뒤 다시 시도
    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic, tries: Int) {
        if (gatt !== g) return
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD) ?: return
        if (!writeDescriptor(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) && tries > 1) {
            handler.postDelayed({ enableNotify(g, ch, tries - 1) }, 300)
        }
    }

    // 조끼가 1초마다 보내는 상태(20바이트) 받기
    private fun onStatus(b: ByteArray) {
        if (b.size < 20 || b[0].toInt() != 1) return // 길이가 모자라거나 버전이 다르면 무시
        _lastStatus.value = b.copyOf()
        _statusCount.value = _statusCount.value + 1

        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8) // 리틀 엔디언 (낮은 바이트가 앞)
        val f = u8(3) // 플래그 8개
        fun bit(n: Int) = (f shr n) and 1 == 1

        val status = VestStatus(
            stateCode = u8(2),
            pump = bit(0), valveOpen = bit(1), armed = bit(2), overload = bit(3),
            breathValid = bit(4), breathHealthy = bit(5), apnea = bit(6), framesOk = bit(7),
            score = u8(4),
            hrState = u8(5),
            bpm = u8(6),
            breathRate = u16(7) / 10f,   // 조끼가 ×10 해서 보냄
            ieRatio = u16(9) / 100f,     // 조끼가 ×100 해서 보냄
            gripL = u16(11), gripR = u16(13), compMaxMv = u16(15),
            inStateSec = u16(17),
            cycles = u8(19),
            seq = u8(1)
        )
        SensorRepository.updateVest(status) { title, msg -> appCtx?.let { Notifier.send(it, title, msg) } }
    }

    @SuppressLint("MissingPermission")
    private fun cleanup(g: BluetoothGatt) {
        g.close()
        if (gatt === g) {
            userClosing = false
            SensorRepository.updateVest(null)
            gatt = null
            hrChar = null
            cmdChar = null
            waveChar = null
            _hasWave.value = false
            BreathWave.clear()
            _lastStatus.value = null
            _address.value = null
            _link.value = VestLink.Disconnected
        }
    }

    // 특성 쓰기 (안드로이드 버전별 방식 차이를 여기서 처리)
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, preferNoResponse: Boolean): Boolean {
        val canNoResp = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val canWrite = ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val type = when {
            preferNoResponse && canNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            canWrite -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            canNoResp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> return false
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            ch.value = value
            g.writeCharacteristic(ch)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }
}
