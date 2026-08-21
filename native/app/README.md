# farmcamera — 定点撮影POC（Android）

一定間隔で圃場を撮影し、JPEG を Google Drive の `FarmCameraPOC` フォルダへ送るハッカソン用 Android アプリ。

## ドキュメント

| ファイル | 内容 |
|---|---|
| [`../AGENTS.md`](../AGENTS.md) | 仕様書。スコープ・完了条件・撮影〜送信フロー |
| [`DISTRIBUTION.md`](DISTRIBUTION.md) | **配布手順。** 実機へインストールするならまずこれ |
| [`BUILD_NOTES.md`](BUILD_NOTES.md) | ビルド・テスト環境の実測メモとトラブル時の読み解き方 |
| [`../PROGRESS.md`](../PROGRESS.md) | 実装進捗と未解決の課題 |
| [`../risk-assessment.md`](../risk-assessment.md) | リスク評価と将来拡張 |

## 開発

```bash
flutter pub get
flutter test          # 70件
flutter analyze
flutter run           # 実機接続時
```

カバレッジは下記「カバレッジ」節を参照してください。

テストが `The Dart compiler exited unexpectedly` で落ちたときは、フレークではなく**本物のコンパイルエラー**です。`BUILD_NOTES.md` 3節を参照してください。

## カバレッジ

```bash
flutter test --coverage      # coverage/lcov.info が出る
```

実測値（70件のテスト）:

| ファイル | 行カバレッジ |
|---|---|
| `capture_session.dart` | 100.0% (63/63) |
| `capture_scheduler.dart` | 100.0% (9/9) |
| `capture_naming.dart` | 100.0% (5/5) |
| `drive_uploader.dart` | 98.0% (50/51) |
| `main.dart` | 93.4% (99/106) |
| `wake_lock.dart` | 25.0% (2/8) |
| `auth_gateway.dart` | **0.0%** (0/13) |
| `photo_source.dart` | **0.0%** (0/31) |
| 全体 | 79.7% (228/286) |

### 0% を放置している理由

`auth_gateway.dart` / `photo_source.dart` と、`wake_lock.dart` の `ScreenWakeLock` は `google_sign_in` / `camera` / `permission_handler` / `wakelock_plus` のプラットフォームチャネルを叩く**薄いアダプタ**で、ホスト側のテストでは実行できません。**ロジックを持たせず抽象の裏に押し出した結果**であり、設計どおりです。

ただし **0% は「誰も検証していない」という意味でもあります。** 端末固有の不具合はここに出るため、検証は実機確認（#8）が担います。カバレッジの数字だけを見て安心しないでください。

残る未到達行は `main()`・`DriveUploader`・`ScreenWakeLock` の既定インスタンス生成、つまり**組み立て（composition root）だけ**です。分岐や判断を含まないため、テストで到達させる価値がありません。

### 数字を目的にしない

カバレッジは「まだ見ていない場所」を探す道具として使っています。追加したテストが実際に効いているかは、**わざとコードを壊して落ちることを確認**して担保します（#26・#6 で実施）。

## 構成

UI からハードウェアと外部サービスを抽象で切り離し、ウィジェットテスト・ユニットテストで検証できるようにしています。

```
lib/
  main.dart              組み立てと描画だけの薄いUI層
  capture_session.dart   撮影セッションの状態機械（UI・カメラ非依存）
  capture_scheduler.dart Timer.periodic のラッパ
  capture_naming.dart    CAM001_yyyyMMdd_HHmmss.jpg の生成
  photo_source.dart      カメラの抽象 + camera/permission_handler 実装
  auth_gateway.dart      Google認証の抽象 + google_sign_in 実装
  photo_uploader.dart    送信先の抽象
  wake_lock.dart         画面スリープ抑止の抽象 + wakelock_plus 実装
  drive_uploader.dart    Drive API v3 multipart 実装
```

## 制約

Google サインインと Drive 送信には GCP 側の OAuth 設定（issue #11）が必要です。未設定のあいだはプレビューまでしか動きません。詳細は `DISTRIBUTION.md` 0節。
