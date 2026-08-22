package com.kotonara.farmcamera.data

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Drive AppData への multipart リクエストだけを組み立てる。 */
class DriveUploadRequestFactory(private val uploadUrl: HttpUrl) {

    fun create(accessToken: String, fileName: String, jpeg: ByteArray): Request = Request.Builder()
        .url(uploadUrl)
        .header("Authorization", "Bearer $accessToken")
        .post(buildMultipartBody(fileName, jpeg))
        .build()

    private fun buildMultipartBody(fileName: String, jpeg: ByteArray): MultipartBody {
        val metadata = """{"name":"${fileName.escapeJson()}","parents":["appDataFolder"],"mimeType":"image/jpeg"}"""
            .toRequestBody(JSON_MEDIA_TYPE)
        val image = jpeg.toRequestBody(JPEG_MEDIA_TYPE)

        return MultipartBody.Builder()
            .setType(MULTIPART_RELATED_MEDIA_TYPE)
            .addPart(metadata)
            .addPart(image)
            .build()
    }

    private fun String.escapeJson(): String = replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
        val MULTIPART_RELATED_MEDIA_TYPE = "multipart/related".toMediaType()
    }
}
