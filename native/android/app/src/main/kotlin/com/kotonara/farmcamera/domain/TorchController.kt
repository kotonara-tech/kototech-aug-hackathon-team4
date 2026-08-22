package com.kotonara.farmcamera.domain

/**
 * カメラのトーチ（常時点灯ライト）を制御する抽象（docs/03-native.md 7 節 / issue #10）。
 *
 * CameraX 実装は `data` 層に置く。トーチが実際に点灯するかはハードウェアに依存するため
 * ここでは意図（ON/OFF）だけを表現する。実機での実挙動の検証は `androidTest` に任せる
 * （docs/03-native.md 9 節の例外）。
 */
interface TorchController {
    /**
     * トーチの ON/OFF を切り替える。
     *
     * この端末にトーチが無い場合や、切り替えに失敗した場合は [Result.failure] を返す。
     * 呼び出し側はエラーを画面に表示すること（黙って握り潰さない）。
     */
    suspend fun setEnabled(enabled: Boolean): Result<Unit>
}
