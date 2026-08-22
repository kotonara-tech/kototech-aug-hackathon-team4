package com.kotonara.farmcamera.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CaptureIntervalOptionTest {
    @Test
    fun `デモ用と通常用の撮影間隔を提供する`() {
        assertEquals(10.seconds, CaptureIntervalOption.DEMO_10_SECONDS.duration)
        assertEquals(1.minutes, CaptureIntervalOption.DEMO_1_MINUTE.duration)
        assertEquals(5.minutes, CaptureIntervalOption.NORMAL_5_MINUTES.duration)
    }

    @Test
    fun `Intent値が未知なら安全な5分へ戻す`() {
        assertEquals(
            CaptureIntervalOption.DEMO_10_SECONDS,
            CaptureIntervalOption.fromMilliseconds(10.seconds.inWholeMilliseconds),
        )
        assertEquals(CaptureIntervalOption.NORMAL_5_MINUTES, CaptureIntervalOption.fromMilliseconds(-1))
    }
}
