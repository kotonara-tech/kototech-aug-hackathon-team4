package com.kotonara.farmcamera.presentation

/** 定点撮影を開始できる Google 認可の状態。 */
sealed interface SignInState {
    data object NotSignedIn : SignInState

    data object SigningIn : SignInState

    data object SignedIn : SignInState

    data class Error(
        val message: String,
    ) : SignInState
}
