package com.kotonara.farmcamera.data

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.kotonara.farmcamera.domain.TorchController
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * CameraX でトーチ（常時点灯ライト）を制御する実装（docs/03-native.md 7 節 / issue #10）。
 *
 * トーチを点けるには CameraX にカメラをバインドし続ける必要があるため、OFF にするまで
 * バインドを保持する。[CameraXPhotoSource] とは別に独立してバインド/解除するため、
 * 撮影とトーチ ON を同時に使うと互いの `unbindAll()` で干渉する。M2 スパイクでは
 * 両者を同時に使わないため許容する。
 */
class CameraXTorchController(private val context: Context, private val lifecycleOwner: LifecycleOwner) :
    TorchController {

    private var boundCamera: Camera? = null

    override suspend fun setEnabled(enabled: Boolean): Result<Unit> = runCatching {
        val camera = boundCamera ?: bindCamera().also { boundCamera = it }
        check(camera.cameraInfo.hasFlashUnit()) { "この端末にはトーチ（フラッシュ）がありません" }

        awaitCompletion(camera.cameraControl.enableTorch(enabled))

        if (!enabled) {
            awaitProvider().unbindAll()
            boundCamera = null
        }
    }

    private suspend fun bindCamera(): Camera {
        val imageCapture = ImageCapture.Builder().build()
        return awaitProvider().bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private suspend fun awaitCompletion(future: ListenableFuture<Void>) =
        suspendCancellableCoroutine<Unit> { continuation ->
            future.addListener(
                {
                    try {
                        future.get()
                        continuation.resume(Unit)
                    } catch (failure: Exception) {
                        continuation.resumeWithException(failure)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }
}
