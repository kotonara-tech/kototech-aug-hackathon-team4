# Kotlin 移行版: Codex / Claude Code 実装分担

正本はリポジトリ直下の `docs/00`〜`05`。この文書は実装の担当と着手順を示す。

## 今すぐ進める

| 担当 | issue（登録予定） | 範囲 | 完了条件 |
|---|---|---|---|
| Codex | Kotlin M0 | `android/` プロジェクト、Gradle / JDK / APKのビルド疎通 | `android/gradlew.bat -p android test assembleDebug` が成功 |
| Claude Code | Kotlin M1a | `PhotoNaming` / `CaptureScheduler` のTDD実装 | 命名、即時発火、停止、多重開始拒否を日本語テストで固定 |
| Claude Code | Kotlin M1b | `CaptureState` / `CaptureCoordinator` / 抽象のTDD実装 | 送信遅延で撮影を欠測させず、送信失敗を状態へ反映 |
| Codex | Kotlin M1c | `AppDataUploader` のTDD実装 | multipart URL、Bearer、`parents:["appDataFolder"]`、4xx/5xxをMockWebServerで固定 |

## 検証ゲート後

| 条件 | 担当 | 範囲 |
|---|---|---|
| Q1/Q6/Q11が確定 | Codex + Claude Code | M2: AppData共有の実地検証スパイク |
| M2成功、Q3確定 | Claude Code | M3: CameraX + Foreground Service |
| M3成功 | Codex | M4: Composeステータス画面 |
| M4成功 | Codex + Claude Code + Web担当 | M5: 実機・Web合同受入 |

## 着手禁止

- 旧Flutter実装を対象とする #34 / PR #39 には実装を追加しない。
- Q1の実地検証が成功するまで、M3以降を始めない。
- Drive実APIをユニットテストから呼ばない。
