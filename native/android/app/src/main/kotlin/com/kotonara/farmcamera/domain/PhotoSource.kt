package com.kotonara.farmcamera.domain

/**
 * 1 枚撮影して JPEG のバイト列を返す抽象（docs/03-native.md 7 節）。
 *
 * CameraX 実装は `data` 層に置く。ここを interface にしているのは、撮影ロジックを
 * 実機なしでテストするため。
 *
 * 実装側の要件: **JPEG を再エンコードしない。バイト列を加工しない。**
 * EXIF の撮影時刻を Web が使うため、ここで落とすと時系列が壊れる
 * （docs/02-google-drive.md 5 節）。
 */
interface PhotoSource {
    suspend fun capture(): Result<ByteArray>
}
