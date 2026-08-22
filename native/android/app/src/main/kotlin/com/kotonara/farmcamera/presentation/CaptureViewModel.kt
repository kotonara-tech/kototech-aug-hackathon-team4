package com.kotonara.farmcamera.presentation

import androidx.lifecycle.ViewModel
import com.kotonara.farmcamera.domain.CaptureState
import kotlinx.coroutines.flow.StateFlow

/** 画面は状態の購読と開始・停止要求だけを担当し、撮影ロジックを持たない。 */
class CaptureViewModel(
    val state: StateFlow<CaptureState>,
    private val startCapture: () -> Unit,
    private val stopCapture: () -> Unit,
) : ViewModel() {
    fun start() = startCapture()

    fun stop() = stopCapture()
}
