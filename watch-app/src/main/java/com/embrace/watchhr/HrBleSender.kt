package com.embrace.watchhr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

/**
 * 조끼(ESP32, BLE 서버)를 찾아 접속하고, 심박을 4바이트로 써 넣는다.
 * 프로토콜: docs/ble_hr_protocol.md  (ESP 쪽 firmware/embrace_node/src/hr_link.cpp 와 1:1)
 *
 *   [0] version = 1   [1] bpm (0~255)   [2] flags bit0 = 측정 유효   [3] seq (0~255 순환)
 *
 * 모든 상태 변경은 메인 스레드(main 핸들러)에서만 한다 → 락이 필요 없다.
 * BLE 콜백은 다른 스레드에서 오므로 main.post 로 넘긴다.
 * 끊기면 RETRY_MS 뒤 다시 찾는다. 조끼가 꺼져 있어도 앱은 멈추지 않는다.
 */
@SuppressLint("MissingPermission")   // 권한은 MainActivity 가 서비스 시작 전에 모두 받는다
class HrBleSender(private val ctx: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("e3b70001-5c3a-4f6e-9a1d-2b8f0c4d7e10")
        val HR_IN_UUID: UUID = UUID.fromString("e3b70002-5c3a-4f6e-9a1d-2b8f0c4d7e10")
        private const val PROTO_VERSION: Byte = 1
        private const val RETRY_MS = 3_000L        // 끊긴 뒤 다시 찾기까지
        private const val SCAN_WINDOW_MS = 15_000L // 한 번에 찾는 시간 (안드로이드는 30초에 5번 넘게 스캔하면 막는다)
        private const val WRITE_TIMEOUT_MS = 3_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    private var running = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var hrChar: BluetoothGattCharacteristic? = null
    private var writeSince = 0L    // 0 = 진행 중인 쓰기 없음
    private var seq = 0

    fun start() = main.post {
        running = true
        startScan()
    }

    fun stop() = main.post {
        running = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        hrChar = null
        StatusBus.link("중지")
    }

    /** 심박 하나를 보낸다. 조끼에 안 붙어 있으면 조용히 버린다 (다음 값이 곧 온다). */
    fun send(bpm: Int, valid: Boolean) = main.post {
        val g = gatt ?: return@post
        val c = hrChar ?: return@post
        val now = SystemClock.elapsedRealtime()
        // BLE 는 한 번에 쓰기 하나만 가능. 앞의 쓰기가 아직이면 이번 값은 건너뛴다.
        if (writeSince != 0L && now - writeSince < WRITE_TIMEOUT_MS) return@post

        val payload = byteArrayOf(
            PROTO_VERSION,
            bpm.coerceIn(0, 255).toByte(),
            (if (valid) 1 else 0).toByte(),
            (seq and 0xFF).toByte(),
        )
        seq++
        val rc = g.writeCharacteristic(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (rc == BluetoothStatusCodes.SUCCESS) writeSince = now
        else StatusBus.link("쓰기 실패 ($rc)")
    }

    // ------------------------------------------------------------------ 스캔
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (running && gatt == null) {
                    stopScan()
                    connect(result.device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                StatusBus.link("스캔 실패 ($errorCode)")
                retryLater()
            }
        }
    }

    private fun startScan() {
        if (!running || scanning || gatt != null) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            StatusBus.link("블루투스 꺼짐")
            retryLater()
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCb)
        scanning = true
        StatusBus.link("찾는 중…")
        main.postDelayed({
            if (scanning) {       // 못 찾음 → 잠깐 쉬고 다시
                stopScan()
                StatusBus.link("못 찾음, 재시도")
                retryLater()
            }
        }, SCAN_WINDOW_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
    }

    private fun retryLater() {
        if (running) main.postDelayed({ startScan() }, RETRY_MS)
    }

    // ------------------------------------------------------------------ 접속
    private fun connect(device: BluetoothDevice) {
        StatusBus.link("접속 중…")
        gatt = device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    StatusBus.link("서비스 확인 중…")
                    g.discoverServices()
                } else {
                    g.close()
                    if (gatt === g) {
                        gatt = null
                        hrChar = null
                        writeSince = 0L
                    }
                    StatusBus.link("끊김 ($status)")
                    retryLater()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val c = g.getService(SERVICE_UUID)?.getCharacteristic(HR_IN_UUID)
                if (c == null) {
                    StatusBus.link("조끼 서비스 없음")
                    g.disconnect()          // → onConnectionStateChange 에서 정리·재시도
                } else {
                    hrChar = c
                    StatusBus.link("연결됨")
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { writeSince = 0L }
        }
    }
}
