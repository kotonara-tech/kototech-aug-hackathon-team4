package com.kotonara.farmcamera.domain

/**
 * 撮影した JPEG を端末へ残す抽象。
 *
 * **端末に残すかどうか、どこに残すか、いつ消すかは Q16（#42）が未決です。**
 * 結論を先取りしないために、`CaptureCoordinator` からは「保存する」1 点だけを
 * 呼び、実際の振る舞いはここの実装差し替えで決める。A/B/C のどれに決まっても、
 * 変更は `CaptureCoordinator` を組み立てる箇所の 1 行で済む。
 *
 * 長期保管は Web アプリ側の責務なので、端末側に履歴や上限の概念は持たせない。
 */
interface LocalPhotoStore {
    suspend fun save(fileName: String, jpeg: ByteArray): Result<Unit>
}

/**
 * 端末には何も残さない既定の実装。
 *
 * Q16 が決まるまではこれを使う。「とりあえず保存する」を既定にすると、
 * あとで消す判断が難しくなるうえ、決まっていない仕様が実装として既成事実になる。
 */
object NoOpLocalPhotoStore : LocalPhotoStore {
    override suspend fun save(fileName: String, jpeg: ByteArray): Result<Unit> = Result.success(Unit)
}
