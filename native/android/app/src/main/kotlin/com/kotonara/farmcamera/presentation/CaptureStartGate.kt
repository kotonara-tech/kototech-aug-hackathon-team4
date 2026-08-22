package com.kotonara.farmcamera.presentation

/** Activity が Service 起動前に満たすべき権限条件。 */
internal object CaptureStartGate {
    fun requiresNotificationPermission(sdkInt: Int): Boolean = sdkInt >= 33

    fun isReady(
        hasCameraPermission: Boolean,
        hasNotificationPermission: Boolean,
        sdkInt: Int,
    ): Boolean = hasCameraPermission && (!requiresNotificationPermission(sdkInt) || hasNotificationPermission)
}
