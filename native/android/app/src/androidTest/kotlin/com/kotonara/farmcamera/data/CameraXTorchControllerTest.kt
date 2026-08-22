package com.kotonara.farmcamera.data

import android.Manifest
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.kotonara.farmcamera.MainActivity
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 実機のカメラハードウェアに接続した状態でのみ検証できるため androidTest に置く
 * （docs/03-native.md 9 節の例外。CameraX のトーチはエミュレータ/Robolectric では
 * 実際の点灯を確認できない）。
 */
@RunWith(AndroidJUnit4::class)
class CameraXTorchControllerTest {
    @get:Rule
    val cameraPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun `トーチをONにしてからOFFにできる`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val onResult = AtomicReference<Result<Unit>>()
            val offResult = AtomicReference<Result<Unit>>()
            val latch = CountDownLatch(1)

            scenario.onActivity { activity ->
                activity.lifecycleScope.launch {
                    val controller = CameraXTorchController(activity, activity)
                    onResult.set(controller.setEnabled(true))
                    offResult.set(controller.setEnabled(false))
                    latch.countDown()
                }
            }

            assertTrue("タイムアウトした", latch.await(10, TimeUnit.SECONDS))
            assertTrue("トーチONに失敗: ${onResult.get().exceptionOrNull()}", onResult.get().isSuccess)
            assertTrue("トーチOFFに失敗: ${offResult.get().exceptionOrNull()}", offResult.get().isSuccess)
        }
    }
}
