package com.kotonara.farmcamera.presentation

import com.kotonara.farmcamera.domain.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureNotificationTextTest {
    @Test
    fun `直近エラーが無いときは撮影枚数と送信枚数だけを表示する`() {
        val state = CaptureState(capturedCount = 3, uploadedCount = 2)

        assertEquals("撮影 3 枚 / 送信 2 枚", buildCaptureNotificationText(state))
    }

    @Test
    fun `直近エラーがあるときは末尾に付け加える`() {
        val state = CaptureState(capturedCount = 1, uploadedCount = 0, lastError = "送信に失敗しました")

        assertEquals("撮影 1 枚 / 送信 0 枚 / 直近エラー: 送信に失敗しました", buildCaptureNotificationText(state))
    }
}
