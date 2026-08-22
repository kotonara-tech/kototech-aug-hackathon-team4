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

    @Test
    fun `自動モードが無効なら手動設定に従う`() {
        assertTrue(TorchSettings(manualEnabled = true).shouldEnableAt(LocalTime.NOON))
        assertFalse(TorchSettings(manualEnabled = false).shouldEnableAt(LocalTime.NOON))
    }

    @Test
    fun `日中の時間帯は開始を含み終了を含まない`() {
        val settings = TorchSettings(automaticEnabled = true, startTime = LocalTime.of(9, 0), endTime = LocalTime.of(17, 0))

        assertFalse(settings.shouldEnableAt(LocalTime.of(8, 59)))
        assertTrue(settings.shouldEnableAt(LocalTime.of(9, 0)))
        assertTrue(settings.shouldEnableAt(LocalTime.of(16, 59)))
        assertFalse(settings.shouldEnableAt(LocalTime.of(17, 0)))
    }
}
