package com.kotonara.farmcamera.presentation

import com.kotonara.farmcamera.domain.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureServiceCommandTest {
    @Test
    fun `開始停止と未知の Intent を区別する`() {
        assertEquals(CaptureServiceCommand.START, captureServiceCommand(CaptureService.ACTION_START))
        assertEquals(CaptureServiceCommand.STOP, captureServiceCommand(CaptureService.ACTION_STOP))
        assertEquals(CaptureServiceCommand.UPLOAD_SAVED, captureServiceCommand(CaptureService.ACTION_UPLOAD_SAVED))
        assertEquals(CaptureServiceCommand.REFRESH_TORCH, captureServiceCommand(CaptureService.ACTION_REFRESH_TORCH))
        assertEquals(CaptureServiceCommand.IGNORE, captureServiceCommand(null))
        assertEquals(CaptureServiceCommand.IGNORE, captureServiceCommand("unknown"))
    }

    @Test
    fun `実行中だけ通知を更新する`() {
        assertTrue(shouldUpdateCaptureNotification(CaptureState(isRunning = true)))
        assertFalse(shouldUpdateCaptureNotification(CaptureState(isRunning = false)))
    }
}
