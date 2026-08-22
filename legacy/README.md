# legacy/ — 廃止した旧方針の実装とドキュメント

**ここにあるものは全部、廃止済みです。読んで実装しないでください。**

設計正本は [`../docs/`](../docs/) です。矛盾したら `docs/` が正です。

退避の判断は [Q2](../docs/00-openquestion.md#q2-既存実装と既存ドキュメントをどう処理するか)（2026-08-22 決着）によります。

## 何が入っているか

| パス | 中身 | 置き換わる先 |
|---|---|---|
| `native-app/` | Flutter 3.47 + Dart の Android 実装。110 テスト、デバッグ APK ビルド済み | Kotlin + CameraX + Foreground Service（[docs/03](../docs/03-native.md)） |
| `native-prototype/` | HTML の画面プロトタイプ | — |
| `AGENTS.md` | 旧方針の Flutter MVP 仕様書 | [docs/01](../docs/01-overview.md), [docs/03](../docs/03-native.md) |
| `PROGRESS.md` | 旧実装の進捗記録 | [docs/05](../docs/05-implementation-plan.md) |
| `web-app/` | FastAPI + Jinja2 の Web 実装とドキュメント一式 | React + Vite SPA（[docs/04](../docs/04-frontend.md)） |

## なぜ消さずに残しているか

**移植したい中身がまだ残っているためです。** [docs/05 §3](../docs/05-implementation-plan.md) の
M1 で、次のものをテストケースごと Kotlin へ移します。

- `native-app/lib/capture_naming.dart` — ファイル名規約。[docs/02 §4.2](../docs/02-google-drive.md) と一致済み
- `native-app/lib/capture_scheduler.dart` — 「開始時に即1回発火」「多重 start を拒否」
- `native-app/lib/capture_session.dart` — **送信が撮影間隔より長くても撮影をスキップしない**（#17 の退行対策）。[docs/03 §6](../docs/03-native.md) が名指しで警告しているもの
- `native-app/lib/drive_uploader.dart` — multipart の組み立てと 4xx/5xx の扱い
- `native-app/BUILD_NOTES.md` — ビルド・テスト環境の実測値。Kotlin 版でも同じ調査をする

移植が終わったら、このディレクトリごと消して構いません。

## ★ 特に混同しやすい点

旧実装は**マイドライブ直下の `FarmCameraPOC` フォルダ**に `drive.file` スコープで書き、
Web は `drive.readonly` で読んでいました。**新方針はどちらも `drive.appdata` で、
`appDataFolder` にフラット配置します。**

この2つを混ぜると「エラーが出ずに 0 枚」という形で跳ね返ります
（[docs/02](../docs/02-google-drive.md)）。
