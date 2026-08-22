package com.kotonara.farmcamera.data

import com.kotonara.farmcamera.domain.CaptureScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Coroutine で駆動する [CaptureScheduler]。
 *
 * [scope] を注入するのは、テストから仮想時間のスコープ（`runTest` の `backgroundScope`）
 * を渡せるようにするため。実時間を待つテストは当日必ず落ちる
 * （docs/01-overview.md 5.6）。
 *
 * `WorkManager` を使わないのは、`PeriodicWorkRequest` の最小間隔が 15 分で
 * 「数分ごと」を原理的に満たせないため（docs/03-native.md 2 節）。
 */
class CoroutineCaptureScheduler(
    private val scope: CoroutineScope,
) : CaptureScheduler {
    private var job: Job? = null

    override val isActive: Boolean
        get() = job?.isActive == true

    override fun start(
        interval: Duration,
        onTick: () -> Unit,
    ): Boolean {
        if (isActive) return false
        if (interval <= Duration.ZERO) return false

        job =
            scope.launch {
                // 先に発火してから待つ。この順序が「開始時に即 1 回」を作っている。
                while (isActive) {
                    onTick()
                    delay(interval)
                }
            }
        return true
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}
