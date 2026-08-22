package com.kotonara.farmcamera.data

import android.content.Context
import com.kotonara.farmcamera.domain.TorchSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** ライト設定と、最後に端末へ反映できた状態を永続化する。 */
object TorchSettingsRepository {
    private val mutableSettings = MutableStateFlow(TorchSettings())
    val settings: StateFlow<TorchSettings> = mutableSettings.asStateFlow()

    fun load(context: Context) {
        appContext = context.applicationContext
        val preferences = appContext!!.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        mutableSettings.value =
            TorchSettings(
                manualEnabled = preferences.getBoolean(KEY_MANUAL_ENABLED, false),
                automaticEnabled = preferences.getBoolean(KEY_AUTOMATIC_ENABLED, false),
                startTime = preferences.getString(KEY_START_TIME, DEFAULT_START)?.toLocalTime() ?: DEFAULT_START_TIME,
                endTime = preferences.getString(KEY_END_TIME, DEFAULT_END)?.toLocalTime() ?: DEFAULT_END_TIME,
                isTorchOn = false,
            )
    }

    fun update(settings: TorchSettings) {
        val context = appContext ?: return
        mutableSettings.value = settings
        context
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MANUAL_ENABLED, settings.manualEnabled)
            .putBoolean(KEY_AUTOMATIC_ENABLED, settings.automaticEnabled)
            .putString(KEY_START_TIME, settings.startTime.format(FORMATTER))
            .putString(KEY_END_TIME, settings.endTime.format(FORMATTER))
            .apply()
    }

    fun markApplied(isTorchOn: Boolean) {
        mutableSettings.value = mutableSettings.value.copy(isTorchOn = isTorchOn, lastError = null)
    }

    fun markFailed(message: String) {
        mutableSettings.value = mutableSettings.value.copy(lastError = message)
    }

    private fun String.toLocalTime(): LocalTime? =
        runCatching { LocalTime.parse(this, FORMATTER) }
            .getOrNull()

    private var appContext: Context? = null

    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private const val PREFERENCES_NAME = "torch_settings"
    private const val KEY_MANUAL_ENABLED = "manual_enabled"
    private const val KEY_AUTOMATIC_ENABLED = "automatic_enabled"
    private const val KEY_START_TIME = "start_time"
    private const val KEY_END_TIME = "end_time"
    private const val DEFAULT_START = "18:00"
    private const val DEFAULT_END = "06:00"
    private val DEFAULT_START_TIME: LocalTime = LocalTime.of(18, 0)
    private val DEFAULT_END_TIME: LocalTime = LocalTime.of(6, 0)
}
