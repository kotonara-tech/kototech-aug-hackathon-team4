# 03. Native（Android / Kotlin）設計

Android 端末の設計正本。
Drive とのやりとりの契約は [02-google-drive.md](02-google-drive.md) が正です。**必ず先に読んでください。**

---

## 1. 責務

**「撮って、送って、忘れる」だけ。**

| やること | やらないこと |
|---|---|
| N 分ごとに JPEG を撮影する | Drive のファイル一覧を取得する |
| 撮影直後に AppData 領域へアップロードする | 撮影済み画像のギャラリー表示 |
| 撮影枚数・送信枚数・最終送信時刻・直近エラーを画面に出す | Web 側との状態同期 |
| 送信失敗時にファイルを端末に残す | 自動再送キュー |
| | 撮影間隔の設定 UI |

---

## 2. なぜ Flutter をやめて Kotlin にするのか

**`WorkManager` の `PeriodicWorkRequest` は最小間隔が 15 分**で、「数分ごと」という
要件を原理的に満たせません。

数分間隔の定点撮影を Android で成立させるには、**常駐通知付きの Foreground Service**が
必要です。Flutter でこれをやると、結局 Kotlin 側にプラグインを書くことになります。
**最初から Kotlin にします。**

旧 Flutter 実装（`legacy/native-app/`）から移植できるのは、ファイル名規約と
撮影セッションの状態遷移の考え方だけです。移植する具体的な中身は
[05 §3](05-implementation-plan.md) の M1 に列挙してあります。

---

## 3. 技術スタック

| 領域 | 採用 |
|---|---|
| 言語 | Kotlin |
| UI | Jetpack Compose（ステータス表示のみの 1 画面） |
| カメラ | CameraX（`camera-core`, `camera-camera2`, `camera-lifecycle`）の `ImageCapture` |
| 常駐 | Foreground Service（`foregroundServiceType="camera"`）+ 常駐通知 |
| 認証 | `androidx.credentials`（Credential Manager）+ `play-services-auth` の `AuthorizationClient` |
| HTTP | OkHttp |
| 非同期 | Kotlin Coroutines + `StateFlow` |
| テスト | JUnit + MockWebServer + `kotlinx-coroutines-test` |

### 認証について

**旧 `GoogleSignIn` API（`GoogleSignInClient`）は非推奨です。使わないでください。**

- サインイン（誰か）: Credential Manager (`androidx.credentials`) の
  `GetCredentialRequest` + Google ID オプション
- 認可（Drive スコープのアクセストークン取得）: `play-services-auth` の
  `Identity.getAuthorizationClient()` に `drive.appdata` スコープを要求

サインインと認可は**別ステップ**です。ID トークンを取っただけでは Drive を叩けません。

### applicationId

```
com.kotonara.farmcamera
```

**据え置きます。** 変更すると GCP 側の OAuth クライアント登録もやり直しになります。

---

## 4. パッケージ構成

[01-overview.md 6.2 / 6.4](01-overview.md#6-開発規則native--web-共通) を Kotlin に
具体化したものです。**依存は一方向**、**トップはエントリーポイントのみ**、
**実装は `src/` 配下**。

```
native/app/
├── build.gradle.kts                    ← ビルド設定のみ
├── settings.gradle.kts
└── src/
    ├── main/kotlin/com/kotonara/farmcamera/
    │   ├── MainActivity.kt             ★ エントリーポイント。組み立てて描画するだけ
    │   ├── FarmCameraApp.kt            ★ Application。依存の生成と注入のみ
    │   │
    │   ├── domain/                     何にも依存しない。純粋な Kotlin だけ
    │   │   ├── CaptureState.kt         UI に出す状態のデータクラス
    │   │   ├── PhotoNaming.kt          CAM001_yyyyMMdd_HHmmss.jpg を組み立てる純粋関数
    │   │   ├── CaptureScheduler.kt     間隔タイマーの interface
    │   │   ├── PhotoSource.kt          カメラの interface
    │   │   ├── AuthGateway.kt          認証の interface
    │   │   ├── PhotoUploader.kt        送信先の interface
    │   │   └── CaptureCoordinator.kt   撮影 → 保存 → 送信 の順序と二重起動防止
    │   │
    │   ├── data/                       domain の interface を実装する。外部に触る唯一の場所
    │   │   ├── CameraXPhotoSource.kt    CameraX 実装
    │   │   ├── CredentialAuthGateway.kt Credential Manager + AuthorizationClient 実装
    │   │   ├── AppDataUploader.kt       Drive API v3 実装（OkHttp）
    │   │   └── CoroutineCaptureScheduler.kt
    │   │
    │   └── presentation/               画面と、その状態
    │       ├── CaptureViewModel.kt     StateFlow<CaptureState> を公開する
    │       ├── CaptureScreen.kt        Compose の画面
    │       └── CaptureService.kt       Foreground Service。Coordinator を回すだけ
    │
    └── test/kotlin/com/kotonara/farmcamera/
        ├── domain/                     ← テストの主戦場。ここを厚く書く
        └── data/
```

### レイヤの規則

| レイヤ | 依存してよい先 | Android SDK を触ってよいか |
|---|---|---|
| `domain` | **なし** | **不可。**`android.*` を import した時点で違反 |
| `data` | `domain` のみ | 可（CameraX、Credential Manager、OkHttp） |
| `presentation` | `domain` のみ | 可（Compose、Service） |

- **`data` と `presentation` は互いを import しない**
- `MainActivity` / `FarmCameraApp` は**依存を組み立てて渡すだけ**。ロジックを置かない

> `domain` が `android.*` に依存しないことが、`./gradlew test` を実機なしで
> 高速に回せる理由です。ここを崩すと Robolectric や実機テストが必要になり、
> テストが遅く・不安定になります。

### 設計方針: ハードウェアと認証を抽象の裏に隠す

`PhotoSource` / `AuthGateway` / `PhotoUploader` / `CaptureScheduler` は
**`domain` に interface として置き**、実装（CameraX / Credential Manager / OkHttp）は
`data` に置いて注入します。

**理由**: これらを直接呼ぶコードはユニットテストできません。抽象を挟むことで、
撮影ロジックとスケジューリングとアップロードのリクエスト組み立てを、
**実機なしでテストできます**（→ 9 節）。

`CaptureScreen` は状態を購読して描画するだけの薄い層に保ってください。
旧 Flutter 実装では `main.dart` の単一 Widget にロジックが集中し、
テストが 1 件しか書けない状態になっていました。同じ轍を踏まないこと。

**過剰にやらないこと。** UseCase クラスの量産、レイヤごとの DTO 詰め替え、
Repository の上の Interactor——やりません。上の構成が上限です。

---

## 5. 権限とマニフェスト

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />

<uses-feature android:name="android.hardware.camera" android:required="true" />

<application ...>
    <service
        android:name=".presentation.CaptureService"
        android:foregroundServiceType="camera"
        android:exported="false" />
</application>
```

| 権限 | 条件 | 備考 |
|---|---|---|
| `CAMERA` | 常時 | **実行時リクエストが必要** |
| `INTERNET` | 常時 | 実行時リクエスト不要 |
| `POST_NOTIFICATIONS` | Android 13 (API 33)+ | **実行時リクエストが必要。**拒否されると常駐通知が出せず、フォアグラウンドサービスが実質使えない |
| `FOREGROUND_SERVICE` | 常時 | |
| `FOREGROUND_SERVICE_CAMERA` | Android 14 (API 34)+ | **これが無いと `startForeground()` が例外を投げます** |

> デモ端末の Android バージョンが未確定です（→ [Q11](00-openquestion.md)）。
> 14+ 前提で書いておけば下位でも動きます。

---

## 6. 撮影〜送信フロー

```
起動
 └→ 権限リクエスト（CAMERA, POST_NOTIFICATIONS）
     └→ 拒否 → 理由を画面に表示して終了。サービスは開始しない
     └→ 許可
         └→ Google サインイン（Credential Manager）
             └→ drive.appdata の認可（AuthorizationClient）
                 └→ アクセストークン取得
                     └→ [開始] ボタン有効化

[開始]
 └→ CaptureService を startForeground で起動（常駐通知を出す）
     └→ CaptureScheduler 起動（開始時に即 1 回発火）
         │
         └→ ┌─ 撮影サイクル ─────────────────────────┐
            │ 1. 撮影中フラグを立てる                   │
            │ 2. CameraX で JPEG 撮影                  │
            │ 3. 端末の一時ディレクトリへ保存            │
            │    名前: CAM001_yyyyMMdd_HHmmss.jpg      │
            │ 4. 撮影枚数++                            │
            │ 5. ★ 撮影中フラグを降ろす ← ここで降ろす  │
            │ 6. AppData へアップロード（非同期）        │
            │    成功 → 送信枚数++、最終送信時刻を更新   │
            │    失敗 → エラー表示。ファイルは残す       │
            └──────────────────────────────────────┘

[停止]
 └→ スケジューラ停止 → stopForeground → サービス終了
```

### ★ 二重起動防止フラグは「撮影完了時点」で降ろす

**送信完了時点ではありません。**

旧 Flutter 実装で、送信完了まで撮影中フラグを保持していたため、
**送信が遅いと次の撮影がスキップされる退行**を作り込みました。
圃場は電波が弱く、送信は撮影間隔を超えることがあります。

```
悪い例: [撮影]───[送信 6分]───┘  次の撮影(5分後)がスキップされる
良い例: [撮影]─┘ [送信 6分]────  撮影は5分後に予定どおり走る
                 [撮影]─┘ [送信]
```

**これはテストで固定してください**（→ 9 節）。

---

## 7. 主要コンポーネントの契約

### `PhotoNaming.buildPhotoFileName(cameraId, at): String`

純粋関数。`CAM001_yyyyMMdd_HHmmss.jpg` を返します。

- `cameraId` は `"CAM001"` 固定（定数として 1 箇所に置く）
- `at` は**撮影時点のローカル時刻（JST）**
- ゼロ埋め必須（`2026-08-22 06:03:00` → `CAM001_20260822_060300.jpg`）

### `CaptureScheduler`

`interval` ごとにコールバックを発火する抽象。

- **開始時に即 1 回発火する。**次のサイクルまで待たせない
  （デモで「開始したのに 5 分間何も起きない」状態を避けるため）
- `stop()` で確実に止まること。停止後に発火しないこと
- 実装は Coroutine の `while (isActive) { emit(); delay(interval) }` で足ります
- テスト用に仮想時間で駆動できる形にすること

### `PhotoSource`

```kotlin
interface PhotoSource {
    suspend fun capture(): Result<ByteArray>   // JPEG のバイト列
}
```

CameraX 実装の要件:

- **プレビューなしで撮影できること。**フォアグラウンドサービスは UI を持ちません
- `ImageCapture` ユースケースのみをバインドする
- **EXIF を落とさないこと。**JPEG を再エンコードしない、バイト列を加工しない
  （→ [02 §5](02-google-drive.md#5--撮影時刻の決定)）
- 解像度・品質は端末デフォルト（→ [Q9](00-openquestion.md)）

### `AuthGateway`

```kotlin
interface AuthGateway {
    suspend fun signIn(): Result<Unit>
    suspend fun accessToken(): Result<String>   // drive.appdata スコープ
}
```

- スコープは `https://www.googleapis.com/auth/drive.appdata`（→ [02 §3](02-google-drive.md)）
- トークンは失効します。`accessToken()` は**呼ぶたびに有効なものを返す**責務を持ちます
  （Play Services 側がキャッシュと更新を面倒見ます）

### `PhotoUploader` / `AppDataUploader`

```kotlin
interface PhotoUploader {
    suspend fun upload(fileName: String, jpeg: ByteArray): Result<String>  // file id
}
```

`AppDataUploader` は [02 §6.1](02-google-drive.md#61-アップロードnative-のみ) の
リクエストをそのまま組み立てます。

> 注意: **`"parents": ["appDataFolder"]` を必ず入れること。**忘れるとマイドライブ直下に
> 落ちます。エラーになりません。**テストで固定してください。**

> 注意: URL は `https://www.googleapis.com/**upload**/drive/v3/files?uploadType=multipart`。
> `upload/` を忘れると空ファイルが作られます。

`OkHttpClient` を**コンストラクタで注入**してください。MockWebServer に差し替えて
テストするためです。

### `CaptureState`

```kotlin
data class CaptureState(
    val isRunning: Boolean = false,
    val capturedCount: Int = 0,
    val uploadedCount: Int = 0,
    val lastUploadedAt: Instant? = null,
    val lastError: String? = null,
)
```

`StateFlow<CaptureState>` として公開し、Compose 側で `collectAsState()` します。

---

## 8. 画面

**1 画面のみ。** Compose で以下を縦に並べます。

```
┌────────────────────────────────┐
│  定点撮影                        │
├────────────────────────────────┤
│  アカウント: user@example.com    │
│  [ サインイン ]                  │
├────────────────────────────────┤
│  撮影間隔: 5 分（固定）           │  ← 表示のみ。変更 UI は作らない
│                                │
│      [ 開始 ]    [ 停止 ]        │
├────────────────────────────────┤
│  撮影枚数     : 12               │
│  送信枚数     : 11               │
│  最終送信     : 06:35:12         │
│  直近エラー   : （なし）          │
└────────────────────────────────┘
```

- カメラプレビューは**出しません**（サービスがバックグラウンドで撮るため、
  プレビューがあると「前面にいないと撮れない」誤解を招きます）
- 未サインイン時は [開始] を disabled にする
- 実行中は [開始] を disabled にする（二重起動防止の UI 側の担保）
- **エラーは必ず画面に出す。**ログだけに出して黙らないこと

---

## 9. テスト方針

**テストファーストです。テストファイルを先に作ってから実装してください。**
Red → Green → Refactor。テスト名は日本語で「何を保証するか」を書きます。

**単体テストを基本とします。** Espresso / Compose UI テスト / Robolectric は
引き続き**書きません**（[01-overview.md 6.3](01-overview.md#63-テストファーストただし単体テストのみ)）。

**例外: ハードウェアの実挙動に依存する機能は `androidTest` で実機テストを書きます。**
CameraX のトーチ制御（`Camera.cameraControl.enableTorch()`）はエミュレータや Robolectric
では実際に点灯するかを検証できないため、2026-08-22、issue #10 を機にこの機能に限って
例外を認めました。`domain` の抽象（`TorchController`）自体はこれまでどおり interface
のみとし、実機ロジックの検証は `data` 層の CameraX 実装（`CameraXTorchController`）に
対する `androidTest` で行います。

配置は `src/test/kotlin/...`（`domain` と、フェイクに差し替えた `data` のロジック）と
`src/androidTest/kotlin/...`（ハードウェアの実挙動、実機接続時のみ実行）。
`domain` を最も厚く、`data` はフェイクに差し替えて、`presentation` は状態遷移のみ。

### 実機なしでテストできるもの（必ず書く）

| 対象 | 保証すること |
|---|---|
| `PhotoNaming` | ゼロ埋めが正しい / 秒精度である / 形式が `CAM001_yyyyMMdd_HHmmss.jpg` である |
| `CaptureScheduler` | 開始時に即 1 回発火する / 間隔どおりに発火する / 停止後は発火しない |
| `CaptureCoordinator` | **送信が撮影間隔より長くても撮影がスキップされない**（6 節の退行の再発防止） |
| `CaptureCoordinator` | 送信失敗時に撮影を止めない / エラーが状態に載る |
| `AppDataUploader` | **リクエストボディに `"parents":["appDataFolder"]` が含まれる** |
| `AppDataUploader` | URL が `/upload/drive/v3/files?uploadType=multipart` である |
| `AppDataUploader` | `Authorization: Bearer` ヘッダが付く |
| `AppDataUploader` | 401 / 403 / 5xx が `Result.failure` になる |
| `CaptureState` | 撮影・送信・失敗で状態が期待どおり遷移する |

### androidTest が要るもの（実機接続時に自動実行、例外）

| 対象 | 保証すること |
|---|---|
| `CameraXTorchController` | トーチを ON にできる / OFF にできる（`src/androidTest/kotlin/...`） |

> `androidTest` のテスト名（バッククォート日本語名）に**スペースを含めないこと。**
> DEX はメソッド名のスペースを許さず、`dexBuilderDebugAndroidTest` がビルド失敗します
> （`src/test` の JVM 単体テストでは問題になりません）。

### 実機が要るもの（手動確認）

- CameraX が EXIF 付き JPEG を出すこと
- フォアグラウンドサービスがバックグラウンドで撮り続けること
- 権限拒否時の挙動

### 禁止

- **外部 API を叩くテストを書かないこと。** Drive API は MockWebServer に差し替えます。
  ネットワークに依存するテストは、当日必ず落ちます
- **結合テスト・E2E・UI テストを書かないこと。** ハードウェアの実挙動検証に限り
  `androidTest` を例外的に許可します（上記）。それ以外（画面操作フローなど）は単体テストのみです
- **`domain` のテストで `android.*` を import しないこと。** 必要になったら、
  それは `domain` に Android 依存が漏れているというサインです

---

## 10. lint

**プロジェクト作成時に整備します。** 後回しにすると、既存コードの違反が溜まって
導入できなくなります。

- **ktlint**（Gradle プラグイン）を導入し、`./gradlew ktlintCheck` で検査できる状態にする
- Android Lint（`./gradlew lint`）も併せて通しておく

**lint が落ちた状態で「完了」と言わないでください**
（[01-overview.md 6.1](01-overview.md#61-ci-は回さない最後のテストが唯一のリリース障壁である)）。

---

## 11. 開発コマンド

```bash
./gradlew ktlintCheck          # lint
./gradlew test                 # 単体テスト
./gradlew assembleDebug        # ビルド
./gradlew installDebug         # 実機へインストール
adb devices                    # 実機接続の確認
adb logcat -s FarmCamera       # ログ確認
```

### 完了の定義

**CI は回しません。** 壊れたコードを止める仕組みは、実装者が最後に自分で回す
これらのコマンドしかありません。

```
1. ./gradlew ktlintCheck    が通る
2. ./gradlew test           が全件通る
3. ./gradlew assembleDebug  が通る
```

**3 つすべてを実際に実行し、出力を確認してから「できた」と言ってください。**

> 開発機は **Windows / PowerShell 5.1** です。`&&` は使えません。
> `;` か `if ($?) { ... }` を使ってください。
> 例: `.\gradlew ktlintCheck; if ($?) { .\gradlew test }`

### 署名鍵の注意

OAuth クライアント ID はパッケージ名と署名 SHA-1 の組で登録されます。
**別マシンでビルドすると SHA-1 が変わり、サインインだけが失敗します。**
他は全部動くので原因に辿り着きにくい障害です。**ビルド機を固定してください**
（→ [Q12](00-openquestion.md)）。

SHA-1 の取得:
```
keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android
```

---

## 12. 実装時の注意

- **エラーを握り潰さないこと。**送信失敗は画面に出します。ログだけに出して黙ると、
  「動いているように見えて 1 枚も上がっていない」状態になります
- **送信失敗時にファイルを削除しないこと。**端末に残します（自動再送はしませんが、
  データを捨てる理由もありません）
- **無限リトライループを書かないこと。**次の撮影サイクルに任せます
- **撮影間隔は定数 1 箇所に閉じ込めること。**値は未決（→ [Q3](00-openquestion.md)）
- **メーカー独自の省電力機能はフォアグラウンドサービスも殺します。**
  検知できません。デモ端末の「バッテリー最適化」を対象外に設定してください
  （→ [Q10](00-openquestion.md)）
