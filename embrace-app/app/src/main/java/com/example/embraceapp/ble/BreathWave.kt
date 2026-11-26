package com.example.embraceapp.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan

// ───────── 조끼 호흡 파형 (실시간 그래프용) ─────────
// 조끼가 WAVE 특성(e3b70005)으로 보내는 호흡 센서 mV 를 받아서,
// PC 프로그램(breath-sensor/software/visualizer.py ② 그래프)과 같은 필터를 걸어 보관한다.
//   필터: 2차 Butterworth 대역통과 0.08~0.7 Hz (고역 biquad → 저역 biquad, Direct Form II transposed)
//         계수 계산과 시작 처리(_init_steady)까지 visualizer.py 를 그대로 옮김. 표본 주파수만 25 Hz.
//   패킷: [0] 버전(1) [1] 순번 [2] 샘플 수 n [3..] mV × n (uint16 리틀 엔디언). mV 0 = 센서 이상
object BreathWave {
    const val FS = 25f                         // 조끼가 보내는 샘플 수 (초당)
    private const val HP_HZ = 0.08f            // 기저선 흔들림 제거 (약 5회/분 이하)
    private const val LP_HZ = 0.70f            // 잡음·떨림 제거 (약 42회/분 이상)
    private const val KEEP = (FS * 30).toInt() // 최근 30 초 보관

    // 그래프가 읽는 값: 필터를 거친 값 목록(오래된 → 최신, 센서 이상 구간은 NaN) + 마지막으로 받은 시각
    class Snapshot(val ys: FloatArray, val lastAt: Long)

    private val _wave = MutableStateFlow(Snapshot(FloatArray(0), 0L))
    val wave: StateFlow<Snapshot> = _wave.asStateFlow()

    private val buf = FloatArray(KEEP) { Float.NaN }
    private var head = 0
    private var count = 0
    private var hp = Biquad.butter2(HP_HZ, FS, low = false)
    private var lp = Biquad.butter2(LP_HZ, FS, low = true)

    @Synchronized
    fun onPacket(b: ByteArray) {
        if (b.size < 3 || b[0].toInt() != 1) return
        val n = b[2].toInt() and 0xFF
        if (b.size < 3 + 2 * n) return
        for (i in 0 until n) {
            val mv = (b[3 + 2 * i].toInt() and 0xFF) or ((b[4 + 2 * i].toInt() and 0xFF) shl 8)
            val y = if (mv == 0) {
                resetFilter()          // 센서 이상 → 다시 연결되면 필터를 처음부터
                Float.NaN
            } else {
                lp.update(hp.update(mv.toFloat()))
            }
            buf[head] = y
            head = (head + 1) % KEEP
            if (count < KEEP) count++
        }
        publish()
    }

    @Synchronized
    fun clear() {
        buf.fill(Float.NaN); head = 0; count = 0
        resetFilter()
        _wave.value = Snapshot(FloatArray(0), 0L)
    }

    private fun resetFilter() {
        hp = Biquad.butter2(HP_HZ, FS, low = false)
        lp = Biquad.butter2(LP_HZ, FS, low = true)
    }

    private fun publish() {
        val out = FloatArray(count) { buf[(head - count + it + KEEP) % KEEP] }
        _wave.value = Snapshot(out, System.currentTimeMillis())
    }

    // 2차 IIR 한 단 (visualizer.py 의 Biquad 와 같음)
    class Biquad(private val b0: Float, private val b1: Float, private val b2: Float,
                 private val a1: Float, private val a2: Float) {
        private var s1 = 0f
        private var s2 = 0f
        private var ready = false

        fun update(x: Float): Float {
            if (!ready) {   // 입력 x 가 계속 들어왔던 것처럼 상태를 맞춤 → 시작 순간의 큰 튐 제거
                val y0 = (b0 + b1 + b2) / (1f + a1 + a2) * x
                s2 = b2 * x - a2 * y0
                s1 = b1 * x - a1 * y0 + s2
                ready = true
            }
            val y = b0 * x + s1
            s1 = b1 * x - a1 * y + s2
            s2 = b2 * x - a2 * y
            return y
        }

        companion object {
            // 2차 Butterworth 계수 (scipy.signal.butter(2, fc, kind) 와 같음)
            fun butter2(cutoffHz: Float, fs: Float, low: Boolean): Biquad {
                val k = tan(PI * cutoffHz / fs).toFloat()
                val norm = 1f / (1f + sqrt(2f) * k + k * k)
                val a1 = 2f * (k * k - 1f) * norm
                val a2 = (1f - sqrt(2f) * k + k * k) * norm
                return if (low) {
                    val b0 = k * k * norm
                    Biquad(b0, 2f * b0, b0, a1, a2)
                } else {
                    Biquad(norm, -2f * norm, norm, a1, a2)
                }
            }
        }
    }
}
