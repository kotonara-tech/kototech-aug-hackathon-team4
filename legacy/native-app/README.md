> **廃止。この文書は旧方針（マイドライブ直下 `FarmCameraPOC` + `drive.file` / `drive.readonly`）で
> 書かれており、現在の設計とは一致しません。設計正本は `docs/` です。**
> 経緯は [legacy/README.md](../README.md) / [Q2](../../docs/00-openquestion.md) を参照。

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
flutter test          # 110件
flutter analyze
flutter run           # 実機接続時
```

カバレッジは下記「カバレッジ」節を参照してください。

テストが `The Dart compiler exited unexpectedly` で落ちたときは、フレークではなく**本物のコンパイルエラー**です。`BUILD_NOTES.md` 3節を参照してください。

## カバレッジ

```bash
flutter test --coverage      # coverage/lcov.info が出る
```

実測値（110件のテスト）:

| ファイル | 行カバレッジ |
|---|---|
| `photo_record.dart` | 100.0% (36/36) |
| `capture_scheduler.dart` | 100.0% (9/9) |
| `capture_naming.dart` | 100.0% (5/5) |
| `capture_session.dart` | 99.0% (102/103) |
| `drive_uploader.dart` | 98.5% (65/66) |
| `main.dart` | 92.5% (124/134) |
| `photo_storage.dart` | 76.9% (10/13) |
| `wake_lock.dart` | 50.0% (4/8) |
| `auth_gateway.dart` | **0.0%** (0/13) |
| `photo_source.dart` | **0.0%** (0/31) |
| 全体 | 84.9% (355/418) |

### 0% を放置している理由

`auth_gateway.dart` / `photo_source.dart` と、`wake_lock.dart` の `ScreenWakeLock` は `google_sign_in` / `camera` / `permission_handler` / `wakelock_plus` のプラットフォームチャネルを叩く**薄いアダプタ**で、ホスト側のテストでは実行できません。**ロジックを持たせず抽象の裏に押し出した結果**であり、設計どおりです。

ただし **0% は「誰も検証していない」という意味でもあります。** 端末固有の不具合はここに出るため、検証は実機確認（#8）が担います。カバレッジの数字だけを見て安心しないでください。

残る未到達行は `main()`・`DriveUploader`・`ScreenWakeLock` の既定インスタンス生成と、`photo_storage.dart` の `resolvePhotoDirectory()`（`path_provider` を叩く保存先の解決）です。つまり**組み立て（composition root）とプラットフォーム呼び出しだけ**で、分岐や判断を含まないため、テストで到達させる価値がありません。削除そのものの判断は `DirectoryPhotoFileStore` 側にあり、実ディレクトリを使って検証しています。

### 数字を目的にしない

カバレッジは「まだ見ていない場所」を探す道具として使っています。追加したテストが実際に効いているかは、**わざとコードを壊して落ちることを確認**して担保します（#26・#6・#4・#33・#32 で実施）。

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
  photo_record.dart      撮影記録（圃場ID/撮影日時/送信状態）と保存先
  photo_storage.dart     写真の保存先と、上限を超えたぶんの削除（#32）
  drive_uploader.dart    Drive API v3 multipart 実装
```

## 制約

Google サインインと Drive 送信には GCP 側の OAuth 設定（issue #11）が必要です。未設定のあいだはプレビューまでしか動きません。詳細は `DISTRIBUTION.md` 0節。
