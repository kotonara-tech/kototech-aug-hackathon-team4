package com.kotonara.farmcamera.presentation

import com.kotonara.farmcamera.domain.CaptureState

/** Service の Intent 分岐を Android Framework なしで検証する。 */
internal enum class CaptureServiceCommand {
    START,
    STOP,
    UPLOAD_SAVED,
    REFRESH_TORCH,
    IGNORE,
}

internal fun captureServiceCommand(action: String?): CaptureServiceCommand =
    when (action) {
        CaptureService.ACTION_START -> CaptureServiceCommand.START
        CaptureService.ACTION_STOP -> CaptureServiceCommand.STOP
        CaptureService.ACTION_UPLOAD_SAVED -> CaptureServiceCommand.UPLOAD_SAVED
        CaptureService.ACTION_REFRESH_TORCH -> CaptureServiceCommand.REFRESH_TORCH
        else -> CaptureServiceCommand.IGNORE
    }

/** 実行中だけ通知を更新し、停止済み Service を再表示しない。 */
internal fun shouldUpdateCaptureNotification(state: CaptureState): Boolean = state.isRunning
