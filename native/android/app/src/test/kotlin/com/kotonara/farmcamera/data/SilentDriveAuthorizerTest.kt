package com.kotonara.farmcamera.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentDriveAuthorizerTest {
    @Test
    fun `認可済みトークンを返す`() =
        runTest {
            val authorizer = SilentDriveAuthorizer.forTest { Result.success(SilentAuthorizationResult("token", false)) }

            assertEquals("token", authorizer.accessToken().getOrThrow())
        }

    @Test
    fun `追加認可が必要な場合は失敗にする`() =
        runTest {
            val authorizer = SilentDriveAuthorizer.forTest { Result.success(SilentAuthorizationResult("token", true)) }

            val message =
                authorizer
                    .accessToken()
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
            assertTrue(message.contains("追加認可"))
        }

    @Test
    fun `トークンが無い場合とPlay Services失敗を返す`() =
        runTest {
            val missingToken = SilentDriveAuthorizer.forTest { Result.success(SilentAuthorizationResult(null, false)) }
            val serviceFailure = SilentDriveAuthorizer.forTest { Result.failure(IllegalStateException("Play Services")) }

            assertTrue(missingToken.accessToken().isFailure)
            assertEquals("Play Services", serviceFailure.accessToken().exceptionOrNull()?.message)
        }
}
