package com.kotonara.farmcamera.domain

import kotlin.time.Duration

/**
 * 一定間隔でコールバックを発火させる抽象（docs/03-native.md 7 節）。
 *
 * 実装は `data` 層に置く。ここを interface にしておくことで、撮影ループを回す側を
 * 実機なしでテストできる。
 */
interface CaptureScheduler {
    /** 動作中か。停止後・開始前は false。 */
    val isActive: Boolean

    /**
     * [interval] ごとに [onTick] を発火させる。**開始した時点で即 1 回発火する。**
     *
     * 次のサイクルまで待たせると、デモで「開始したのに数分間何も起きない」状態になる。
     *
     * 開始できなければ `false` を返す。動作中の再呼び出しを拒否するのは、
     * タイマーが多重起動して撮影が二重に走るのを防ぐため。
     * ゼロ以下の [interval] も拒否する。受け付けるとタイマーが暴走する。
     */
    fun start(
        interval: Duration,
        onTick: () -> Unit,
    ): Boolean

    /** 停止する。停止後は発火しない。 */
    fun stop()
}
