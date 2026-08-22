package com.kotonara.farmcamera.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class TorchScheduleTest {
    @Test
    fun `夜をまたぐ自動点灯時間帯を判定できる`() {
        val settings = TorchSettings(automaticEnabled = true, startTime = LocalTime.of(18, 0), endTime = LocalTime.of(6, 0))

        assertTrue(settings.shouldEnableAt(LocalTime.of(18, 0)))
        assertTrue(settings.shouldEnableAt(LocalTime.of(2, 0)))
        assertFalse(settings.shouldEnableAt(LocalTime.of(6, 0)))
        assertFalse(settings.shouldEnableAt(LocalTime.of(12, 0)))
    }

    @Test
    fun `同じ開始終了時刻は終日点灯として扱う`() {
        val settings = TorchSettings(automaticEnabled = true, startTime = LocalTime.NOON, endTime = LocalTime.NOON)

        assertTrue(settings.shouldEnableAt(LocalTime.MIDNIGHT))
    }
}
