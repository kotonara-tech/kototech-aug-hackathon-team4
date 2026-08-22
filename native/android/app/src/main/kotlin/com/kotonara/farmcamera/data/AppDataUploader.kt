package com.kotonara.farmcamera.data

import com.kotonara.farmcamera.domain.PhotoUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response

/** Google Drive の `appDataFolder` へ JPEG を multipart 送信する実装。 */
class AppDataUploader(
    private val client: OkHttpClient,
    private val accessToken: suspend () -> Result<String>,
    uploadUrl: HttpUrl = DRIVE_UPLOAD_URL.toHttpUrl(),
    private val requestFactory: DriveUploadRequestFactory = DriveUploadRequestFactory(uploadUrl)
) : PhotoUploader {

    override suspend fun upload(fileName: String, jpeg: ByteArray): Result<String> = runCatching {
        val token = accessToken().getOrThrow()
        val request = requestFactory.create(token, fileName, jpeg)

        // OkHttp の execute() は同期 I/O なので、呼び出し元のディスパッチャ（Main のことがある）
        // をブロックしないよう IO へ逃がす。忘れると実機で NetworkOnMainThreadException が
        // メッセージ null のまま飛び、原因が追いにくい（JVM 単体テストでは再現しない）。
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                response.requireSuccessfulFileId()
            }
        }
    }

    private fun Response.requireSuccessfulFileId(): String {
        val body = body?.string().orEmpty()
        check(isSuccessful) { "Drive upload failed: HTTP $code" }
        return FILE_ID_PATTERN.find(body)?.groupValues?.get(1)
            ?: error("Drive upload response does not contain a file id")
    }

    private companion object {
        const val DRIVE_UPLOAD_URL =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        val FILE_ID_PATTERN = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    }
}
