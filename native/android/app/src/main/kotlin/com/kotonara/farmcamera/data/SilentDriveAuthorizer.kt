package com.kotonara.farmcamera.data

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * [CaptureService][com.kotonara.farmcamera.presentation.CaptureService] からトークンを
 * 取り直すための認可（issue #52）。
 *
 * Foreground Service は UI を持たないため、初回同意の解決画面は出せない。
 * [CredentialAuthGateway] で一度サインイン・認可を済ませたあとの**トークン更新のみ**を
 * ここで担う。Play Services 側がキャッシュと更新を面倒見るため、通常は UI 不要で
 * 有効なトークンが返る（docs/03-native.md 7 節）。
 *
 * 同意の取り直しが必要な状態（`hasResolution() == true`、例: ユーザーがアクセスを取り消した）
 * になったらサービス内では解決できないため失敗として返す。`CaptureCoordinator` の
 * 直近エラーに載り、利用者にアプリを開いて再サインインを促す形になる。
 */
class SilentDriveAuthorizer(private val context: Context) {

    suspend fun accessToken(): Result<String> = runCatching {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()

        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        // 同意の解決画面が必要になった場合、Service には出す手段が無い。
        // CredentialAuthGateway 側（Activity）での再サインインに委ねる。
        check(!result.hasResolution()) { "drive.appdata の再同意が必要です。アプリを開いてサインインし直してください" }

        requireNotNull(result.accessToken) { "drive.appdata のアクセストークンを取得できませんでした" }
    }

    private companion object {
        // CredentialAuthGateway.DRIVE_APPDATA_SCOPE と同じ値。両者は別クラスの private
        // companion なので共有できないが、値は docs/02-google-drive.md 3 節で固定されている。
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
