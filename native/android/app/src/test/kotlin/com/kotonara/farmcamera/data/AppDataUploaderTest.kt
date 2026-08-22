package com.kotonara.farmcamera.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppDataUploaderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `AppData の multipart URL と Bearer と親フォルダを送る`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"file-123"}"""))
        val jpeg = byteArrayOf(0x01, 0x02, 0x03)
        val uploader = uploader()

        val result = uploader.upload("CAM001_20260822_063000.jpg", jpeg)
        val request = server.takeRequest()

        assertEquals("file-123", result.getOrThrow())
        assertEquals("POST", request.method)
        assertEquals("/upload/drive/v3/files?uploadType=multipart", request.path)
        assertEquals("Bearer access-token", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/related; boundary="))
        assertTrue(request.body.readUtf8().contains("\"parents\":[\"appDataFolder\"]"))
    }

    @Test
    fun `401 403 と 5xx は失敗として返す`() = runBlocking {
        listOf(401, 403, 500, 503).forEach { statusCode ->
            server.enqueue(MockResponse().setResponseCode(statusCode).setBody("failure"))

            val result = uploader().upload("CAM001_20260822_063000.jpg", byteArrayOf())

            assertTrue("HTTP $statusCode は失敗にする", result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("HTTP $statusCode"))
        }
    }

    @Test
    fun `ファイル ID がない成功レスポンスは失敗として返す`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = uploader().upload("CAM001_20260822_063000.jpg", byteArrayOf())

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("file id"))
    }

    @Test
    fun `アクセストークン取得に失敗したら HTTP リクエストを送らない`() = runBlocking {
        val uploader = AppDataUploader(
            client = OkHttpClient(),
            accessToken = { Result.failure(IllegalStateException("authorization is required")) },
            uploadUrl = server.url("/upload/drive/v3/files?uploadType=multipart")
        )

        val result = uploader.upload("CAM001_20260822_063000.jpg", byteArrayOf())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("authorization is required"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `通信切断は失敗として返す`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = uploader().upload("CAM001_20260822_063000.jpg", byteArrayOf())

        assertTrue(result.isFailure)
    }

    @Test
    fun `既定の Drive URL を使う場合でもトークン失敗を返す`() = runBlocking {
        val uploader = AppDataUploader(
            client = OkHttpClient(),
            accessToken = { Result.failure(IllegalStateException("authorization is required")) }
        )

        val result = uploader.upload("CAM001_20260822_063000.jpg", byteArrayOf())

        assertTrue(result.isFailure)
    }

    private fun uploader() = AppDataUploader(
        client = OkHttpClient(),
        accessToken = { Result.success("access-token") },
        uploadUrl = server.url("/upload/drive/v3/files?uploadType=multipart")
    )
}
