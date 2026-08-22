package com.kotonara.farmcamera.presentation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class CaptureScreenFormattingTest {
    @Test
    fun `最終送信時刻をJSTのyyyy年MM月dd日HH時mm分ss秒形式に変換する`() {
        // UTC 04:41:42 は JST 13:41:42（M2実機検証で確認した imageMediaMetadata.time と同じ時刻）。
        val instant = Instant.parse("2026-08-22T04:41:42Z")

        assertEquals("2026/08/22 13:41:42", instant.toJstTime())
    }
}
