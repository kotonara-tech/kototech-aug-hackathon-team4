# AGENTS.md — 定点撮影MVP（Android / Flutter）

このドキュメントは実装前の仕様書。対象は `POC_ToDo_担当A_Androidアプリ.docx` に定義されたハッカソン当日ゴールを最速で満たす**最小MVP**であり、`risk-assessment.md` に記載した将来拡張（農場名登録・撮影担当・未送信データ非削除保持・画像拡大ビューアなど）はここでは**意図的に対象外**とする。将来拡張はGitHubのSTEP issue（#3〜#7, #9, #10）としてバックログに残す。

## 1. ゴールと完了条件

**目的**: Android端末で定期撮影し、JPEG画像をGoogle Driveへアップロードできるアプリを完成させる。

**完了条件（Definition of Done）**:
- 1分間隔で撮影した画像が、Google Driveの「FarmCameraPOC」フォルダへ連続保存される（実機・デバッグAPKで確認）。

## 2. スコープ（今回やること）

1. Androidプロジェクトのセットアップ、実機接続、カメラ権限の取得
2. 背面カメラのプレビューとJPEG撮影
3. ファイル名 `CAM001_yyyyMMdd_HHmmss.jpg` で端末に一時保存
4. 撮影間隔を 1分・5分・10分・30分 から選択可能にする
5. 撮影の開始・停止、二重実行防止
6. Googleアカウント認証とDriveアップロード
7. 撮影枚数・送信枚数・最終送信時刻・エラーを画面表示
8. 実機用デバッグAPKをビルドし、1分間隔での連続動作を確認

## 3. スコープ外（今回やらない・将来issue）

| 項目 | 対応するissue | 備考 |
|---|---|---|
| 農場名の登録・撮影中ロック | STEP3 (#4) 派生 | プロトタイプでは実装済み。今回のAndroid MVPには含めない |
| 撮影担当（所有者/家族/JA職員）の記録 | STEP3 (#4) 派生 | 同上 |
| 未送信データの非削除保持・容量警告 | STEP4 (#5) 派生 | 今回は送信失敗時のリトライのみ最小実装（詳細は5節） |
| 保存/送信タブ分離・画像拡大ビューア | STEP3 (#4) | 今回は単一画面のステータス表示のみ |
| 画面スリープ抑止・前面復帰時の再初期化 | STEP5 (#6), STEP6 (#7) | 今回は前面表示前提のデモ運用でカバー |
| Google認証（本格運用: ロール・監査ログ） | #9 | 今回はサインインのみの素朴な認証 |
| カメラのトーチ制御 | #10 | 対象外 |

## 4. 技術スタック・環境

- Flutter 3.47.1 (stable) — Android向けのみ。iOSは対象外。
- 開発機のセットアップ（このセッションで実施済み）:
  - Flutter SDK: `C:\dev\flutter`（旧 `Downloads\flutter_windows_3.47.1-stable` から移設）
  - Android SDK: `%LOCALAPPDATA%\Android\Sdk`（build-tools 36.0.0, platform android-37.0, ライセンス承諾済み）
  - JAVA_HOME: `C:\Program Files\Android\Android Studio\jbr`（Android Studio同梱JBR）
  - ユーザー環境変数PATHに `C:\dev\flutter\bin` と `...\Android\Sdk\platform-tools` を追加済み（新しいシェルで有効）
  - `flutter doctor` の残課題: cmdline-tools コンポーネント未導入（ビルド自体はbuild-tools/platformsで可能なため必須ではないが、`sdkmanager` を使う操作が必要になったらAndroid Studio「SDK Manager > SDK Tools > Android SDK Command-line Tools」から追加）
  - AVD未作成・実機未接続。動作確認はUSBデバッグを有効化した実機を想定（ToDo記載の「実機接続」に合わせる）
- 依存パッケージ方針:
  - `camera` — 背面カメラのプレビュー・静止画撮影
  - `permission_handler` — カメラ権限の実行時リクエスト
  - `google_sign_in` — Googleアカウント認証（Drive APIスコープ）
  - Drive アップロードは `googleapis` フルパッケージではなく、`google_sign_in` の認証済みHTTPクライアント + `http` パッケージで Drive API v3 の multipart アップロードを直接呼び出す（依存を軽量に保ちビルド時間を短縮するため）
  - `path_provider` — 撮影ファイルの一時保存先取得

## 5. アプリ構成

### 5.1 画面構成
単一画面アプリ（プロトタイプのタブ構成は今回採用しない）:
- 上部: カメラプレビュー
- 中段: 撮影間隔セレクタ（1分/5分/10分/30分）、開始/停止ボタン
- 下部: ステータスパネル（撮影枚数・送信枚数・最終送信時刻・直近エラー）

### 5.2 撮影〜送信フロー
1. 起動時にカメラ権限を要求 → 許可されたらプレビュー開始
2. Googleサインインボタンでアカウント認証（未認証時は開始不可）
3. 間隔選択 → 「開始」でタイマー起動。多重起動防止のため実行中は開始ボタンをdisabled化
4. タイマー発火ごとに撮影 → `CAM001_yyyyMMdd_HHmmss.jpg` として端末の一時ディレクトリへ保存 → 撮影枚数をインクリメント
5. 保存直後にDriveアップロードを試行:
   - アプリ専用フォルダ「FarmCameraPOC」をDrive内で検索（`drive.file` スコープなのでアプリ作成ファイルのみ検索対象）。無ければ作成し、フォルダIDをローカル（SharedPreferences）にキャッシュ
   - フォルダへmultipartアップロード。成功で送信枚数をインクリメントし最終送信時刻を更新、失敗はエラーメッセージを表示して**当該ファイルは端末に残す**（今回は自動削除ロジックを入れない＝簡易的な非破壊）
   - 明示的な再送キューは実装しない（MVPでは失敗時に手動で「停止→開始」しての再試行を許容する程度に留める。自動再送は将来issue化）
6. 「停止」でタイマー停止。実行中フラグを解除

### 5.3 ファイル名・フォルダ
- ファイル名: `CAM001_yyyyMMdd_HHmmss.jpg`（`CAM001` は今回固定の端末IDプレースホルダー。農場名等との連携は将来issue）
- Driveフォルダ名: `FarmCameraPOC`（マイドライブ直下）

## 6. Google Cloud / OAuth設定（未着手・要協働）

- GCPプロジェクトを新規作成し、Drive APIを有効化
- OAuth同意画面を設定（テストユーザー登録、スコープ `https://www.googleapis.com/auth/drive.file`）
- Android用OAuthクライアントIDを発行するために以下が必要:
  - **applicationId（パッケージ名）**: `com.kotonara.farmcamera`（提案。変更する場合はGCP登録前に確定させる）
  - **SHA-1フィンガープリント**: デバッグ用keystore（`%USERPROFILE%\.android\debug.keystore`）から取得
- 上記が揃い次第、`google-services.json` は使わず `google_sign_in` のAndroidネイティブ設定（`applicationId` と署名SHA-1をGCP Consoleに登録するのみ）で完結させる方針

## 7. 当日の進め方（参考: ToDo文書より）

- 午前前半: プレビュー・手動撮影・JPEG保存を完成
- 午前後半: Googleログインと画像1枚のDrive送信を完成
- 午後前半: 定期撮影・開始/停止・状態表示を結合
- 午後後半: 担当Bと接続し、APKと実機をデモ状態へ調整
