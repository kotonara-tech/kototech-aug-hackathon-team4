package com.kotonara.farmcamera.data

import android.content.Context
import com.kotonara.farmcamera.domain.PhotoUploadStatus
import com.kotonara.farmcamera.domain.PhotoUploadStatusStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 画像名をキーにした、Drive 送信状況の永続台帳。 */
object CapturePhotoUploadStates : PhotoUploadStatusStore {
    private val mutableStates = MutableStateFlow<Map<String, PhotoUploadStatus>>(emptyMap())

    val states: StateFlow<Map<String, PhotoUploadStatus>> = mutableStates.asStateFlow()

    fun load(context: Context) {
        appContext = context.applicationContext
        CapturePhotoGallery.load(context)
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val existingNames = CapturePhotoGallery.photos.value.mapTo(mutableSetOf()) { it.name }
        val loaded =
            preferences.all
                .asSequence()
                .filter { (key, _) -> key.startsWith(KEY_PREFIX) }
                .mapNotNull { (key, value) ->
                    val fileName = key.removePrefix(KEY_PREFIX)
                    val status = (value as? String)?.let(::parseStatus)
                    if (fileName in existingNames && status != null) fileName to status else null
                }.toMap()
        mutableStates.value = loaded
        preferences.edit().also { editor ->
            preferences.all.keys
                .filter { it.startsWith(KEY_PREFIX) && it.removePrefix(KEY_PREFIX) !in existingNames }
                .forEach(editor::remove)
            editor.apply()
        }
    }

    override fun markPending(fileName: String) {
        mark(fileName, PhotoUploadStatus.PENDING)
    }

    override fun markUploaded(fileName: String) {
        mark(fileName, PhotoUploadStatus.UPLOADED)
    }

    override fun markFailed(fileName: String) {
        mark(fileName, PhotoUploadStatus.FAILED)
    }

    private fun mark(
        fileName: String,
        status: PhotoUploadStatus,
    ) {
        val context = appContext ?: return
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("$KEY_PREFIX$fileName", status.name)
            .apply()
        mutableStates.value = mutableStates.value + (fileName to status)
    }

    private fun parseStatus(value: String): PhotoUploadStatus? =
        runCatching { PhotoUploadStatus.valueOf(value) }
            .getOrNull()

    private var appContext: Context? = null

    private const val PREFERENCES_NAME = "capture_photo_upload_states"
    private const val KEY_PREFIX = "photo:"
}
