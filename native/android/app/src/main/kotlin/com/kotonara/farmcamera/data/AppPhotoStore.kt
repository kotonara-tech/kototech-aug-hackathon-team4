package com.kotonara.farmcamera.data

import android.content.Context
import com.kotonara.farmcamera.domain.LocalPhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** アプリ専用領域の撮影 JPEG 一覧。直近100枚だけを画面へ公開する。 */
object CapturePhotoGallery {
    private val mutablePhotos = MutableStateFlow<List<File>>(emptyList())

    val photos: StateFlow<List<File>> = mutablePhotos.asStateFlow()

    fun load(context: Context) {
        mutablePhotos.value = listCaptureFiles(context)
    }

    internal fun replace(photos: List<File>) {
        mutablePhotos.value = photos
    }

    private fun listCaptureFiles(context: Context): List<File> =
        File(context.filesDir, CAPTURE_DIRECTORY)
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            .sortedByDescending(File::lastModified)
            .take(MAX_PHOTOS)
}

/** 撮影結果をアプリ専用領域へ保存し、上限を超えた古い JPEG を削除する。 */
class AppPhotoStore(
    private val context: Context,
) : LocalPhotoStore {
    suspend fun savedPhotos(): List<File> =
        withContext(Dispatchers.IO) {
            File(context.filesDir, CAPTURE_DIRECTORY)
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                .sortedByDescending(File::lastModified)
                .take(MAX_PHOTOS)
        }

    suspend fun readPhoto(photo: File): ByteArray = withContext(Dispatchers.IO) { photo.readBytes() }

    override suspend fun save(
        fileName: String,
        jpeg: ByteArray,
    ): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) {
                val directory = File(context.filesDir, CAPTURE_DIRECTORY).apply { mkdirs() }
                File(directory, fileName).writeBytes(jpeg)
                val photos =
                    directory
                        .listFiles()
                        .orEmpty()
                        .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                        .sortedByDescending(File::lastModified)
                photos.drop(MAX_PHOTOS).forEach(File::delete)
                CapturePhotoGallery.replace(photos.take(MAX_PHOTOS))
            }
        }
}

private const val CAPTURE_DIRECTORY = "captures"
private const val MAX_PHOTOS = 100
