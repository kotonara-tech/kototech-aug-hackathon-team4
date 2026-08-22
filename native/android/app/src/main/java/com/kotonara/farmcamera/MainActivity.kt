package com.kotonara.farmcamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kotonara.farmcamera.data.AppDataUploader
import com.kotonara.farmcamera.data.CameraXPhotoSource
import com.kotonara.farmcamera.data.CameraXTorchController
import com.kotonara.farmcamera.data.CredentialAuthGateway
import com.kotonara.farmcamera.domain.AuthGateway
import com.kotonara.farmcamera.domain.CAMERA_ID
import com.kotonara.farmcamera.domain.PhotoSource
import com.kotonara.farmcamera.domain.PhotoUploader
import com.kotonara.farmcamera.domain.TorchController
import com.kotonara.farmcamera.domain.buildPhotoFileName
import com.kotonara.farmcamera.presentation.CaptureService
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * M2 疎通スパイク（issue #51）専用の画面。
 *
 * ボタン1つ: サインイン → drive.appdata の認可 → 1枚撮影 → アップロード →
 * ファイル ID を表示するだけ。**Service もスケジューラも持たない。**
 * Q1 が倒れて捨てるのはこの画面だけで、M1 の domain/data ロジックは生き残る
 * （docs/05-implementation-plan.md 2 節）。M3/M4 の本画面（Compose）とは別物。
 */
class MainActivity : ComponentActivity() {

    private lateinit var authGateway: AuthGateway
    private lateinit var photoSource: PhotoSource
    private lateinit var uploader: PhotoUploader
    private lateinit var torchController: TorchController
    private lateinit var logView: TextView

    private var torchOn = false
    private var pendingCameraPermission: CompletableDeferred<Boolean>? = null
    private var pendingNotificationPermission: CompletableDeferred<Boolean>? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingCameraPermission?.complete(granted)
        pendingCameraPermission = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingNotificationPermission?.complete(granted)
        pendingNotificationPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authGateway = CredentialAuthGateway(this)
        photoSource = CameraXPhotoSource(this, this)
        uploader = AppDataUploader(client = OkHttpClient(), accessToken = { authGateway.accessToken() })
        torchController = CameraXTorchController(this, this)

        setContentView(buildLayout())
    }

    private fun buildLayout(): LinearLayout {
        val startButton = Button(this).apply {
            text = "検証開始（サインイン→認可→撮影→送信）"
            setOnClickListener { runVerification() }
        }

        val torchButton = Button(this).apply {
            text = "トーチ ON/OFF"
            setOnClickListener { toggleTorch() }
        }

        // M3（issue #52）の実機検証用ボタン。M4 で Compose のステータス画面に置き換わるまでの
        // 暫定 UI。「検証開始」でサインイン・drive.appdata 同意を済ませたあとに使う。
        val serviceStartButton = Button(this).apply {
            text = "常駐撮影 開始（Foreground Service）"
            setOnClickListener { startCaptureService() }
        }

        val serviceStopButton = Button(this).apply {
            text = "常駐撮影 停止"
            setOnClickListener {
                CaptureService.stop(this@MainActivity)
                log("常駐撮影を停止しました")
            }
        }

        logView = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            text = "ボタンを押すと02 §7の手順1〜4を実行します。"
        }

        val scroll = ScrollView(this).apply { addView(logView) }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            addView(startButton)
            addView(torchButton)
            addView(serviceStartButton)
            addView(serviceStopButton)
            addView(scroll)
        }
    }

    private fun runVerification() {
        lifecycleScope.launch {
            log("=== 検証開始 ===")

            if (!awaitCameraPermission()) {
                log("✗ カメラ権限が拒否されました。設定から許可してください。")
                return@launch
            }
            log("✓ カメラ権限OK")

            authGateway.signIn().fold(
                onSuccess = { log("✓ サインインOK") },
                onFailure = { failure ->
                    log("✗ サインイン失敗: ${failure.message}")
                    return@launch
                }
            )

            authGateway.accessToken().fold(
                onSuccess = { log("✓ drive.appdata 認可OK") },
                onFailure = { failure ->
                    log("✗ 認可失敗: ${failure.message}")
                    return@launch
                }
            )

            val jpeg = photoSource.capture().fold(
                onSuccess = { bytes ->
                    log("✓ 撮影OK（${bytes.size} bytes）")
                    bytes
                },
                onFailure = { failure ->
                    log("✗ 撮影失敗: ${failure.message}")
                    return@launch
                }
            )

            val fileName = buildPhotoFileName(CAMERA_ID, LocalDateTime.now())
            uploader.upload(fileName, jpeg).fold(
                onSuccess = { fileId ->
                    log("✓ アップロードOK: fileId=$fileId, name=$fileName")
                    log("=== 検証完了。OAuth Playground で files.list?spaces=appDataFolder を確認してください ===")
                },
                onFailure = { failure -> log("✗ アップロード失敗: ${failure.message}") }
            )
        }
    }

    private fun toggleTorch() {
        lifecycleScope.launch {
            if (!awaitCameraPermission()) {
                log("✗ カメラ権限が拒否されました。設定から許可してください。")
                return@launch
            }

            val nextState = !torchOn
            torchController.setEnabled(nextState).fold(
                onSuccess = {
                    torchOn = nextState
                    log(if (torchOn) "✓ トーチON" else "✓ トーチOFF")
                },
                onFailure = { failure -> log("✗ トーチ切替失敗: ${failure.message}") }
            )
        }
    }

    private fun startCaptureService() {
        lifecycleScope.launch {
            if (!awaitCameraPermission()) {
                log("✗ カメラ権限が拒否されました。設定から許可してください。")
                return@launch
            }
            if (!awaitNotificationPermission()) {
                log("✗ 通知権限が拒否されました。常駐通知を出せないため開始できません。")
                return@launch
            }

            CaptureService.start(this@MainActivity)
            log("✓ 常駐撮影を開始しました（先に「検証開始」でサインイン済みであること）")
        }
    }

    private suspend fun awaitCameraPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return true

        val deferred = CompletableDeferred<Boolean>()
        pendingCameraPermission = deferred
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        return deferred.await()
    }

    private suspend fun awaitNotificationPermission(): Boolean {
        // POST_NOTIFICATIONS は API 33+ でのみ実行時リクエストが要る（issue #52）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return true

        val deferred = CompletableDeferred<Boolean>()
        pendingNotificationPermission = deferred
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return deferred.await()
    }

    private fun log(message: String) {
        logView.append("\n$message")
    }
}
