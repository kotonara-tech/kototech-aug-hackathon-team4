package com.kotonara.farmcamera.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kotonara.farmcamera.domain.PhotoUploadStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Android のアプリ内ストレージと SharedPreferences を使う計測テスト。
 *
 * テストで扱うファイル名には専用接頭辞を付け、終了時にはそのファイルと状態だけを除去する。
 */
@RunWith(AndroidJUnit4::class)
class PhotoPersistenceIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fileNames = mutableListOf<String>()

    @After
    fun cleanUp() {
        val directory = File(context.filesDir, "captures")
        fileNames.forEach { fileName ->
            File(directory, fileName).delete()
            context
                .getSharedPreferences("capture_photo_upload_states", 0)
                .edit()
                .remove("photo:$fileName")
                .commit()
        }
        CapturePhotoGallery.load(context)
        CapturePhotoUploadStates.load(context)
    }

    @Test
    fun appPhotoStoreSavesReadsAndPublishesPhotoToGallery() =
        runBlocking {
            val fileName = testFileName()
            val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0x02)
            val store = AppPhotoStore(context)

            store.save(fileName, jpeg).getOrThrow()

            val saved = store.savedPhotos().single { it.name == fileName }
            assertArrayEquals(jpeg, store.readPhoto(saved))
            assertTrue(CapturePhotoGallery.photos.value.any { it.name == fileName })
        }

    @Test
    fun uploadStatusPersistsAcrossReloadWhenTheSavedPhotoStillExists() {
        val fileName = testFileName()
        val directory = File(context.filesDir, "captures").apply { mkdirs() }
        File(directory, fileName).writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        CapturePhotoUploadStates.load(context)

        CapturePhotoUploadStates.markPending(fileName)
        CapturePhotoUploadStates.markUploaded(fileName)
        CapturePhotoUploadStates.load(context)

        assertEquals(PhotoUploadStatus.UPLOADED, CapturePhotoUploadStates.states.value[fileName])
    }

    private fun testFileName(): String =
        "instrumentation_${UUID.randomUUID()}.jpg"
            .also(fileNames::add)
}
