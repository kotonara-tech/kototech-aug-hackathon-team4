package com.kotonara.farmcamera.data

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.kotonara.farmcamera.domain.TorchController

/**
 * 端末の CameraManager を使ってトーチを切り替える。
 *
 * CameraX の撮影用 UseCase を bind / unbind しないため、定期撮影とライト点灯を
 * 同時に維持できる。
 */
class CameraXTorchController(
    private val context: Context,
) : TorchController {
    override suspend fun setEnabled(enabled: Boolean): Result<Unit> =
        runCatching {
            val manager = context.getSystemService(CameraManager::class.java)
            val cameraId =
                manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: error("この端末にはフラッシュライトがありません")
            manager.setTorchMode(cameraId, enabled)
        }
}
