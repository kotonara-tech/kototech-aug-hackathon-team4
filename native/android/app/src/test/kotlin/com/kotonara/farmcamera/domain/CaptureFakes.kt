package com.kotonara.farmcamera.domain

import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration

// [CaptureCoordinator] のテスト用フェイク群。
//
// カメラ・ネットワーク・時計を実物に触らせないために置いている。実機や外部 API を
// 叩くテストは当日必ず落ちる（docs/01-overview.md 6.3 / docs/03-native.md 9 節）。

/** 発火のタイミングをテストが完全に握るスケジューラ。 */
class FakeCaptureScheduler : CaptureScheduler {
    /** `false` にすると start を拒否する（ゼロ以下の間隔を渡された実装の代役）。 */
    var acceptsStart: Boolean = true

    var startedInterval: Duration? = null
        private set

    var stopCount: Int = 0
        private set

    override var isActive: Boolean = false
        private set

    private var onTick: (() -> Unit)? = null

    override fun start(
        interval: Duration,
        onTick: () -> Unit,
    ): Boolean {
        if (!acceptsStart) return false
        startedInterval = interval
        this.onTick = onTick
        isActive = true
        return true
    }

    override fun stop() {
        stopCount++
        isActive = false
    }

    /** スケジューラが 1 回発火したことにする。停止後も呼べる（遅れて届く発火の再現）。 */
    fun tick() {
        val callback = onTick ?: error("start していないスケジューラを発火させようとした")
        callback()
    }
}

/** 撮影を返すフェイク。[captureDuration] で「撮影に時間がかかる」状況を作れる。 */
class FakePhotoSource(
    private val jpeg: ByteArray = JPEG,
) : PhotoSource {
    var captureCount: Int = 0
        private set

    var failWith: Throwable? = null
    var captureDuration: Duration = Duration.ZERO

    override suspend fun capture(): Result<ByteArray> {
        captureCount++
        if (captureDuration > Duration.ZERO) delay(captureDuration)
        return failWith?.let { Result.failure(it) } ?: Result.success(jpeg)
    }

    companion object {
        /** EXIF を壊していないことを見るため、意味のあるバイト列にしておく。 */
        val JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x45, 0x78, 0x69, 0x66)
    }
}

/** 送信のフェイク。[uploadDuration] で「送信が撮影間隔より長い」状況を作れる。 */
class FakePhotoUploader : PhotoUploader {
    val requests: MutableList<Pair<String, ByteArray>> = mutableListOf()

    var uploadDuration: Duration = Duration.ZERO
    var failWith: Throwable? = null

    var completedCount: Int = 0
        private set

    var activeUploadCount: Int = 0
        private set

    var maxConcurrentUploads: Int = 0
        private set

    override suspend fun upload(
        fileName: String,
        jpeg: ByteArray,
    ): Result<String> {
        requests += fileName to jpeg
        activeUploadCount++
        maxConcurrentUploads = maxOf(maxConcurrentUploads, activeUploadCount)
        try {
            if (uploadDuration > Duration.ZERO) delay(uploadDuration)
            completedCount++
            return failWith?.let { Result.failure(it) } ?: Result.success("file-$completedCount")
        } finally {
            activeUploadCount--
        }
    }
}

/** 端末保存のフェイク。Q16（#42）が決まるまで実装は入らないので、記録だけ取る。 */
class RecordingLocalPhotoStore : LocalPhotoStore {
    val saved: MutableList<Pair<String, ByteArray>> = mutableListOf()

    var failWith: Throwable? = null

    override suspend fun save(
        fileName: String,
        jpeg: ByteArray,
    ): Result<Unit> {
        saved += fileName to jpeg
        return failWith?.let { Result.failure(it) } ?: Result.success(Unit)
    }
}

class RecordingPhotoUploadStatusStore : PhotoUploadStatusStore {
    val pending = mutableListOf<String>()
    val uploaded = mutableListOf<String>()
    val failed = mutableListOf<String>()

    override fun markPending(fileName: String) {
        pending += fileName
    }

    override fun markUploaded(fileName: String) {
        uploaded += fileName
    }

    override fun markFailed(fileName: String) {
        failed += fileName
    }
}

/**
 * テストから進められる時計。
 *
 * coroutine の仮想時間は `delay` にしか効かないので、ファイル名と最終送信時刻の
 * ためには別途こちらを進める。
 */
class MutableClock(
    private var current: Instant,
    private val zone: ZoneId,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    fun set(instant: Instant) {
        current = instant
    }
}
