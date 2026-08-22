package com.kotonara.farmcamera.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveUploadRequestFactoryTest {
    @Test
    fun `AppData 用 URL と Bearer と multipart ボディを組み立てる`() {
        val request =
            factory().create(
                accessToken = "access-token",
                fileName = "CAM001_20260822_063000.jpg",
                jpeg = byteArrayOf(0x01, 0x02, 0x03),
            )

        assertEquals("POST", request.method)
        assertEquals("/upload/drive/v3/files?uploadType=multipart", request.url.encodedPath + "?" + request.url.query)
        assertEquals("Bearer access-token", request.header("Authorization"))
        assertTrue(
            request.body!!
                .contentType()
                .toString()
                .startsWith("multipart/related; boundary="),
        )
    }

    @Test
    fun `JSON に使うファイル名をエスケープする`() {
        val request =
            factory().create(
                accessToken = "access-token",
                fileName = "CAM001_\"quoted\".jpg",
                jpeg = byteArrayOf(),
            )

        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()

        assertTrue(body.contains("CAM001_\\\"quoted\\\".jpg"))
        assertTrue(body.contains("\"parents\":[\"appDataFolder\"]"))
    }

    @Test
    fun `metadata と JPEG バイト列を multipart related の各パートに入れる`() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val request =
            factory().create(
                accessToken = "access-token",
                fileName = "CAM001_20260822_063000.jpg",
                jpeg = jpeg,
            )

        val body = Buffer().also { request.body!!.writeTo(it) }.readByteString()
        val text = body.utf8()

        assertTrue(text.contains("Content-Type: application/json; charset=utf-8"))
        assertTrue(text.contains("\"mimeType\":\"image/jpeg\""))
        assertTrue(text.contains("Content-Type: image/jpeg"))
        assertTrue(body.indexOf(jpeg.toByteString()) >= 0)
    }

    private fun factory() =
        DriveUploadRequestFactory(
            "https://example.test/upload/drive/v3/files?uploadType=multipart".toHttpUrl(),
        )
}
