package com.embrace.watchhr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseCapabilities
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlin.math.roundToInt

/**
 * 화면이 꺼져도 계속 도는 포그라운드 서비스.
 *   ExerciseClient(운동 세션) 로 심박을 받고 → HrBleSender 로 조끼에 보낸다.
 *
 * 화면이 꺼지면 Health Services 는 데이터를 묶어서(배치) 보낸다. 기본값이면 몇 분씩 묶일 수 있어
 * BatchingMode.HEART_RATE_5_SECONDS 를 요청한다 → 화면 꺼짐에서도 5 초마다 받는다.
 * (그래서 ESP 의 HR_STALE_MS 는 5 초보다 길게 12 초로 둔다)
 */
class HrService : Service() {

    companion object {
        private const val CHANNEL_ID = "embrace_hr"
        private const val NOTI_ID = 1
        private const val ACTION_STOP = "com.embrace.watchhr.STOP"

        @Volatile var isRunning = false
            private set

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, HrService::class.java))

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, HrService::class.java).setAction(ACTION_STOP))
    }

    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private lateinit var sender: HrBleSender
    @Volatile private var hrAvailable = true   // 워치가 "측정 불가(착용 안 함 등)" 라고 알리면 false

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            // 배치로 여러 개가 와도 가장 최근 값 하나만 보낸다
            val hr = update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
            val bpm = hr.roundToInt()
            val valid = hrAvailable && bpm > 0
            sender.send(bpm, valid)
            StatusBus.hr(bpm, valid)
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            if (dataType == DataType.HEART_RATE_BPM && availability is DataTypeAvailability) {
                hrAvailable = availability == DataTypeAvailability.AVAILABLE
                if (!hrAvailable) StatusBus.hr(0, false)
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            StatusBus.link("심박 콜백 등록 실패: ${throwable.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        sender = HrBleSender(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_STICKY

        startForeground(
            NOTI_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        isRunning = true
        sender.start()
        startExercise()
        return START_STICKY
    }

    private fun startExercise() {
        exerciseClient.setUpdateCallback(callback)
        val capsFuture = exerciseClient.getCapabilitiesAsync()
        capsFuture.addListener({
            try {
                val caps = capsFuture.get()
                val type = pickExerciseType(caps)
                if (type == null) {
                    StatusBus.link("이 워치는 운동 중 심박을 지원하지 않음")
                    return@addListener
                }
                val builder = ExerciseConfig.builder(type)
                    .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                    .setIsAutoPauseAndResumeEnabled(false)
                    .setIsGpsEnabled(false)
                if (BatchingMode.HEART_RATE_5_SECONDS in caps.supportedBatchingModeOverrides) {
                    builder.setBatchingModeOverrides(setOf(BatchingMode.HEART_RATE_5_SECONDS))
                }
                val startFuture = exerciseClient.startExerciseAsync(builder.build())
                startFuture.addListener({
                    try {
                        startFuture.get()
                    } catch (e: Exception) {
                        // 흔한 원인: 삼성 헬스에서 운동 기록이 이미 켜져 있음 (운동은 앱 하나만 소유 가능)
                        StatusBus.link("운동 시작 실패: ${e.cause?.message ?: e.message}")
                    }
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                StatusBus.link("기능 확인 실패: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 심박을 주는 운동 종류 중 하나를 고른다. 걷기를 우선 (대부분의 워치가 지원). */
    private fun pickExerciseType(caps: ExerciseCapabilities): ExerciseType? {
        fun hasHr(t: ExerciseType) =
            DataType.HEART_RATE_BPM in caps.getExerciseTypeCapabilities(t).supportedDataTypes
        val preferred = listOf(ExerciseType.WALKING,    ExerciseType.WORKOUT)
        return preferred.firstOrNull { it in caps.supportedExerciseTypes && hasHr(it) }
            ?: caps.supportedExerciseTypes.firstOrNull { hasHr(it) }
    }

    override fun onDestroy() {
        isRunning = false
        // 운동 세션을 반드시 끝낸다. 안 끝내면 센서가 계속 켜져 배터리가 닳는다.
        exerciseClient.endExerciseAsync()
        exerciseClient.clearUpdateCallbackAsync(callback)
        sender.stop()
        StatusBus.hr(0, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "EMBRACE 심박 전송", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("EMBRACE")
            .setContentText("조끼로 심박 전송 중")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }
}
