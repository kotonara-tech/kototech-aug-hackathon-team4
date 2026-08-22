package com.kotonara.farmcamera.presentation

import com.kotonara.farmcamera.domain.CaptureState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service が更新し、画面が購読する直近の撮影状態。 */
object CaptureStatusRepository {
    private val mutableState = MutableStateFlow(CaptureState())

    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    fun update(state: CaptureState) {
        mutableState.value = state
    }
}
