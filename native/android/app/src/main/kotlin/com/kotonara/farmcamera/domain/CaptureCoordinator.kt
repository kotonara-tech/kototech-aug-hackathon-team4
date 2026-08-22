package com.kotonara.farmcamera.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * 撮影 → 保存 → 送信のサイクルを回す状態機械（docs/03-native.md 6 節）。
 *
 * カメラ・保存先・送信先・タイマーをすべて抽象越しに受け取るので、実機なしで
 * テストできる。[scope] は送信を撮影サイクルから切り離して投げるためのもので、
 * 実運用ではフォアグラウンドサービスのスコープを渡す。
 */
class CaptureCoordinator(
    private val photoSource: PhotoSource,
    private val uploader: PhotoUploader,
    private val scheduler: CaptureScheduler,
    private val scope: CoroutineScope,
    private val localStore: LocalPhotoStore = NoOpLocalPhotoStore,
    private val uploadStatusStore: PhotoUploadStatusStore = NoOpPhotoUploadStatusStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val cameraId: String = CAMERA_ID,
) {
    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()
    private val uploadMutex = Mutex()

    /**
     * 撮影中フラグ。**降ろすのは撮影が終わった時点で、送信の完了時点ではない。**
     *
     * ここを送信完了まで持つと、圃場のように電波が弱く送信が撮影間隔を超える環境で
     * 次の撮影がスキップされる。旧 Flutter 実装で実際に作り込んだ退行（#17）で、
     * テストで固定してある。
     *
     * `AtomicBoolean` なのは、発火ごとに別 coroutine が走るため。
     */
    private val capturing = AtomicBoolean(false)

    /**
     * [interval] ごとの撮影を開始する。開始できなければ `false`。
     *
     * 実行中の再呼び出しは拒否する。タイマーが多重起動すると撮影が二重に走る。
     */
    fun start(interval: Duration): Boolean {
        if (_state.value.isRunning) return false

        // スケジューラより先に実行中にする。開始時の即時発火が同期的に届いても
        // [captureOnce] が停止中と判定して撮り逃さないようにするため。
        _state.update { it.started() }

        if (!scheduler.start(interval) { scope.launch { captureOnce() } }) {
            _state.update { it.stopped() }
            return false
        }
        return true
    }

    /** 撮影を停止する。**送信中のぶんは中断しない。**撮り終えた写真を捨てる理由がない。 */
    fun stop() {
        if (!_state.value.isRunning) return
        scheduler.stop()
        _state.update { it.stopped() }
    }

    /**
     * 1 サイクルぶんの撮影・保存・送信。スケジューラの発火ごとに呼ばれる。
     *
     * 失敗しても例外は投げない。どの段階で失敗しても撮影自体は継続し、理由は
     * [state] の `lastError` に載せる。握り潰すと「動いているように見えて
     * 1 枚も上がっていない」状態になる（docs/03-native.md 12 節）。
     */
    suspend fun captureOnce() {
        if (!_state.value.isRunning) return
        if (!capturing.compareAndSet(false, true)) {
            // 前回の撮影がまだ終わっていない。黙って捨てると「原因を示す痕跡が一切残らない」
            // 状態になる（マルチエージェントレビューで確認、issue #52 のレビュー知見）。
            _state.update { it.failed("前回の撮影がまだ完了していないため、この発火はスキップしました") }
            return
        }

        val fileName = buildPhotoFileName(cameraId, LocalDateTime.now(clock))

        val jpeg =
            try {
                photoSource.capture().getOrElse { failure ->
                    _state.update { it.failed("撮影に失敗しました: ${failure.message}") }
                    return
                }
            } finally {
                // ★ 撮影さえ終われば次の発火を受け付ける。送信はこの後ろで並行に走る。
                capturing.set(false)
            }

        _state.update { it.captured(jpeg) }

        // 端末に残すかどうかは Q16（#42）が未決。差し替え箇所をこの 1 行に閉じている。
        val saveError =
            localStore
                .save(fileName, jpeg)
                .exceptionOrNull()
                ?.let { "保存に失敗しました: ${it.message}" }
        if (saveError != null) {
            // 保存に失敗してもバイト列は手元にあるので送信は続ける。
            _state.update { it.failed(saveError) }
        } else {
            uploadStatusStore.markPending(fileName)
        }

        uploadPhoto(fileName, jpeg, saveError)
    }

    /** 端末に保存済みのJPEGを、撮影を行わずDriveへ送る。 */
    suspend fun uploadSavedPhoto(
        fileName: String,
        jpeg: ByteArray,
    ) {
        uploadStatusStore.markPending(fileName)
        uploadPhoto(fileName, jpeg, saveError = null)
    }

    private suspend fun uploadPhoto(
        fileName: String,
        jpeg: ByteArray,
        saveError: String?,
    ) {
        // 撮影は次の周期へ進める一方、Drive 送信は常に1本だけにする。10秒デモ間隔で
        // 大きなJPEGの送信を重ねるとHTTP/2ストリームが詰まり得るため、待機中の撮影結果は
        // この排他区間で順に送る。失敗分を永続化・自動再送するキューは持たない。
        uploadMutex.withLock {
            uploader.upload(fileName, jpeg).fold(
                onSuccess = {
                    uploadStatusStore.markUploaded(fileName)
                    _state.update { state -> state.uploaded(clock.instant(), saveError) }
                },
                onFailure = { failure ->
                    uploadStatusStore.markFailed(fileName)
                    val uploadError = "送信に失敗しました: ${failure.message}"
                    // 保存と送信が同じサイクルで両方失敗すると、あとから上書きする側の
                    // メッセージだけが直近エラーに残り、保存失敗が消えてしまう
                    // （マルチエージェントレビューで確認、issue #52 のレビュー知見）。
                    val message = if (saveError != null) "$saveError / $uploadError" else uploadError
                    _state.update { state -> state.failed(message) }
                },
            )
        }
    }
}
