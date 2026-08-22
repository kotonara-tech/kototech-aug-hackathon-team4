package com.kotonara.farmcamera.domain

/**
 * JPEG を撮影サイクルから切り離して送信する抽象。
 *
 * 実装は `data` 層に閉じ込め、`CaptureCoordinator` は送信先の詳細を知らない。
 */
interface PhotoUploader {
    /** 成功時は Google Drive のファイル ID を返す。 */
    suspend fun upload(
        fileName: String,
        jpeg: ByteArray,
    ): Result<String>
}
