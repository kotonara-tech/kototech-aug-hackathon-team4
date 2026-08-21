# 実装進捗メモ（Android MVP）

最終更新: 2026-08-22

## 現在地

**アプリ側の作業は実機検証待ちの状態まで到達した。** 配布用APKをビルドでき、端末に入れれば起動する。
残る関門は**アプリ側ではなく外部設定**で、`AGENTS.md` 1節の完了条件（Drive へ1分間隔で連続保存）に到達するには **#11（GCP / OAuth）** が必要。#11 が唯一のブロッカー。

| | 状態 |
|---|---|
| テスト | 61件パス / 行カバレッジ 80.2%（210/262行） |
| 配布用APK | release arm64 17.5MB / armeabi-v7a 14.9MB / debug universal 171MB |
| 実機での動作確認 | **未実施**（#8） |
| GCP / OAuth | **未着手**（#11。登録に必要な値は issue にコメント済み） |

## 完了したこと

### 環境セットアップ
- Flutter SDK 3.47.1 (stable) を `Downloads` から `C:\dev\flutter` へ移設
- ユーザー環境変数に `PATH`（`C:\dev\flutter\bin`、`Android\Sdk\platform-tools`）、`ANDROID_HOME`/`ANDROID_SDK_ROOT`、`JAVA_HOME`（Android Studio同梱JBR）を設定
- Android SDK（build-tools 36.0.0, platforms, ライセンス）は既存インストール済みのものを流用

### 仕様書
- `AGENTS.md` を新規作成し、`POC_ToDo_担当A_Androidアプリ.docx` 準拠の**最小MVPスコープ**を明文化
  - スコープ内: カメラプレビュー/撮影、1・5・10・30分間隔選択、開始/停止（二重実行防止）、Google Drive連携、ステータス表示、デバッグAPK
  - スコープ外（将来issue化）: 農場名登録、撮影担当記録、未送信データ非削除保持、拡大ビューア、スリープ抑止 等

### issue駆動開発
GitHub Issue を26件起票し、issue → ブランチ → PR の流れで進めている。

- **クローズ済み（10件）**: #1, #2, #3, #5, #13, #14, #17, #23, #24, #26
- **マージ済みPR**: #15, #16, #18, #20, #25, #27
- `risk-assessment.md` のリポジトリ情報を現状（実リポジトリ名・**Public**）に修正し、STEP と後から追加した issue を紐付け

### Flutterプロジェクト（`app/`）
- `flutter create` でAndroid専用プロジェクトを作成（applicationId: `com.kotonara.farmcamera`）
- 依存追加: `camera`, `permission_handler`, `google_sign_in`, `http`, `path_provider`, `shared_preferences`, `fake_async`(dev)
- `AndroidManifest.xml` にCAMERA/INTERNET権限を追加
- **APKビルド成功**（`compileSdk = 37` へ変更して `permission_handler_android` のAARメタデータチェックを通過）

### 責務分割とテスト基盤（#14 / #17 / #26）
`main.dart` の単一 `StatefulWidget` からロジックを切り出し、ハードウェアと認証を抽象の裏に隠して **テストを 1件 → 61件** まで増やした。

| ファイル | 責務 | カバレッジ |
|---|---|---|
| `lib/capture_session.dart` | UI・カメラ非依存の撮影セッション状態機械 | 100.0% |
| `lib/capture_scheduler.dart` | `Timer.periodic` のラッパ。開始時に即1回発火 | 100.0% |
| `lib/capture_naming.dart` | `CAM001_yyyyMMdd_HHmmss.jpg` の生成 | 100.0% |
| `lib/drive_uploader.dart` | Drive API v3 実装（`http.Client` 注入でテスト可能） | 98.0% |
| `lib/main.dart` | 組み立てと描画だけの薄いUI層 | 93.1% |
| `lib/photo_source.dart` | カメラの抽象と `camera`/`permission_handler` 実装 | **0.0%** |
| `lib/auth_gateway.dart` | Google認証の抽象と `google_sign_in` 実装 | **0.0%** |
| `lib/photo_uploader.dart` | 送信先の抽象（実行行なし） | — |

途中、**送信が遅いと撮影がスキップされる退行**を作り込んでいたが、Codex による TDD レビューで検出し #17 / PR #18 で修正した（撮影完了時点で撮影中フラグを解放する）。

`photo_source.dart` / `auth_gateway.dart` の **0% は設計どおり**（プラットフォームチャネルを叩く薄いアダプタでホスト側では実行できない）。ただし **0% は「誰も検証していない」という意味でもある**。端末固有の不具合はここに出るため、検証は #8 が担う。カバレッジの数字だけを見て安心しないこと。

### ビルド・テスト環境の調査（#13）
起票時の前提「解析が極端に遅い」「テストがランダムに落ちる」は**どちらも誤りだった**ことを計測で確認。詳細は `app/BUILD_NOTES.md`。

- `flutter analyze` の48分は**初回のみ**。2回目以降は10〜12秒
- `The Dart compiler exited unexpectedly` は**常に本物のコンパイルエラー**。並列実行時に無関係なファイルが巻き添えで失敗報告されるだけ
- 恒久的な設定変更（`dart_test.yaml`）は不要と判断し、見送った

### 配信準備（#23 / #24）
配布用APKをビルドし、手順を `app/DISTRIBUTION.md` にまとめた。`dist/` は `.gitignore` 済みで APK 自体はコミットしない。

- **USB接続は必須ではない**。APKファイルを端末に送って開くだけで入る（release は 17.5MB）。ログが必要なときはワイヤレスデバッグ（Android 11以降）でつなぐ
- 配布APKの署名証明書 SHA-1 が `DF:39:D5:02:...:2E:BB`（debug keystore）であることを `apksigner` で実測し、#11 に登録用の値としてコメント済み
- **ビルドマシンを固定する**前提を採用（#24）。別マシンでビルドすると SHA-1 が変わり、カメラも撮影も動くのに**サインインだけが失敗する**分かりにくい壊れ方をする

## 未解決の課題

- **GCP側のOAuth設定が未着手**（#11 / 唯一のブロッカー）: 同意画面・Android用OAuthクライアントID発行。登録に必要な値（パッケージ名・SHA-1・スコープ）は issue にコメント済みで、あとは Console 上の操作のみ。**これが終わるまでサインインできず「開始」ボタンが押せない**ため、撮影の連続動作＝完了条件に到達できない
- **実機での動作確認が未実施**（#8）: APKと手順は揃っている。#11 完了前でもプレビューまでは確認できる
- **`flutter run` 環境が未整備**（#12）: `adb devices` は空。ブロッカーではない（APK配布で足りる）が、実機で調整する段階では必要
- **Web連携の受け渡し方法が未合意**（#21）: `drive.file` スコープは**アプリが作成したファイルしか見えない**ため、「Driveに置けばWebから読める」とは限らない。#11 の構成にも影響するので先に決める必要がある。Webアプリ本体は別担当がリリースするため、ここでは実装しない
- **レスポンシブ未対応**（#22）: 現状は縦積みレイアウトのみ。横向きで崩れる可能性がある。実機で崩れ方を確認してから着手するのが効率的
- **未実装のSTEP**: #4（一覧表示）、#6（スリープ抑止）、#7（ライフサイクル対応）

## 次回の再開ポイント

1. **#11** GCPプロジェクト作成・Drive API有効化・OAuth同意画面・Android用OAuthクライアントID発行（値は issue コメント参照）
2. **#8** 実機へインストール（`DISTRIBUTION.md` 参照）→ #11 完了後に1分間隔の連続動作を確認
3. **#21** Web側との受け渡しコントラクトを合意（#11 の構成に影響）
4. **#22** レスポンシブ対応、**#6** スリープ抑止、**#7** ライフサイクル対応 を TDD で実装
