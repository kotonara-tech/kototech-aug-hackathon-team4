package com.kotonara.farmcamera.presentation

import com.kotonara.farmcamera.domain.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CaptureStatusRepository] は Service と画面をまたぐ唯一の橋渡しなので、
 * `update` が `state` にそのまま反映されることをテストで固定する。
 *
 * シングルトンなので初期値には依存しない（他のテストの実行順に影響されるため）。
 */
class CaptureStatusRepositoryTest {
    @Test
    fun `updateした状態がstateにそのまま反映される`() {
        val updated = CaptureState(capturedCount = 7, uploadedCount = 5, lastError = "テスト用エラー")

        CaptureStatusRepository.update(updated)

        assertEquals(updated, CaptureStatusRepository.state.value)
    }
}
