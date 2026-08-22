package com.kotonara.farmcamera.domain

import java.time.LocalTime

data class TorchSettings(
    val manualEnabled: Boolean = false,
    val automaticEnabled: Boolean = false,
    val startTime: LocalTime = LocalTime.of(18, 0),
    val endTime: LocalTime = LocalTime.of(6, 0),
    val isTorchOn: Boolean = false,
    val lastError: String? = null,
) {
    fun shouldEnableAt(time: LocalTime): Boolean =
        if (!automaticEnabled) {
            manualEnabled
        } else if (startTime == endTime) {
            true
        } else if (startTime < endTime) {
            time >= startTime && time < endTime
        } else {
            time >= startTime || time < endTime
        }
}
