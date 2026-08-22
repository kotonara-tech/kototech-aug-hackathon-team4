package com.kotonara.farmcamera.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** デモと通常利用で選べる撮影間隔。 */
enum class CaptureIntervalOption(
    val label: String,
    val duration: Duration,
) {
    DEMO_10_SECONDS("10秒", 10.seconds),
    DEMO_1_MINUTE("1分", 1.minutes),
    NORMAL_5_MINUTES("5分", 5.minutes),
    ;

    companion object {
        fun fromMilliseconds(value: Long): CaptureIntervalOption =
            entries.firstOrNull { it.duration.inWholeMilliseconds == value } ?: NORMAL_5_MINUTES
    }
}
