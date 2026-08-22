package com.kotonara.farmcamera.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 状態遷移だけを見る純粋なテスト。`android.*` も coroutine も要らない
 * （docs/03-native.md 9 節）。
 */
class CaptureStateTest {
    private val at = Instant.parse("2026-08-22T06:03:00Z")

    @Test
    fun `初期状態は停止中で、撮影も送信も0件である`() {
        val state = CaptureState()

        assertFalse(state.isRunning)
        assertEquals(0, state.capturedCount)
        assertEquals(0, state.uploadedCount)
        assertNull(state.lastUploadedAt)
        assertNull(state.lastError)
    }

    @Test
    fun `started は実行中にし、前回の直近エラーを消す`() {
        val state = CaptureState(lastError = "送信に失敗しました").started()

        assertTrue(state.isRunning)
        assertNull("開始し直したのに古いエラーが残ると、今のエラーだと誤読される", state.lastError)
    }

    @Test
    fun `stopped は実行中を解除するが、件数と直近エラーは保つ`() {
        val state =
            CaptureState(
                isRunning = true,
                capturedCount = 3,
                uploadedCount = 2,
                lastUploadedAt = at,
                lastError = "送信に失敗しました",
            ).stopped()

        assertFalse(state.isRunning)
        assertEquals(3, state.capturedCount)
        assertEquals(2, state.uploadedCount)
        assertEquals(at, state.lastUploadedAt)
        assertEquals("停止しても失敗した事実は消さない", "送信に失敗しました", state.lastError)
    }

    @Test
    fun `captured は撮影枚数だけを増やす`() {
        val jpeg = byteArrayOf(1, 2, 3)
        val state = CaptureState(capturedCount = 1, uploadedCount = 1, lastUploadedAt = at).captured(jpeg)

        assertEquals(2, state.capturedCount)
        assertEquals("撮影しただけで送信枚数が動いてはいけない", 1, state.uploadedCount)
        assertEquals(at, state.lastUploadedAt)
        assertEquals(jpeg.toList(), state.latestJpeg?.toList())
    }

    @Test
    fun `uploaded は送信枚数と最終送信時刻を更新する`() {
        val state = CaptureState(capturedCount = 1).uploaded(at)

        assertEquals(1, state.uploadedCount)
        assertEquals(at, state.lastUploadedAt)
        assertEquals("送信しただけで撮影枚数が動いてはいけない", 1, state.capturedCount)
    }

    @Test
    fun `uploaded は直近エラーを消す`() {
        val state = CaptureState(lastError = "送信に失敗しました").uploaded(at)

        assertNull("送信できているのに失敗表示が残ると、利用者は復旧に気付けない", state.lastError)
    }

    @Test
    fun `uploaded にエラーを渡すと、送信が成功していても直近エラーは残る`() {
        val state = CaptureState().uploaded(at, error = "古い写真を削除できませんでした")

        assertEquals(1, state.uploadedCount)
        assertEquals(
            "送信成功でサイクル中の別の失敗を握り潰さない（docs/03-native.md 12 節）",
            "古い写真を削除できませんでした",
            state.lastError,
        )
    }

    @Test
    fun `failed は直近エラーを載せるだけで、件数を巻き戻さない`() {
        val state =
            CaptureState(capturedCount = 5, uploadedCount = 4, lastUploadedAt = at)
                .failed("送信に失敗しました")

        assertEquals("送信に失敗しました", state.lastError)
        assertEquals(5, state.capturedCount)
        assertEquals(4, state.uploadedCount)
        assertEquals("失敗しても前回成功した時刻は消さない", at, state.lastUploadedAt)
    }
}
