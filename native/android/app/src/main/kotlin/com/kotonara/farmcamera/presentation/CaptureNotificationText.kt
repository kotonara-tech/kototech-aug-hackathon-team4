package com.kotonara.farmcamera.presentation

import com.kotonara.farmcamera.domain.CaptureState

/**
 * 常駐通知の本文を組み立てる純粋関数（[CaptureService] から抽出。テストのため）。
 *
 * 撮影枚数・送信枚数は常に表示し、直近エラーがあるときだけ末尾に付け加える。
 */
internal fun buildCaptureNotificationText(state: CaptureState): String =
    buildString {
        append("撮影 ${state.capturedCount} 枚 / 送信 ${state.uploadedCount} 枚")
        state.lastError?.let { append(" / 直近エラー: $it") }
    }
