package com.kotonara.farmcamera.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.kotonara.farmcamera.BuildConfig
import com.kotonara.farmcamera.MainActivity
import com.kotonara.farmcamera.data.AndroidDriveUploadLogger
import com.kotonara.farmcamera.data.AppDataUploader
import com.kotonara.farmcamera.data.AppPhotoStore
import com.kotonara.farmcamera.data.CameraXPhotoSource
import com.kotonara.farmcamera.data.CameraXTorchController
import com.kotonara.farmcamera.data.CapturePhotoUploadStates
import com.kotonara.farmcamera.data.CoroutineCaptureScheduler
import com.kotonara.farmcamera.data.NoOpDriveUploadLogger
import com.kotonara.farmcamera.data.SilentDriveAuthorizer
import com.kotonara.farmcamera.data.TorchSettingsRepository
import com.kotonara.farmcamera.domain.CaptureCoordinator
import com.kotonara.farmcamera.domain.CaptureIntervalOption
import com.kotonara.farmcamera.domain.CaptureState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 定期撮影を常駐させる Foreground Service（docs/03-native.md 5〜6 節、issue #52）。
 *
 * サインインと初回の `drive.appdata` 同意は [MainActivity] 側で済ませておく前提。
 * ここでは [SilentDriveAuthorizer] でトークンを取り直すだけで、UI は持たない。
 * `CaptureScheduler` と `CaptureCoordinator` を回すだけの薄い層に保つ
 * （docs/03-native.md 4 節「presentation は domain のみに依存」）。
 */
class CaptureService : LifecycleService() {
    private lateinit var coordinator: CaptureCoordinator
    private lateinit var localStore: AppPhotoStore
    private lateinit var torchController: CameraXTorchController
    private lateinit var notificationManager: NotificationManager
    private var isUploadingSavedPhotos = false
    private var torchScheduleJob: Job? = null

    /**
     * 撮影・送信サイクルはこの Service 専用の scope で回す。**lifecycleScope ではない。**
     *
     * ACTION_STOP は stopForeground() の直後に stopSelf() を呼び、それが誘発する
     * onDestroy() は lifecycleScope をキャンセルする。coordinator の scope がそれと
     * 同じだと、CaptureCoordinator.stop() が保証する「送信中のぶんは中断しない」
     * （docs/03-native.md 6 節）が Service 単位では破れる（マルチエージェントレビューで確認、
     * issue #52 のレビュー知見）。SupervisorJob なので 1 サイクルの失敗が他へ波及しない。
     */
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        val authorizer = SilentDriveAuthorizer(applicationContext)
        localStore = AppPhotoStore(applicationContext)
        CapturePhotoUploadStates.load(applicationContext)
        TorchSettingsRepository.load(applicationContext)
        torchController = CameraXTorchController(applicationContext)
        coordinator =
            CaptureCoordinator(
                photoSource = CameraXPhotoSource(this, this),
                uploader =
                    AppDataUploader(
                        client =
                            OkHttpClient
                                .Builder()
                                .writeTimeout(60, TimeUnit.SECONDS)
                                .readTimeout(60, TimeUnit.SECONDS)
                                .callTimeout(75, TimeUnit.SECONDS)
                                .protocols(listOf(Protocol.HTTP_1_1))
                                .build(),
                        accessToken = { authorizer.accessToken() },
                        logger = if (BuildConfig.DEBUG) AndroidDriveUploadLogger else NoOpDriveUploadLogger,
                    ),
                scheduler = CoroutineCaptureScheduler(coordinatorScope),
                scope = coordinatorScope,
                localStore = localStore,
                uploadStatusStore = CapturePhotoUploadStates,
            )

        CaptureStatusRepository.update(coordinator.state.value)
        // coordinatorScope で購読する。lifecycleScope だと Service の停止（onDestroy）で
        // 購読ごと止まり、停止直後に完了した送信の最終状態が画面へ届かない。
        coordinator.state
            .onEach {
                CaptureStatusRepository.update(it)
                updateNotification(it)
            }.launchIn(coordinatorScope)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)

        when (captureServiceCommand(intent?.action)) {
            CaptureServiceCommand.START -> {
                // startForegroundService() から 5 秒以内に startForeground() を呼ばないと
                // ANR 扱いになるため、撮影開始より先に常駐通知を出す。
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(coordinator.state.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
                coordinator.start(captureInterval(intent))
            }
            CaptureServiceCommand.STOP -> {
                coordinator.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            CaptureServiceCommand.UPLOAD_SAVED -> uploadSavedPhotos()
            CaptureServiceCommand.REFRESH_TORCH -> refreshTorch()
            CaptureServiceCommand.IGNORE -> Unit
        }
        // システムに再生成された場合は起動中フラグを持たないため、明示的な再開始待ち。
        return if (TorchSettingsRepository.settings.value.automaticEnabled ||
            TorchSettingsRepository.settings.value.manualEnabled
        ) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    private fun refreshTorch() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(coordinator.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        torchScheduleJob?.cancel()
        torchScheduleJob =
            coordinatorScope.launch {
                do {
                    applyTorchState()
                    if (!TorchSettingsRepository.settings.value.automaticEnabled) break
                    delay(TORCH_SCHEDULE_CHECK_MILLIS)
                } while (true)
                stopIfIdle()
            }
    }

    private suspend fun applyTorchState() {
        val enabled = TorchSettingsRepository.settings.value.shouldEnableAt(LocalTime.now())
        torchController.setEnabled(enabled).fold(
            onSuccess = { TorchSettingsRepository.markApplied(enabled) },
            onFailure = { failure -> TorchSettingsRepository.markFailed(failure.message ?: "ライトを切り替えられません") },
        )
    }

    private fun stopIfIdle() {
        val settings = TorchSettingsRepository.settings.value
        if (!coordinator.state.value.isRunning && !isUploadingSavedPhotos && !settings.automaticEnabled && !settings.manualEnabled) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun uploadSavedPhotos() {
        if (isUploadingSavedPhotos) return
        isUploadingSavedPhotos = true
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(coordinator.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        coordinatorScope.launch {
            try {
                localStore.savedPhotos().forEach { photo ->
                    coordinator.uploadSavedPhoto(photo.name, localStore.readPhoto(photo))
                }
            } finally {
                isUploadingSavedPhotos = false
                stopIfIdle()
            }
        }
    }

    private fun updateNotification(state: CaptureState) {
        if (!shouldUpdateCaptureNotification(state)) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: CaptureState): Notification {
        val openAppIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle("定点撮影を実行中")
            .setContentText(buildCaptureNotificationText(state))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "定点撮影", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "capture_service"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.kotonara.farmcamera.action.START_CAPTURE"
        const val ACTION_STOP = "com.kotonara.farmcamera.action.STOP_CAPTURE"
        const val ACTION_UPLOAD_SAVED = "com.kotonara.farmcamera.action.UPLOAD_SAVED"
        const val ACTION_REFRESH_TORCH = "com.kotonara.farmcamera.action.REFRESH_TORCH"
        private const val EXTRA_INTERVAL_MILLIS = "capture_interval_millis"
        private const val TORCH_SCHEDULE_CHECK_MILLIS = 30_000L

        /** [CaptureService] を起動し、撮影サイクルを開始する。 */
        fun start(
            context: Context,
            interval: CaptureIntervalOption,
        ) {
            val intent =
                Intent(context, CaptureService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_INTERVAL_MILLIS, interval.duration.inWholeMilliseconds)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 撮影サイクルを止め、[CaptureService] を終了する。 */
        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        /** アプリ専用領域に保存済みのJPEGをDriveへ同期する。 */
        fun uploadSaved(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_UPLOAD_SAVED)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 手動操作または時刻設定の変更を端末のライトへ反映する。 */
        fun refreshTorch(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_REFRESH_TORCH)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun captureInterval(intent: Intent?): kotlin.time.Duration =
            CaptureIntervalOption
                .fromMilliseconds(intent?.getLongExtra(EXTRA_INTERVAL_MILLIS, -1) ?: -1)
                .duration
    }
}
