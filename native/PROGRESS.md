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

### issue駆動開発のセットアップ
- GitHub Issue を14件作成（`kotonara-tech/kototech-aug-hackathon-team4`）
  - #1〜#8: STEP0〜STEP7 / #9・#10: future / #11: GCP・OAuth設定 / #12: 実機接続 / #13: ビルド性能 / #14: テスト基盤
- `risk-assessment.md` のリポジトリ情報を現状（実リポジトリ名・**Public**）に修正し、STEP に issue 番号を紐付け

### Flutterプロジェクト（`app/`）
- `flutter create` でAndroid専用プロジェクトを作成（applicationId: `com.kotonara.farmcamera`）
- 依存追加: `camera`, `permission_handler`, `google_sign_in`, `http`, `path_provider`, `shared_preferences`
- `AndroidManifest.xml` にCAMERA/INTERNET権限を追加
- **デバッグAPKビルドが成功**（`compileSdk = 37` へ変更して `permission_handler_android` のAARメタデータチェックを通過）
  - `Running Gradle task 'assembleDebug'... 160.2s` / `app-debug.apk` 生成確認

### テスト基盤（#14）
`main.dart` の単一 `StatefulWidget` からロジックを切り出し、TDD で進められる状態にした。

- `lib/capture_naming.dart`: `buildPhotoFileName()` — `CAM001_yyyyMMdd_HHmmss.jpg` 形式
- `lib/photo_uploader.dart`: `PhotoUploader` 抽象クラス（送信先を差し替え可能にする）
- `lib/capture_session.dart`: UI・カメラ非依存の撮影セッション状態機械。`kCaptureIntervals` で撮影間隔の選択肢を仕様として固定
- `lib/drive_uploader.dart`: `PhotoUploader` を実装。`http.Client` と認可ヘッダ供給を注入可能にしてテスト可能化
- `lib/main.dart`: 上記を組み合わせる薄いUI層に整理。`Timer` はUI層が保持

## 未解決の課題

- **GCP側のOAuth設定が未着手**（#11）: 同意画面・Android用OAuthクライアントID発行（パッケージ名 `com.kotonara.farmcamera` + デバッグ用SHA-1が必要）。#5・#8・#9 をブロック中
- **実機/エミュレータが未接続**（#12）: `flutter devices` は Windows/Chrome/Edge のみ、`adb devices` は空。`flutter run` での動作確認ができない
- **`flutter analyze` が極端に遅い**（#13）: 2855秒（約48分）。APKビルドは160秒なので analyze 固有の要因を切り分け中

## 次回の再開ポイント

1. #11 GCPプロジェクト作成・Drive API有効化・OAuth同意画面・Android用OAuthクライアントID発行（`AGENTS.md` 6節参照）
2. #12 実機接続（USBデバッグ有効化）して `flutter run` で動作確認
3. #6 STEP5（スリープ抑止）、#7 STEP6（ライフサイクル対応）をTDDで実装
