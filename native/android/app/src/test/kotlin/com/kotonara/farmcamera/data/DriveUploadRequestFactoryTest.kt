package com.kotonara.farmcamera.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveUploadRequestFactoryTest {

    @Test
    fun `AppData 用 URL と Bearer と multipart ボディを組み立てる`() {
        val request = factory().create(
            accessToken = "access-token",
            fileName = "CAM001_20260822_063000.jpg",
            jpeg = byteArrayOf(0x01, 0x02, 0x03)
        )

        assertEquals("POST", request.method)
        assertEquals("/upload/drive/v3/files?uploadType=multipart", request.url.encodedPath + "?" + request.url.query)
        assertEquals("Bearer access-token", request.header("Authorization"))
        assertTrue(request.body!!.contentType().toString().startsWith("multipart/related; boundary="))
    }

    @Test
    fun `JSON に使うファイル名をエスケープする`() {
        val request = factory().create(
            accessToken = "access-token",
            fileName = "CAM001_\"quoted\".jpg",
            jpeg = byteArrayOf()
        )

        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()

        assertTrue(body.contains("CAM001_\\\"quoted\\\".jpg"))
        assertTrue(body.contains("\"parents\":[\"appDataFolder\"]"))
    }

    private fun factory() = DriveUploadRequestFactory(
        "https://example.test/upload/drive/v3/files?uploadType=multipart".toHttpUrl()
    )
}
