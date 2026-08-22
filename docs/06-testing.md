# 06. テストとカバレッジの残件

最終更新: 2026-08-22

## 現状

| 種別 | 実行方法 | 結果 | カバレッジ |
|---|---|---|---|
| JVM ユニット／コンポーネントテスト | `./gradlew test createDebugUnitTestCoverageReport` | 成功 | 命令 21%、分岐 29% |
| 実機計測テスト | `./gradlew createDebugAndroidTestCoverageReport` | SC-51D（Android 16）で3件成功 | 命令 41%（4,375 / 10,554）、分岐 24%（199 / 801） |

実機計測テストは Debug ビルドで `enableAndroidTestCoverage = true` を有効化して収集する。
JVM と実機のレポートは対象クラスと計測方式が異なるため、数値を単純に平均・加算しない。

## 指標の読み方

JaCoCo の「未カバー」はソース行数ではなく、未実行のバイトコード命令数である。
Compose コンパイラの生成コード、ラムダ、Activity、Service、CameraX、認証・ネットワーク連携も分母に含まれる。
そのため、プロジェクト全体の100%を目標にせず、重要な振る舞いをテストで保証する。

目安は次の通り。

| 層 | 目標 |
|---|---:|
| domain | 命令・分岐ともに80〜90%以上 |
| data（HTTPはMockWebServer） | 70〜80%以上 |
| Android UI／Service／ハードウェア | 数値より主要フローの実機検証 |
| プロジェクト全体 | 40〜60%を上限目安として、意味のある経路を優先 |

## 実装済みの検証

- 撮影・保存・Driveアップロードの協調ロジック（成功、重複、失敗、同時送信抑制）
- Driveリクエスト、HTTP応答、認可エラー（MockWebServer）
- 端末内の画像保存・読み出し・ギャラリー反映
- アップロード状態のSharedPreferences永続化
- 定期撮影の開始・停止、撮影間隔、照明スケジュール
- 実機でのカメラライトON/OFF

## 残件

- [ ] 実機でCameraXによる実撮影を行い、JPEGが保存されることを検証する
- [ ] Foreground Serviceの開始・停止と定期撮影を、実機で短い間隔（10秒）で検証する
- [ ] Compose UIテストを追加し、撮影／画像タブ遷移、画像拡大・戻る、ネットワーク表示を検証する
- [ ] 保存済み画像の一括送信、再送、重複ファイルのスキップを実機で検証する
- [ ] Credential Managerの認証復帰・未認証時の遷移を、テスト用Googleアカウントで手動E2E検証する
- [ ] Google Drive本番環境へのアップロード、リスト照合、タイムアウト時のログを手動E2E検証する
- [ ] CIでJVMテスト・静的チェックを必須化し、実機計測テストは接続端末またはエミュレータの定期ジョブとして実行する

## 実行コマンド

```bash
./gradlew ktlintCheck test createDebugUnitTestCoverageReport
./gradlew connectedDebugAndroidTest
./gradlew createDebugAndroidTestCoverageReport
```

実機テストはカメラライトを操作し、アプリ内ストレージへテスト専用の一時ファイルを作成する。テスト終了時にそのファイルと対応する送信状態は削除する。
