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
import androidx.lifecycle.lifecycleScope
import com.kotonara.farmcamera.MainActivity
import com.kotonara.farmcamera.data.AppDataUploader
import com.kotonara.farmcamera.data.CameraXPhotoSource
import com.kotonara.farmcamera.data.CoroutineCaptureScheduler
import com.kotonara.farmcamera.data.SilentDriveAuthorizer
import com.kotonara.farmcamera.domain.CAPTURE_INTERVAL
import com.kotonara.farmcamera.domain.CaptureCoordinator
import com.kotonara.farmcamera.domain.CaptureState
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.OkHttpClient

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
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        val authorizer = SilentDriveAuthorizer(applicationContext)
        coordinator = CaptureCoordinator(
            photoSource = CameraXPhotoSource(this, this),
            uploader = AppDataUploader(client = OkHttpClient(), accessToken = { authorizer.accessToken() }),
            scheduler = CoroutineCaptureScheduler(lifecycleScope),
            scope = lifecycleScope
        )

        coordinator.state.onEach { updateNotification(it) }.launchIn(lifecycleScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                // startForegroundService() から 5 秒以内に startForeground() を呼ばないと
                // ANR 扱いになるため、撮影開始より先に常駐通知を出す。
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(coordinator.state.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
                coordinator.start(CAPTURE_INTERVAL)
            }
            ACTION_STOP -> {
                coordinator.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // システムに再生成された場合は起動中フラグを持たないため、明示的な再開始待ち。
        return START_NOT_STICKY
    }

    private fun updateNotification(state: CaptureState) {
        if (!state.isRunning) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: CaptureState): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = buildString {
            append("撮影 ${state.capturedCount} 枚 / 送信 ${state.uploadedCount} 枚")
            state.lastError?.let { append(" / 直近エラー: $it") }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("定点撮影を実行中")
            .setContentText(text)
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

        /** [CaptureService] を起動し、撮影サイクルを開始する。 */
        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 撮影サイクルを止め、[CaptureService] を終了する。 */
        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
