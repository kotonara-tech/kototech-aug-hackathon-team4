package com.kotonara.farmcamera.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class CaptureIntervalTest {
    @Test
    fun `暫定の撮影間隔は5分である`() {
        assertEquals(5, CAPTURE_INTERVAL_MINUTES)
        assertEquals(5.minutes, CAPTURE_INTERVAL)
    }
}
