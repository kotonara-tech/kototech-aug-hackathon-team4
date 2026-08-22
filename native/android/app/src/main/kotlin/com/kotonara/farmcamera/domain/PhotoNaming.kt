package com.kotonara.farmcamera.domain

import java.time.LocalDateTime

/**
 * 端末 ID のプレースホルダー。
 *
 * 「1 Google アカウント = 1 端末 = 1 Web クライアント」という動作契約
 * （docs/01-overview.md 2 節）を採っているため固定値でよい。端末を識別する値ではない。
 */
const val CAMERA_ID: String = "CAM001"

/**
 * `CAM001_yyyyMMdd_HHmmss.jpg` を組み立てる純粋関数。
 *
 * 規約は docs/02-google-drive.md 4.2 が正本。[at] は**撮影時点のローカル時刻（JST）**で、
 * 秒精度まで名前に含める。撮影間隔は最短でも数分なので同一秒の衝突は起きない。
 *
 * Web 側はこの名前を表示にしか使わず、撮影時刻の判定には使わない（同 5 節）。
 * それでも人間がデバッグで読むため、規約は崩さないこと。
 */
fun buildPhotoFileName(
    cameraId: String,
    at: LocalDateTime,
): String {
    fun pad2(value: Int): String = value.toString().padStart(2, '0')

    val date = "${at.year}${pad2(at.monthValue)}${pad2(at.dayOfMonth)}"
    val time = "${pad2(at.hour)}${pad2(at.minute)}${pad2(at.second)}"
    return "${cameraId}_${date}_$time.jpg"
}
