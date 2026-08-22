package com.kotonara.farmcamera.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CapturePhotoFilesTest {
    @Test
    fun `JPEGだけを新しい順に返す`() {
        val directory = Files.createTempDirectory("capture-files").toFile()
        try {
            write(directory, "old.jpg", 1)
            write(directory, "new.JPG", 3)
            write(directory, "ignore.txt", 5)

            assertEquals(listOf("new.JPG", "old.jpg"), CapturePhotoFiles.newest(directory, 100).map(File::getName))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `上限を超えた古いJPEGを削除する`() {
        val directory = Files.createTempDirectory("capture-prune").toFile()
        try {
            val old = write(directory, "old.jpg", 1)
            val middle = write(directory, "middle.jpg", 2)
            val newest = write(directory, "newest.jpg", 3)

            val retained = CapturePhotoFiles.pruneToNewest(directory, 2)

            assertEquals(listOf(newest, middle), retained)
            assertFalse(old.exists())
            assertTrue(middle.exists())
            assertTrue(newest.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun write(
        directory: File,
        name: String,
        modifiedAt: Long,
    ): File =
        File(directory, name).apply {
            writeText(name)
            setLastModified(modifiedAt)
        }
}
