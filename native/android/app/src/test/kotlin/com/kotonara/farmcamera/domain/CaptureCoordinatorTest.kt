package com.kotonara.farmcamera.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 撮影サイクルの状態機械のテスト。仮想時間で駆動する。
 *
 * 本命は「送信が撮影間隔より長くても撮影がスキップされない」こと。旧 Flutter 実装は
 * ここで退行を作り込んだ（#17 / docs/03-native.md 6 節）。
 *
 * 送信を投げるスコープに `backgroundScope` を使うのは、`runTest` 自身のスコープだと
 * 送信中の coroutine が終わるまでテストが返らず、遅い送信を再現できないため。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureCoordinatorTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    /** JST 2026-08-22 06:03:00。ファイル名の期待値を読みやすくするため端数を持たせない。 */
    private val startedAt = Instant.parse("2026-08-21T21:03:00Z")

    private val clock = MutableClock(startedAt, zone)
    private val source = FakePhotoSource()
    private val uploader = FakePhotoUploader()
    private val scheduler = FakeCaptureScheduler()
    private val localStore = RecordingLocalPhotoStore()
    private val uploadStatusStore = RecordingPhotoUploadStatusStore()

    // ---- 開始と停止 ----

    @Test
    fun `start すると実行中になり、スケジューラを指定した撮影間隔で起動する`() =
        runTest {
            val coordinator = newCoordinator(backgroundScope)

            assertTrue(coordinator.start(5.minutes))

            assertTrue(coordinator.state.value.isRunning)
            assertEquals(5.minutes, scheduler.startedInterval)
        }

    @Test
    fun `実行中の start は拒否し、スケジューラを二重に起動しない`() =
        runTest {
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            assertFalse("2 回目の start は拒否されるべき", coordinator.start(1.minutes))
            assertEquals("間隔が上書きされてはいけない", 5.minutes, scheduler.startedInterval)
        }

    @Test
    fun `スケジューラが起動を拒否したら実行中にしない`() =
        runTest {
            scheduler.acceptsStart = false
            val coordinator = newCoordinator(backgroundScope)

            assertFalse(coordinator.start(5.minutes))
            assertFalse(
                "動いていないのに実行中と表示すると、撮れていないことに気付けない",
                coordinator.state.value.isRunning,
            )
        }

    @Test
    fun `stop するとスケジューラを止め、実行中を解除する`() =
        runTest {
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            coordinator.stop()

            assertFalse(coordinator.state.value.isRunning)
            assertEquals(1, scheduler.stopCount)
        }

    @Test
    fun `停止中の stop はスケジューラを止めに行かない`() =
        runTest {
            val coordinator = newCoordinator(backgroundScope)

            coordinator.stop()

            assertEquals(0, scheduler.stopCount)
        }

    @Test
    fun `stop したあと再度 start できる`() =
        runTest {
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)
            coordinator.stop()

            assertTrue(coordinator.start(1.minutes))
            assertEquals(1.minutes, scheduler.startedInterval)
        }

    // ---- ★ 本命: 送信は撮影を待たせない ----

    @Test
    fun `送信が撮影間隔より長くても、撮影はスキップされない`() =
        runTest {
            uploader.uploadDuration = 6.minutes
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            repeat(4) { index ->
                if (index > 0) advanceTimeBy(5.minutes)
                scheduler.tick()
                runCurrent()
            }

            assertEquals("撮影中フラグを送信完了まで持つと 2 回に減る（#17 の退行）", 4, source.captureCount)
            assertEquals(4, coordinator.state.value.capturedCount)
        }

    @Test
    fun `撮影が終わるまでの発火は無視する（同時に2枚撮らない）`() =
        runTest {
            source.captureDuration = 2.minutes
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(1.minutes)

            scheduler.tick()
            runCurrent()
            advanceTimeBy(1.minutes)
            scheduler.tick()
            runCurrent()

            assertEquals("撮影中に来た発火で 2 枚目を撮り始めてはいけない", 1, source.captureCount)

            advanceTimeBy(2.minutes)
            scheduler.tick()
            runCurrent()

            assertEquals("撮影が終わっていれば次は撮る", 2, source.captureCount)
        }

    @Test
    fun `撮影中に来た発火はスキップを直近エラーに記録する（無言で捨てない）`() =
        runTest {
            source.captureDuration = 2.minutes
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(1.minutes)

            scheduler.tick()
            runCurrent()
            advanceTimeBy(1.minutes)
            scheduler.tick()
            runCurrent()

            assertEquals("撮影中に来た発火で 2 枚目を撮り始めてはいけない", 1, source.captureCount)
            assertTrue(
                "スキップした痕跡が状態に残らないと、原因不明のまま撮影が止まって見える",
                coordinator.state.value.lastError
                    .orEmpty()
                    .contains("スキップ"),
            )
        }

    @Test
    fun `stop 後に遅れて届いた発火では撮影しない`() =
        runTest {
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)
            coordinator.stop()

            scheduler.tick()
            runCurrent()

            assertEquals(0, source.captureCount)
        }

    @Test
    fun `stop しても送信中のアップロードは中断しない`() =
        runTest {
            uploader.uploadDuration = 6.minutes
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)
            scheduler.tick()
            runCurrent()

            coordinator.stop()
            advanceTimeBy(10.minutes)
            runCurrent()

            assertEquals("撮り終えたぶんを捨てる理由がない", 1, uploader.completedCount)
            assertEquals(1, coordinator.state.value.uploadedCount)
        }

    // ---- 撮影・保存・送信の受け渡し ----

    @Test
    fun `ファイル名は撮影時刻から組み立て、保存と送信で同じ名前を使う`() =
        runTest {
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            scheduler.tick()
            runCurrent()

            assertEquals("CAM001_20260822_060300.jpg", localStore.saved.single().first)
            assertEquals("CAM001_20260822_060300.jpg", uploader.requests.single().first)
        }

    @Test
    fun `撮影した JPEG をそのまま保存し、そのまま送信する（EXIF を壊さない）`() =
        runTest {
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            scheduler.tick()
            runCurrent()

            assertSame(
                "再エンコードや詰め替えをすると EXIF が落ちる（docs/02-google-drive.md 5 節）",
                FakePhotoSource.JPEG,
                localStore.saved.single().second,
            )
            assertSame(FakePhotoSource.JPEG, uploader.requests.single().second)
        }

    @Test
    fun `送信に成功すると送信枚数と最終送信時刻が更新され、直近エラーが消える`() =
        runTest {
            uploader.uploadDuration = 1.minutes
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)
            scheduler.tick()
            runCurrent()

            val finishedAt = Instant.parse("2026-08-21T21:04:00Z")
            clock.set(finishedAt)
            advanceTimeBy(1.minutes)
            runCurrent()

            val state = coordinator.state.value
            assertEquals(1, state.uploadedCount)
            assertEquals("撮影時刻ではなく送信が終わった時刻を出す", finishedAt, state.lastUploadedAt)
            assertNull(state.lastError)
        }

    // ---- 失敗しても撮影を止めない ----

    @Test
    fun `撮影に失敗しても実行中のままで、次の発火で撮影を続ける`() =
        runTest {
            source.failWith = RuntimeException("カメラが使用中です")
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            scheduler.tick()
            runCurrent()

            assertTrue("1 回の撮影失敗で常駐を止めない", coordinator.state.value.isRunning)
            assertEquals(0, coordinator.state.value.capturedCount)
            assertTrue(
                coordinator.state.value.lastError
                    .orEmpty()
                    .contains("撮影に失敗しました"),
            )

            source.failWith = null
            advanceTimeBy(5.minutes)
            scheduler.tick()
            runCurrent()

            assertEquals(1, coordinator.state.value.capturedCount)
        }

    @Test
    fun `撮影に失敗したら保存も送信もしない`() =
        runTest {
            source.failWith = RuntimeException("カメラが使用中です")
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            scheduler.tick()
            runCurrent()

            assertTrue("撮れていないものを送ってはいけない", uploader.requests.isEmpty())
            assertTrue(localStore.saved.isEmpty())
        }

    @Test
    fun `送信に失敗しても撮影は継続し、直近エラーに載る`() =
        runTest {
            uploader.failWith = RuntimeException("HTTP 503")
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            scheduler.tick()
            runCurrent()

            val state = coordinator.state.value
            assertTrue(state.isRunning)
            assertEquals("撮影自体は成功している", 1, state.capturedCount)
            assertEquals(0, state.uploadedCount)
            assertTrue(state.lastError.orEmpty().contains("送信に失敗しました"))

            advanceTimeBy(5.minutes)
            scheduler.tick()
            runCurrent()

            assertEquals("送信が失敗し続けても撮影は止めない", 2, coordinator.state.value.capturedCount)
        }

    @Test
    fun `送信に失敗しても即座に再送しない（無限リトライを作らない）`() =
        runTest {
            uploader.failWith = RuntimeException("HTTP 503")
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            scheduler.tick()
            advanceTimeBy(60.minutes)
            runCurrent()

            assertEquals("再送は次の撮影サイクルに任せる（docs/03-native.md 12 節）", 1, uploader.requests.size)
        }

    @Test
    fun `保存に失敗しても送信は続行し、そのエラーは送信成功で消えない`() =
        runTest {
            localStore.failWith = RuntimeException("空き容量がありません")
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            scheduler.tick()
            runCurrent()

            val state = coordinator.state.value
            assertEquals("バイト列は手元にあるので送信はできる", 1, uploader.requests.size)
            assertEquals(1, state.uploadedCount)
            assertTrue(
                "送信成功でサイクル中の別の失敗を握り潰さない",
                state.lastError.orEmpty().contains("保存に失敗しました"),
            )
        }

    @Test
    fun `保存と送信の両方が失敗すると、両方のエラーメッセージが直近エラーに残る`() =
        runTest {
            localStore.failWith = RuntimeException("空き容量がありません")
            uploader.failWith = RuntimeException("HTTP 503")
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            scheduler.tick()
            runCurrent()

            val lastError =
                coordinator.state.value.lastError
                    .orEmpty()
            assertTrue("保存失敗が送信失敗のメッセージに上書きされて消えてはいけない", lastError.contains("保存に失敗しました"))
            assertTrue(lastError.contains("送信に失敗しました"))
        }

    @Test
    fun `撮影が続いても Drive 送信は常に1本だけ実行する`() =
        runTest {
            uploader.uploadDuration = 1.minutes
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(1.minutes)

            scheduler.tick()
            runCurrent()
            advanceTimeBy(30.seconds)
            scheduler.tick()
            runCurrent()

            assertEquals(2, coordinator.state.value.capturedCount)
            assertEquals(1, uploader.maxConcurrentUploads)
        }

    @Test
    fun `Drive送信の成功と失敗を画像単位の状態として記録する`() =
        runTest {
            val coordinator = newCoordinator(backgroundScope)
            coordinator.start(5.minutes)

            scheduler.tick()
            runCurrent()

            val fileName = localStore.saved.single().first
            assertEquals(listOf(fileName), uploadStatusStore.pending)
            assertEquals(listOf(fileName), uploadStatusStore.uploaded)

            uploader.failWith = RuntimeException("HTTP 503")
            coordinator.uploadSavedPhoto(fileName, FakePhotoSource.JPEG)

            assertEquals(listOf(fileName, fileName), uploadStatusStore.pending)
            assertEquals(listOf(fileName), uploadStatusStore.failed)
        }

    private fun newCoordinator(scope: CoroutineScope): CaptureCoordinator =
        CaptureCoordinator(
            photoSource = source,
            uploader = uploader,
            localStore = localStore,
            uploadStatusStore = uploadStatusStore,
            scheduler = scheduler,
            scope = scope,
            clock = clock,
        )
}
