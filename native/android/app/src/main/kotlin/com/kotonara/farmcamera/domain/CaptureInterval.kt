package com.kotonara.farmcamera.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 撮影間隔（分）。値は Q3（docs/00-openquestion.md）が未決のため暫定値。
 *
 * 設定 UI は作らない（docs/03-native.md 8 節）。変更は値を書き換えて
 * 再ビルド＋再インストールする（issue #52）。
 */
const val CAPTURE_INTERVAL_MINUTES: Long = 5

/** [CAPTURE_INTERVAL_MINUTES] を [CaptureScheduler] / [CaptureCoordinator] に渡す形にしたもの。 */
val CAPTURE_INTERVAL: Duration = CAPTURE_INTERVAL_MINUTES.minutes
