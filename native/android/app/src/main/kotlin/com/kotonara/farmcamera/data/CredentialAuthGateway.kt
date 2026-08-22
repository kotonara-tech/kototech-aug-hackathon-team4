package com.kotonara.farmcamera.data

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.kotonara.farmcamera.BuildConfig
import com.kotonara.farmcamera.domain.AuthGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await

/**
 * サインインは Credential Manager、`drive.appdata` の認可は `AuthorizationClient` で行う
 * 実装（docs/03-native.md 3 節）。**旧 `GoogleSignInClient` は使わない。**
 *
 * `AuthorizationClient.authorize()` は初回同意時に `hasResolution()` が true を返し、
 * ユーザー操作（同意画面）が要る。その画面を [activity] の `ActivityResultLauncher` 越しに
 * 出すため、Activity への参照をコンストラクタで受け取る。
 */
class CredentialAuthGateway(private val activity: ComponentActivity) : AuthGateway {

    private val credentialManager = CredentialManager.create(activity)
    private var pendingResolution: CompletableDeferred<Unit>? = null

    private val resolutionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val deferred = pendingResolution
        pendingResolution = null
        if (result.resultCode == Activity.RESULT_OK) {
            deferred?.complete(Unit)
        } else {
            deferred?.completeExceptionally(IllegalStateException("drive.appdata の認可がキャンセルされました"))
        }
    }

    override suspend fun signIn(): Result<Unit> = runCatching {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        credentialManager.getCredential(activity, request)
        Unit
    }

    override suspend fun accessToken(): Result<String> = runCatching {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()

        var result = Identity.getAuthorizationClient(activity).authorize(request).await()
        if (result.hasResolution()) {
            awaitConsent(requireNotNull(result.pendingIntent) { "認可の同意画面が要求されたが pendingIntent が無い" })
            result = Identity.getAuthorizationClient(activity).authorize(request).await()
        }

        requireNotNull(result.accessToken) { "drive.appdata のアクセストークンを取得できませんでした" }
    }

    private suspend fun awaitConsent(pendingIntent: PendingIntent) {
        val deferred = CompletableDeferred<Unit>()
        pendingResolution = deferred
        resolutionLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        deferred.await()
    }

    private companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
