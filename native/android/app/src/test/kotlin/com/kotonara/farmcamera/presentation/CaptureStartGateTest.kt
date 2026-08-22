package com.kotonara.farmcamera.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartGateTest {
    @Test
    fun `Android 13以上では通知権限を要求する`() {
        assertFalse(CaptureStartGate.requiresNotificationPermission(32))
        assertTrue(CaptureStartGate.requiresNotificationPermission(33))
    }

    @Test
    fun `開始にはカメラ権限と必要な通知権限が要る`() {
        assertFalse(CaptureStartGate.isReady(false, true, 36))
        assertFalse(CaptureStartGate.isReady(true, false, 36))
        assertTrue(CaptureStartGate.isReady(true, true, 36))
        assertTrue(CaptureStartGate.isReady(true, false, 32))
    }
}
