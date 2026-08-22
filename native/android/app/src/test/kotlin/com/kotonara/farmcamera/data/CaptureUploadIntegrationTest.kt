package com.kotonara.farmcamera.data

import com.kotonara.farmcamera.domain.CaptureCoordinator
import com.kotonara.farmcamera.domain.FakeCaptureScheduler
import com.kotonara.farmcamera.domain.FakePhotoSource
import com.kotonara.farmcamera.domain.MutableClock
import com.kotonara.farmcamera.domain.RecordingLocalPhotoStore
import com.kotonara.farmcamera.domain.RecordingPhotoUploadStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * 撮影・端末保存・Drive 送信をつなぐ JVM 統合テスト。
 *
 * CameraX と実際の Drive API は使わず、撮影元は fake、HTTP は MockWebServer に置き換える。
 * そのため実機不要で、アプリの主要な送信経路を一連で検証できる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureUploadIntegrationTest {
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
    fun `撮影した画像を保存してDriveへ送信し画面状態を完了へ更新する`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"drive-file-1"}"""))
            val scheduler = FakeCaptureScheduler()
            val store = RecordingLocalPhotoStore()
            val statuses = RecordingPhotoUploadStatusStore()
            val coordinator = coordinator(scheduler, store, statuses, backgroundScope)

            assertTrue(coordinator.start(1.minutes))
            coordinator.captureOnce()

            val fileName = "CAM001_20260822_060300.jpg"
            assertEquals(listOf(fileName), store.saved.map { it.first })
            assertEquals(listOf(fileName), statuses.pending)
            assertEquals(listOf(fileName), statuses.uploaded)
            assertEquals(1, coordinator.state.value.capturedCount)
            assertEquals(1, coordinator.state.value.uploadedCount)
            assertFalse(coordinator.state.value.lastError != null)

            val lookup = server.takeRequest()
            val upload = server.takeRequest()
            assertEquals("GET", lookup.method)
            assertTrue(lookup.path.orEmpty().contains("spaces=appDataFolder"))
            assertEquals("POST", upload.method)
            assertEquals("Bearer access-token", upload.getHeader("Authorization"))
            assertTrue(upload.body.readUtf8().contains("\"parents\":[\"appDataFolder\"]"))
        }

    @Test
    fun `Drive検索が失敗しても画像は端末に保存され送信失敗として扱う`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
            val scheduler = FakeCaptureScheduler()
            val store = RecordingLocalPhotoStore()
            val statuses = RecordingPhotoUploadStatusStore()
            val coordinator = coordinator(scheduler, store, statuses, backgroundScope)

            coordinator.start(1.minutes)
            coordinator.captureOnce()

            val fileName = "CAM001_20260822_060300.jpg"
            assertEquals(listOf(fileName), store.saved.map { it.first })
            assertEquals(listOf(fileName), statuses.pending)
            assertEquals(listOf(fileName), statuses.failed)
            assertEquals(1, coordinator.state.value.capturedCount)
            assertEquals(0, coordinator.state.value.uploadedCount)
            assertTrue(
                coordinator.state.value.lastError
                    .orEmpty()
                    .contains("HTTP 503"),
            )
            assertEquals(1, server.requestCount)
        }

    private fun coordinator(
        scheduler: FakeCaptureScheduler,
        store: RecordingLocalPhotoStore,
        statuses: RecordingPhotoUploadStatusStore,
        scope: CoroutineScope,
    ): CaptureCoordinator {
        val uploader =
            AppDataUploader(
                client = OkHttpClient(),
                accessToken = { Result.success("access-token") },
                uploadUrl = server.url("/upload/drive/v3/files?uploadType=multipart"),
                listUrl = server.url("/drive/v3/files"),
            )
        return CaptureCoordinator(
            photoSource = FakePhotoSource(),
            uploader = uploader,
            scheduler = scheduler,
            scope = scope,
            localStore = store,
            uploadStatusStore = statuses,
            clock = MutableClock(Instant.parse("2026-08-21T21:03:00Z"), ZoneId.of("Asia/Tokyo")),
        )
    }
}
