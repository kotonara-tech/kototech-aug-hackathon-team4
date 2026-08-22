package com.kotonara.farmcamera.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * ファイル名規約は docs/02-google-drive.md 4.2 が正本。
 * Web 側は表示にしか使わないが、人間がデバッグで読むので規約を崩さない。
 */
class PhotoNamingTest {
    @Test
    fun `CAM001_yyyyMMdd_HHmmss_jpg の形式で組み立てる`() {
        val name = buildPhotoFileName(CAMERA_ID, LocalDateTime.of(2026, 8, 22, 6, 30, 0))

        assertEquals("CAM001_20260822_063000.jpg", name)
    }

    @Test
    fun `月日と時分秒をゼロ埋めする`() {
        val name = buildPhotoFileName(CAMERA_ID, LocalDateTime.of(2026, 1, 2, 3, 4, 5))

        assertEquals("CAM001_20260102_030405.jpg", name)
    }

    @Test
    fun `秒までを名前に含める（分精度に丸めない）`() {
        val at = LocalDateTime.of(2026, 8, 22, 6, 30, 59)

        assertEquals("CAM001_20260822_063059.jpg", buildPhotoFileName(CAMERA_ID, at))
    }

    @Test
    fun `ナノ秒は名前に現れない`() {
        val at = LocalDateTime.of(2026, 8, 22, 6, 30, 0, 999_999_999)

        assertEquals("CAM001_20260822_063000.jpg", buildPhotoFileName(CAMERA_ID, at))
    }

    @Test
    fun `端末IDは先頭に置き、区切りはアンダースコアにする`() {
        val name = buildPhotoFileName("CAM042", LocalDateTime.of(2026, 12, 31, 23, 59, 59))

        assertEquals("CAM042_20261231_235959.jpg", name)
    }

    @Test
    fun `端末IDの既定値は CAM001（1端末契約のプレースホルダー）`() {
        assertEquals("CAM001", CAMERA_ID)
    }
}
