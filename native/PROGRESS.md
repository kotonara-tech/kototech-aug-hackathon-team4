# 実装進捗メモ（Android MVP）

最終更新: 2026-08-21

## 現在地

**実機に配る手前まで到達している。** アプリはビルド・インストールでき、カメラ権限からプレビュー・撮影・端末内保存・間隔切替・開始停止まで動く実装になっている。
残る関門は**アプリ側ではなく外部設定**で、`AGENTS.md` 1節の完了条件（Drive へ連続保存）に到達するには **#11（GCP/OAuth）と #12（実機接続）** が必要。

## 完了したこと

### 環境セットアップ
- Flutter SDK 3.47.1 (stable) を `Downloads` から `C:\dev\flutter` へ移設
- ユーザー環境変数に `PATH`（`C:\dev\flutter\bin`、`Android\Sdk\platform-tools`）、`ANDROID_HOME`/`ANDROID_SDK_ROOT`、`JAVA_HOME`（Android Studio同梱JBR）を設定
- Android SDK（build-tools 36.0.0, platforms, ライセンス）は既存インストール済みのものを流用

### 仕様書
- `AGENTS.md` を新規作成し、`POC_ToDo_担当A_Androidアプリ.docx` 準拠の**最小MVPスコープ**を明文化
  - スコープ内: カメラプレビュー/撮影、1・5・10・30分間隔選択、開始/停止（二重実行防止）、Google Drive連携、ステータス表示、デバッグAPK
  - スコープ外（将来issue化）: 農場名登録、撮影担当記録、未送信データ非削除保持、拡大ビューア、スリープ抑止 等

### issue駆動開発のセットアップ
- GitHub Issue を24件作成（`kotonara-tech/kototech-aug-hackathon-team4`）
  - #1〜#8: STEP0〜STEP7 / #9・#10: future / #11: GCP・OAuth設定 / #12: 実機接続 / #13: ビルド性能 / #14: テスト基盤
  - #19: 自動再送キュー / #21: Web連携コントラクト / #22: レスポンシブ対応 / #23: 配信準備 / #24: 署名鍵の運用
- クローズ済み: #1, #2, #3, #5, #13, #14, #17
- `risk-assessment.md` のリポジトリ情報を現状（実リポジトリ名・**Public**）に修正し、STEP に issue 番号を紐付け

### Flutterプロジェクト（`app/`）
- `flutter create` でAndroid専用プロジェクトを作成（applicationId: `com.kotonara.farmcamera`）
- 依存追加: `camera`, `permission_handler`, `google_sign_in`, `http`, `path_provider`, `shared_preferences`, `fake_async`(dev)
- `AndroidManifest.xml` にCAMERA/INTERNET権限を追加
- **APKビルド成功**（`compileSdk = 37` へ変更して `permission_handler_android` のAARメタデータチェックを通過）

### 責務分割とテスト基盤（#14 / #17）
`main.dart` の単一 `StatefulWidget` からロジックを切り出し、ハードウェアと認証を抽象の裏に隠して **テストを 1件 → 55件** まで増やした。

| ファイル | 責務 |
|---|---|
| `lib/capture_naming.dart` | `buildPhotoFileName()` — `CAM001_yyyyMMdd_HHmmss.jpg` 形式 |
| `lib/capture_session.dart` | UI・カメラ非依存の撮影セッション状態機械。`kCaptureIntervals` で選択肢を仕様として固定 |
| `lib/capture_scheduler.dart` | `Timer.periodic` のラッパ。開始時に即1回発火する |
| `lib/photo_source.dart` | カメラの抽象（`PhotoSource`）と `camera`/`permission_handler` による実装 |
| `lib/auth_gateway.dart` | Google認証の抽象（`AuthGateway`）と `google_sign_in` による実装 |
| `lib/photo_uploader.dart` / `lib/drive_uploader.dart` | 送信先の抽象と Drive API v3 実装（`http.Client` 注入でテスト可能） |
| `lib/main.dart` | 上記を組み立てて描画するだけの薄いUI層 |

途中、**送信が遅いと撮影がスキップされる退行**を作り込んでいたが、Codex による TDD レビューで検出し #17 / PR #18 で修正した（撮影完了時点で撮影中フラグを解放する）。

### ビルド・テスト環境の調査（#13, クローズ済み）
起票時の前提「解析が極端に遅い」「テストがランダムに落ちる」は**どちらも誤りだった**ことを計測で確認。詳細は `app/BUILD_NOTES.md`。

- `flutter analyze` の48分は**初回のみ**。2回目以降は10〜12秒
- `The Dart compiler exited unexpectedly` は**常に本物のコンパイルエラー**。並列実行時に無関係なファイルが巻き添えで失敗報告されるだけ
- 恒久的な設定変更（`dart_test.yaml`）は不要と判断し、見送った

### 配信準備（#23）
配布用APKをビルドし、手順を `app/DISTRIBUTION.md` にまとめた。

| 成果物 | サイズ |
|---|---|
| `dist/farmcamera-release-arm64-v8a.apk` | 17.5 MB |
| `dist/farmcamera-release-armeabi-v7a.apk` | 14.9 MB |
| `dist/farmcamera-debug-universal.apk` | 171 MB |

`dist/` は `.gitignore` 済みで、APK自体はコミットしない。
配布APKの署名証明書 SHA-1 が `DF:39:D5:02:...:2E:BB`（debug keystore）であることを `apksigner` で確認し、#11 に登録用の値としてコメント済み。

## 未解決の課題

- **GCP側のOAuth設定が未着手**（#11）: 同意画面・Android用OAuthクライアントID発行。登録に必要な値（パッケージ名・SHA-1・スコープ）は issue にコメント済みで、あとは Console 上の操作のみ。#5・#8・#9 をブロック中
- **実機/エミュレータが未接続**（#12）: 端末は用意される見込み。USBデバッグを有効にして `adb devices` に出れば `DISTRIBUTION.md` の手順で入る
- **Web連携の受け渡し方法が未合意**（#21）: `drive.file` スコープは**アプリが作成したファイルしか見えない**ため、「Driveに置けばWebから読める」とは限らない。#11 の構成にも影響するので先に決める必要がある
- **レスポンシブ未対応**（#22）: 現状は縦積みレイアウトのみ。横向きで崩れる可能性がある
- **署名鍵がビルドマシン依存**（#24）: 別マシンでビルドすると SHA-1 が変わり、サインインだけが失敗する

## 次回の再開ポイント

1. **#11** GCPプロジェクト作成・Drive API有効化・OAuth同意画面・Android用OAuthクライアントID発行（値は issue コメント参照）
2. **#12** 実機接続 →`DISTRIBUTION.md` の手順でインストール → **#8** 1分間隔の連続動作を確認
3. **#21** Web側との受け渡しコントラクトを合意（#11 の構成に影響）
4. **#22** レスポンシブ対応、**#6** スリープ抑止、**#7** ライフサイクル対応 を TDD で実装
