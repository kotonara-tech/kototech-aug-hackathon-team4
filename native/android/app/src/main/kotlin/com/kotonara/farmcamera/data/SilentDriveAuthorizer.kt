package com.kotonara.farmcamera.data

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/** Play Services が返す、UIを出さない認可結果の最小表現。 */
data class SilentAuthorizationResult(
    val accessToken: String?,
    val requiresUserConsent: Boolean,
)

/**
 * Foreground ServiceからDriveトークンを取得する。
 *
 * 認可結果の解釈を注入可能にし、Play Servicesなしのユニットテストでも失敗条件を固定する。
 */
class SilentDriveAuthorizer private constructor(
    private val authorize: suspend () -> Result<SilentAuthorizationResult>,
) {
    constructor(context: Context) : this({ context.authorizeSilently() })

    suspend fun accessToken(): Result<String> =
        authorize().mapCatching { result ->
            check(!result.requiresUserConsent) {
                "drive.appdata の追加認可が必要です。アプリを開いてサインインしてください"
            }
            requireNotNull(result.accessToken) { "drive.appdata のアクセストークンを取得できませんでした" }
        }

    companion object {
        internal fun forTest(authorize: suspend () -> Result<SilentAuthorizationResult>): SilentDriveAuthorizer =
            SilentDriveAuthorizer(authorize)
    }
}

private suspend fun Context.authorizeSilently(): Result<SilentAuthorizationResult> =
    runCatching {
        val request =
            AuthorizationRequest
                .builder()
                .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
                .build()
        val result = Identity.getAuthorizationClient(this).authorize(request).await()
        SilentAuthorizationResult(
            accessToken = result.accessToken,
            requiresUserConsent = result.hasResolution(),
        )
    }

private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
