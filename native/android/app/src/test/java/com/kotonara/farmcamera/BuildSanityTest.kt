package com.kotonara.farmcamera

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildSanityTest {
    @Test
    fun `Kotlinのユニットテスト環境が実行できる`() {
        assertEquals("com.kotonara.farmcamera", BuildConfig.APPLICATION_ID)
    }
}
