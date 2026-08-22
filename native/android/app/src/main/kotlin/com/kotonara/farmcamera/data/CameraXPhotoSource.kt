package com.kotonara.farmcamera.data

import android.content.Context
import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.kotonara.farmcamera.domain.PhotoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * プレビューなしで JPEG を1枚撮影する CameraX 実装（docs/03-native.md 7 節）。
 *
 * `ImageCapture` のみをバインドする。`OnImageCapturedCallback` が返す [ImageProxy] の
 * バッファをそのままコピーするだけで、**再エンコードしない**。EXIF を落とさないため
 * （docs/02-google-drive.md 5 節）。
 */
class CameraXPhotoSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : PhotoSource {
    override suspend fun capture(): Result<ByteArray> =
        runCatching {
            withContext(Dispatchers.Main.immediate) {
                val provider = awaitCameraProvider()
                val imageCapture = ImageCapture.Builder().setBufferFormat(ImageFormat.JPEG).build()

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
                try {
                    awaitCaptureJpeg(imageCapture)
                } finally {
                    provider.unbindAll()
                }
            }
        }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        continuation.resume(future.get())
                    } catch (failure: Exception) {
                        continuation.resumeWithException(failure)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

    private suspend fun awaitCaptureJpeg(imageCapture: ImageCapture): ByteArray =
        suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val jpeg = image.toJpegByteArray()
                        image.close()
                        continuation.resume(jpeg)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }

    private fun ImageProxy.toJpegByteArray(): ByteArray {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
}
