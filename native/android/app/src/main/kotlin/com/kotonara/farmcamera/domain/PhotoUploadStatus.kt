package com.kotonara.farmcamera.domain

/** ローカル画像ごとに保存する Drive 送信状況。 */
enum class PhotoUploadStatus {
    PENDING,
    UPLOADED,
    FAILED,
}

/** 撮影・一括送信で共通に使う、画像単位の送信状態の記録先。 */
interface PhotoUploadStatusStore {
    fun markPending(fileName: String)

    fun markUploaded(fileName: String)

    fun markFailed(fileName: String)
}

object NoOpPhotoUploadStatusStore : PhotoUploadStatusStore {
    override fun markPending(fileName: String) = Unit

    override fun markUploaded(fileName: String) = Unit

    override fun markFailed(fileName: String) = Unit
}
