package com.kotonara.farmcamera.domain

/**
 * Google サインインと `drive.appdata` の認可を抽象化する。
 *
 * 実装は `data` 層に閉じ込め、呼び出し側は Credential Manager / AuthorizationClient の
 * 詳細を知らない（docs/03-native.md 7 節）。
 */
interface AuthGateway {
    /** Credential Manager によるサインイン。認可（スコープの取得）は含まない。 */
    suspend fun signIn(): Result<Unit>

    /**
     * `drive.appdata` スコープの有効なアクセストークンを返す。
     *
     * トークンは失効する。呼ぶたびに有効なものを返す責務を持つ
     * （Play Services 側がキャッシュと更新を面倒見る）。
     */
    suspend fun accessToken(): Result<String>
}
