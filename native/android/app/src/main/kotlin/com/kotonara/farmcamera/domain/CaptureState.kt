package com.kotonara.farmcamera.domain

import java.time.Instant

/**
 * 撮影サイクルの状態（docs/03-native.md 7 節）。
 *
 * `StateFlow<CaptureState>` として公開し、Compose 側で `collectAsState()` する。
 * 遷移を [CaptureState] 自身の純粋関数として置いているのは、状態の意味を
 * `CaptureCoordinator` の制御フローから切り離してテストできるようにするため。
 */
data class CaptureState(
    val isRunning: Boolean = false,
    val capturedCount: Int = 0,
    val uploadedCount: Int = 0,
    val lastUploadedAt: Instant? = null,
    val lastError: String? = null
) {

    /**
     * 撮影を開始する。**直近エラーは消す。**
     *
     * 開始し直したのに前回のエラーが残っていると、今このサイクルで失敗したものと
     * 誤読される。
     */
    fun started(): CaptureState = copy(isRunning = true, lastError = null)

    /**
     * 撮影を停止する。件数と直近エラーは残す。
     *
     * 停止しても「何枚撮って何枚送れたか」「なぜ失敗したか」は利用者が見たい情報で、
     * 消す理由がない。
     */
    fun stopped(): CaptureState = copy(isRunning = false)

    /** 1 枚撮れた。送信の成否とは独立に数える。 */
    fun captured(): CaptureState = copy(capturedCount = capturedCount + 1)

    /**
     * 1 枚送れた。[at] は**送信が完了した時刻**。
     *
     * [error] に値を渡すと、送信が成功していても直近エラーとして残す。同じサイクルの
     * 別の失敗（保存など）を、送信成功で握り潰さないため（docs/03-native.md 12 節）。
     */
    fun uploaded(at: Instant, error: String? = null): CaptureState =
        copy(uploadedCount = uploadedCount + 1, lastUploadedAt = at, lastError = error)

    /** 失敗を記録する。件数や最終送信時刻は巻き戻さない。 */
    fun failed(message: String): CaptureState = copy(lastError = message)
}
