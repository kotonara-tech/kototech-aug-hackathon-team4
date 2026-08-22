package com.kotonara.farmcamera.data

import android.util.Log

fun interface DriveUploadLogger {
    fun log(
        message: String,
        failure: Throwable?,
    )
}

object NoOpDriveUploadLogger : DriveUploadLogger {
    override fun log(
        message: String,
        failure: Throwable?,
    ) = Unit
}

object AndroidDriveUploadLogger : DriveUploadLogger {
    override fun log(
        message: String,
        failure: Throwable?,
    ) {
        if (failure == null) Log.i(TAG, message) else Log.w(TAG, message, failure)
    }

    private const val TAG = "FarmCameraDrive"
}
