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
flutter test          # 55件
flutter analyze
flutter run           # 実機接続時
```

テストが `The Dart compiler exited unexpectedly` で落ちたときは、フレークではなく**本物のコンパイルエラー**です。`BUILD_NOTES.md` 3節を参照してください。

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
  drive_uploader.dart    Drive API v3 multipart 実装
```

## 制約

Google サインインと Drive 送信には GCP 側の OAuth 設定（issue #11）が必要です。未設定のあいだはプレビューまでしか動きません。詳細は `DISTRIBUTION.md` 0節。
