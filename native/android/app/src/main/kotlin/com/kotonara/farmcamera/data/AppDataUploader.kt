package com.kotonara.farmcamera.data

import com.kotonara.farmcamera.domain.PhotoUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Google Drive の `appDataFolder` へ JPEG を multipart 送信する実装。 */
class AppDataUploader(
    private val client: OkHttpClient,
    private val accessToken: suspend () -> Result<String>,
    uploadUrl: HttpUrl = DRIVE_UPLOAD_URL.toHttpUrl(),
    private val listUrl: HttpUrl = DRIVE_LIST_URL.toHttpUrl(),
    private val requestFactory: DriveUploadRequestFactory = DriveUploadRequestFactory(uploadUrl),
    private val logger: DriveUploadLogger = NoOpDriveUploadLogger,
) : PhotoUploader {
    override suspend fun upload(
        fileName: String,
        jpeg: ByteArray,
    ): Result<String> =
        runCatching {
            val token = accessToken().getOrThrow()
            findExistingFileId(token, fileName)?.let { fileId ->
                logger.log("Drive upload skipped: file=$fileName id=$fileId already exists", null)
                return@runCatching fileId
            }
            val request = requestFactory.create(token, fileName, jpeg)

            // OkHttp の execute() は同期 I/O なので、呼び出し元のディスパッチャ（Main のことがある）
            // をブロックしないよう IO へ逃がす。忘れると実機で NetworkOnMainThreadException が
            // メッセージ null のまま飛び、原因が追いにくい（JVM 単体テストでは再現しない）。
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    logger.log("Drive API response: HTTP ${response.code} for $fileName", null)
                    response.requireSuccessfulFileId()
                }
            }
        }.onSuccess { fileId ->
            logger.log("Drive upload succeeded: file=$fileName id=$fileId", null)
        }.onFailure { failure ->
            logger.log("Drive upload failed: file=$fileName", failure)
        }

    private fun Response.requireSuccessfulFileId(): String {
        val body = body?.string().orEmpty()
        check(isSuccessful) { "Drive upload failed: HTTP $code" }
        return FILE_ID_PATTERN.find(body)?.groupValues?.get(1)
            ?: error("Drive upload response does not contain a file id")
    }

    private suspend fun findExistingFileId(
        accessToken: String,
        fileName: String,
    ): String? =
        withContext(Dispatchers.IO) {
            val urlBuilder = listUrl.newBuilder()
            urlBuilder.addQueryParameter("spaces", "appDataFolder")
            urlBuilder.addQueryParameter("q", "name = '${fileName.escapeDriveQuery()}'")
            urlBuilder.addQueryParameter("fields", "files(id,name)")
            urlBuilder.addQueryParameter("pageSize", "1")
            val request =
                Request
                    .Builder()
                    .url(urlBuilder.build())
                    .header("Authorization", "Bearer $accessToken")
                    .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                check(response.isSuccessful) { "Drive file lookup failed: HTTP ${response.code}" }
                FILE_ID_PATTERN.find(body)?.groupValues?.get(1)
            }
        }

    private fun String.escapeDriveQuery(): String = replace("\\", "\\\\").replace("'", "\\'")

    private companion object {
        const val DRIVE_UPLOAD_URL =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
        const val DRIVE_LIST_URL = "https://www.googleapis.com/drive/v3/files"
        val FILE_ID_PATTERN = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    }
}
