package com.example.embraceapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ───────── 블루투스(BLE) 기기 검색 ─────────
// 주변에 있는 블루투스 기기(조끼 ESP32, 워치 등)를 찾아서 목록으로 보관합니다.
// 화면은 이 목록(found)만 보면 되고, 나중에 연결/데이터 받기 기능도 이 파일에 이어서 추가해요.

// 검색된 기기 한 개
// isVest: EMBRACE 조끼인지 (광고에 조끼 서비스 UUID 가 있거나 이름이 EMBRACE-VEST)
data class FoundDevice(val name: String, val address: String, val signal: Int, val isVest: Boolean = false) // signal: 신호 세기(dBm), 클수록 가까움

object BleScanner {
    private val VEST_SERVICE: java.util.UUID = java.util.UUID.fromString("e3b70001-5c3a-4f6e-9a1d-2b8f0c4d7e10")

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())
    val found: StateFlow<List<FoundDevice>> = _found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var callback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    // 검색 시작 (권한이 허용된 뒤에 호출). 10초 뒤 자동 종료
    @SuppressLint("MissingPermission")
    fun start(ctx: Context): Boolean { // 검색을 시작했으면 true, 블루투스가 꺼져 있으면 false
        if (_scanning.value) return true
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) return false // 블루투스가 꺼져 있거나 기기에 없음

        _found.value = emptyList()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // 조끼는 서비스 UUID 를 광고에, 이름은 스캔 응답에 싣는다 → 이름이 늦게 와도 UUID 로 알아본다
                val uuids = result.scanRecord?.serviceUuids.orEmpty()
                val rawName = result.device.name ?: result.scanRecord?.deviceName
                val isVest = uuids.any { it.uuid == VEST_SERVICE } || rawName == VestConnection.VEST_NAME
                val name = rawName ?: (if (isVest) VestConnection.VEST_NAME else return) // 이름 없는 기기는 목록에서 제외
                val d = FoundDevice(name, result.device.address, result.rssi, isVest)
                // 같은 기기는 한 번만 보여주고 신호 세기만 갱신
                _found.value = _found.value.filter { it.address != d.address } + d
            }
        }
        callback = cb
        _scanning.value = true
        scanner.startScan(cb)
        handler.postDelayed({ stop(ctx) }, 10_000)
        return true
    }

    // 검색 멈추기
    @SuppressLint("MissingPermission")
    fun stop(ctx: Context) {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        callback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        callback = null
        _scanning.value = false
    }
}