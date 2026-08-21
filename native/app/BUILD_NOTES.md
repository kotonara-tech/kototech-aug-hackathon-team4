# ビルド／テスト環境メモ（GitHub issue #13）

調査日: 2026-08-21 / Windows 11 / Flutter 3.47.1 (Dart 3.13.1) / SDK: `C:\dev\flutter`

## 結論

**issue #13 が前提としていた「ビルド・解析が極端に遅い」「テストがランダムに落ちる」は、どちらも誤りだった。**

- `flutter analyze` の48分は**初回だけ**のコスト。2回目以降は10〜12秒
- `flutter test` の `The Dart compiler exited unexpectedly` は**フレークではなく、常に本物のコンパイルエラー**だった

恒久的な設定変更は不要と判断し、`dart_test.yaml` の追加は見送った。代わりに、症状を見たときの読み解き方をここに残す。

## 1. `flutter analyze` / ビルドの所要時間

| コマンド | 初回 | 2回目以降 |
|---|---|---|
| `flutter analyze` | 2855秒（約48分） | **10〜12秒** |
| `flutter build apk --debug` | 160.2秒 | 未計測 |

初回のコストは依存パッケージのダウンロード、解析サーバのコールドスタート、全パッケージの初回解析による。当日のイテレーションに対するリスクは無い。Defender の除外設定なども不要。

## 2. `The Dart compiler exited unexpectedly` の正体

### 症状

既定の並列実行では、**コンパイルエラーと無関係なテストファイルまでロード失敗として報告される**。エラー本文は `Dart compiler exited unexpectedly` という原因を示さないメッセージで、落ちるファイルは実行のたびに変わるように見える。

### 検証

`lib/main.dart` にわざと構文エラーを入れて比較した。`main.dart` を import しているのは `capture_screen_test.dart` だけである。

| 実行 | `Dart compiler exited unexpectedly` | 失敗と報告されたファイル |
|---|---|---|
| `flutter test`（既定＝並列） | **出る** | `capture_screen_test.dart` + **`capture_naming_test.dart`**（無関係） |
| `flutter test --concurrency=1` | 出ない | `capture_screen_test.dart` のみ（正しい） |

並列実行では共有の resident compiler が死ぬため、たまたま同時にロード中だった別ファイルが巻き添えになる。`--concurrency=1` なら巻き添えが起きず、失敗ファイルが正しく1件に絞られる。

**なお、本当のコンパイルエラーのメッセージは並列・直列どちらの出力にも含まれている。** 当初これを見落としたのは、出力を `tail` で切り詰めて読んでいたためで、ツールの問題ではない。

### 正常時は再現しない

コードがコンパイルできる状態では、並列実行でも一度も失敗しなかった。

| 条件 | 実行回数 | 結果 | 所要時間 |
|---|---|---|---|
| 既定（並列） | 5回 | 5/5 全55件パス、crash 0回 | 8.1〜8.2秒 |
| `concurrency: 1` | 5回 | 5/5 全55件パス、crash 0回 | 10.7〜11.5秒 |

別途実行した6回を含めると **16回中0回**。「ランダムに落ちる」という当初の観測は、実際にはその時点で `lib/main.dart` にコンパイルエラーがあったこと（`const <Duration, String>{}` は `Duration` が primitive equality を持たないため不可）が原因だった。

## 3. 運用ルール

**`Dart compiler exited unexpectedly` を見たら、フレークだと思わずコンパイルエラーを疑うこと。**

1. 出力を末尾だけでなく**全体**確認する。本当のエラーはそこに出ている
2. 失敗ファイルを正しく絞りたいときだけ `flutter test --concurrency=1` で再実行する

`dart_test.yaml` に `concurrency: 1` を常設する案は見送った。理由は次のとおり。

- 正常時は毎回2.7秒（約33%）遅くなる
- 得られるのは「エラー時に巻き添えファイルが出ない」という診断上の利点だけで、失敗そのものは防げない
- 必要なときに CLI で `--concurrency=1` を付ければ同じ効果が得られる

なお `dart_test.yaml` の `concurrency` 設定自体は有効に働き、CLI の `--concurrency=N` で上書きできることは確認済み（設定あり＋`--concurrency=4` で8.1秒＝並列時と同じ）。将来必要になれば追加すればよい。

## 4. 未確認のまま残したこと

- `flutter build apk --debug` の2回目以降の所要時間
- `flutter clean` 後のクリーンビルド時間（CI を組む場合はここが効く）
- 実行中のメモリピーク。並列実行でメモリ逼迫が起きているかは測っていない（正常時に再現しないため測る動機が無かった）
