package com.kotonara.farmcamera.presentation

import com.kotonara.farmcamera.domain.CaptureState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CaptureViewModelTest {
    @Test
    fun `Serviceの状態をそのまま画面に公開する`() {
        val state = MutableStateFlow(CaptureState(capturedCount = 3, uploadedCount = 2))
        val viewModel = CaptureViewModel(state, {}, {})

        assertSame(state, viewModel.state)
        assertEquals(3, viewModel.state.value.capturedCount)
        assertEquals(2, viewModel.state.value.uploadedCount)
    }

    @Test
    fun `開始と停止の要求をActivityへ委譲する`() {
        var starts = 0
        var stops = 0
        val viewModel = CaptureViewModel(MutableStateFlow(CaptureState()), { starts++ }, { stops++ })

        viewModel.start()
        viewModel.stop()

        assertEquals(1, starts)
        assertEquals(1, stops)
    }
}
