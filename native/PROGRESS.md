# 実装進捗メモ（Android MVP）

最終更新: 2026-08-21

## 完了したこと

### 環境セットアップ
- Flutter SDK 3.47.1 (stable) を `Downloads` から `C:\dev\flutter` へ移設
- ユーザー環境変数に `PATH`（`C:\dev\flutter\bin`、`Android\Sdk\platform-tools`）、`ANDROID_HOME`/`ANDROID_SDK_ROOT`、`JAVA_HOME`（Android Studio同梱JBR）を設定
- Android SDK（build-tools, platforms, ライセンス）は既存インストール済みのものを流用

### 仕様書
- `AGENTS.md` を新規作成し、`POC_ToDo_担当A_Androidアプリ.docx` 準拠の**最小MVPスコープ**を明文化
  - スコープ内: カメラプレビュー/撮影、1・5・10・30分間隔選択、開始/停止（二重実行防止）、Google Drive連携、ステータス表示、デバッグAPK
  - スコープ外（将来issue化）: 農場名登録、撮影担当記録、未送信データ非削除保持、拡大ビューア、スリープ抑止 等

### Flutterプロジェクト（`app/`）
- `flutter create` でAndroid専用プロジェクトを作成（applicationId: `com.kotonara.farmcamera`）
- 依存追加: `camera`, `permission_handler`, `google_sign_in`, `http`, `path_provider`, `shared_preferences`
- `AndroidManifest.xml` にCAMERA/INTERNET権限を追加
- `lib/main.dart`: カメラプレビュー、撮影間隔選択、開始/停止、Googleサインイン、撮影→Drive送信フロー、ステータス表示（撮影枚数・送信枚数・最終送信時刻・エラー）を実装
- `lib/drive_uploader.dart`: Drive内「FarmCameraPOC」フォルダの検索/自動作成＋multipartアップロードを実装
- `flutter analyze` はエラーなし

## 未解決の課題

- **デバッグAPKビルドが失敗中**: `permission_handler_android` が `compileSdk 37` を要求するのに対し、プロジェクトは `flutter.compileSdkVersion`（36）を使用していたためAARメタデータチェックで失敗。`android/app/build.gradle.kts` の `compileSdk` を `37` に明示変更する修正を適用済みだが、再ビルドは未実施
- GCP側のOAuth同意画面・Android用OAuthクライアントID発行（パッケージ名 `com.kotonara.farmcamera` + デバッグ用SHA-1が必要）は未着手
- 実機（USBデバッグ）またはエミュレータが未接続

## 次回の再開ポイント

1. `compileSdk = 37` 修正後に `flutter build apk --debug` を再実行して成否を確認
2. GCPプロジェクト作成・Drive API有効化・OAuth同意画面・Android用OAuthクライアントID発行（`AGENTS.md` 6節参照）
3. 実機接続（USBデバッグ有効化）して `flutter run` で動作確認
