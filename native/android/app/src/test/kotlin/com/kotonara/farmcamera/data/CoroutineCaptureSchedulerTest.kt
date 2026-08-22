package com.kotonara.farmcamera.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 仮想時間で駆動する。実時間を待つテストは当日落ちるので書かない
 * （docs/01-overview.md 5.6 / 6.3）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineCaptureSchedulerTest {
    @Test
    fun `開始した時点で1回発火する（最初の1枚を撮影間隔ぶん待たせない）`() =
        runTest {
            val scheduler = CoroutineCaptureScheduler(backgroundScope)
            var ticks = 0

            scheduler.start(5.minutes) { ticks++ }
            runCurrent()

            assertEquals(1, ticks)
        }

    @Test
    fun `撮影間隔ごとに発火する`() =
        runTest {
            val scheduler = CoroutineCaptureScheduler(backgroundScope)
            var ticks = 0

            scheduler.start(5.minutes) { ticks++ }
            runCurrent()
            advanceTimeBy(15.minutes)
            runCurrent()

            assertEquals("開始時の1回 + 15分ぶんの3回", 4, ticks)
        }

    @Test
    fun `動作中の start は拒否し、タイマーを多重起動しない`() =
        runTest {
            val scheduler = CoroutineCaptureScheduler(backgroundScope)
            var ticks = 0

            assertTrue(scheduler.start(5.minutes) { ticks++ })
            assertFalse("2回目の start は拒否されるべき", scheduler.start(5.minutes) { ticks++ })
            runCurrent()
            advanceTimeBy(10.minutes)
            runCurrent()

            assertEquals("多重起動していれば 3 を超える", 3, ticks)
        }

    @Test
    fun `stop 後は発火しない`() =
        runTest {
            val scheduler = CoroutineCaptureScheduler(backgroundScope)
            var ticks = 0

            scheduler.start(5.minutes) { ticks++ }
            runCurrent()
            advanceTimeBy(5.minutes)
            runCurrent()
            scheduler.stop()
            advanceTimeBy(60.minutes)
            runCurrent()

            assertEquals("停止後のタイマー発火は起きてはいけない", 2, ticks)
            assertFalse(scheduler.isActive)
        }

    @Test
    fun `stop したあと再度 start できる`() =
        runTest {
            val scheduler = CoroutineCaptureScheduler(backgroundScope)
            var ticks = 0

            scheduler.start(5.minutes) { ticks++ }
            runCurrent()
            scheduler.stop()

            assertTrue(scheduler.start(5.minutes) { ticks++ })
            runCurrent()
            assertEquals(2, ticks)
        }

    @Test
    fun `開始前は動作中ではない`() =
        runTest {
            val scheduler = CoroutineCaptureScheduler(backgroundScope)

            assertFalse(scheduler.isActive)
        }

    @Test
    fun `最初の発火前に停止するとコールバックを呼ばない`() =
        runTest {
            val scheduler = CoroutineCaptureScheduler(backgroundScope)
            var ticks = 0

            scheduler.start(5.minutes) { ticks++ }
            scheduler.stop()
            runCurrent()

            assertEquals(0, ticks)
            assertFalse(scheduler.isActive)
        }

    @Test
    fun `start すると動作中になる`() =
        runTest {
            val scheduler = CoroutineCaptureScheduler(backgroundScope)

            scheduler.start(5.minutes) {}

            assertTrue(scheduler.isActive)
        }

    @Test
    fun `ゼロ以下の間隔は拒否する（タイマーの暴走を防ぐ）`() =
        runTest {
            val scheduler = CoroutineCaptureScheduler(backgroundScope)
            var ticks = 0

            assertFalse(scheduler.start(0.seconds) { ticks++ })
            assertFalse(scheduler.start((-1).minutes) { ticks++ })
            runCurrent()

            assertEquals(0, ticks)
            assertFalse(scheduler.isActive)
        }

    @Test
    fun `発火のたびにコールバックを呼び直す（同じ呼び出しを使い回さない）`() =
        runTest {
            val scheduler = CoroutineCaptureScheduler(backgroundScope)
            val firedAt = mutableListOf<Long>()

            scheduler.start(5.minutes) { firedAt += currentTime }
            runCurrent()
            advanceTimeBy(10.minutes)
            runCurrent()

            assertEquals(listOf(0L, 300_000L, 600_000L), firedAt)
        }
}
