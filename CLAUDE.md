# CLAUDE.md — 定点観測システム

エージェント向けの入り口。**設計の詳細は `docs/` が正本です。**

---

## 1. まず読むもの

| # | 文書 | 範囲 |
|---|---|---|
| 00 | [docs/00-openquestion.md](docs/00-openquestion.md) | **未決事項。判断に迷ったら最初に見る** |
| 01 | [docs/01-overview.md](docs/01-overview.md) | システム全体、動作契約、責務分担、設計原則、着手順序 |
| 02 | [docs/02-google-drive.md](docs/02-google-drive.md) | Drive AppData 領域。**Native と Web が共通で守る契約層** |
| 03 | [docs/03-native.md](docs/03-native.md) | Android（Kotlin）の設計 |
| 04 | [docs/04-frontend.md](docs/04-frontend.md) | Web（React + Vite SPA）の設計 |

**`02` は両側に効く契約です。`03` や `04` だけを読んで実装しないでください。**
「Web に 1 枚も表示されない」の原因は、ほぼ全部 `02` にあります。

---

## 2. 30 秒で分かる全体像

スマートフォンをエッジデバイスとする**定点観測システム**。

```
[Android/Kotlin]  ──アップロード──>  [Drive AppData 領域]  <──ポーリング──  [Web/React SPA]
 Foreground Service                   （隠し領域）                    サーバーなし・静的
 N 分ごとに撮影                    drive.appdata スコープ            drive.appdata スコープ
```

- **サーバーは存在しません。** 両側ともユーザー本人の OAuth トークンで Drive API を直接叩きます
- **DB は存在しません。** Drive の AppData 領域が唯一の永続層です
- **動作契約は「1 Google アカウント = 1 端末 = 1 Web クライアント」。**
  端末ペアリングは概念としてのみ存在し、実装しません。この割り切りが設計の中核です

---

## 3. 絶対に外してはいけない 3 点

詳細と対処は [docs/02-google-drive.md](docs/02-google-drive.md) にあります。
**いずれもエラーが出ません。静かに 0 件が返るか、間違った場所に書き込むだけです。**

1. **Android 用と Web 用の OAuth クライアントは、同じ GCP プロジェクト内に作る。**
   AppData 領域は「GCP プロジェクト × ユーザー」単位で隔離されます。
   別プロジェクトだと永久に共有されません
2. **スコープは両側とも `drive.appdata`。**
   `drive.readonly` でも `drive`（フル）でも AppData には届きません
3. **Web は `spaces=appDataFolder`、Native は `parents: ["appDataFolder"]` を必ず付ける。**
   忘れると前者はマイドライブを見に行って 0 件、後者はマイドライブ直下にファイルが落ちます

---

## 4. 迷ったときの手順

1. **[docs/00-openquestion.md](docs/00-openquestion.md) を見る。**
   載っていれば**まだ決まっていません。勝手に決めずに人間に確認してください**
2. `02` の契約に関わるか確認する。関わるなら勝手に変えないこと（両側が壊れます）
3. `01` の設計原則を確認する。原則に反する実装は、原則ごと議論してください

---

## 5. 譲れない開発ルール

- **TDD で進めます。**Red → Green → Refactor。テストを書かずに本体コードを追加しないこと
- **テスト名は日本語で「何を保証するか」を書きます**
- **外部 API を叩くテストを書かないこと。**Drive API はフェイクに差し替えます。
  ネットワークに依存するテストは、当日必ず落ちます
- **`.env` / `.env.local` は絶対にコミットしないこと**
- **開発機は Windows / PowerShell 5.1。**`&&` は使えません。`;` か `if ($?) { ... }` を使うこと
- **YAGNI を守ること。**画面は Native も Web も 1 枚です。状態管理ライブラリもキューも要りません

---

## 6. ブランチ戦略

### デフォルトブランチは `develop`

```
main      リリース・安定版。develop からのマージでのみ進む
  ▲
  │ （区切りの良いところでマージ）
  │
develop   デフォルトブランチ。ここが開発の起点
  ▲
  │ Pull Request
  │
<type>/<issue番号>-<slug>   作業ブランチ
```

- **作業ブランチは必ず `develop` から切り、`develop` へ Pull Request を出します**
- `main` へ直接コミットしないこと。`develop` へも直接コミットせず、PR を通すこと
- `main` は区切りの良いタイミングで `develop` からマージします

### Native と Web はブランチを分けない

**`native/` と `web/` の変更を同じブランチに含めて構いません。** 側ごとにブランチを
分ける必要はありません。

**理由**: 両者は [docs/02-google-drive.md](docs/02-google-drive.md) の契約層を介してのみ
結合しています。この契約（スコープ、`spaces`、`parents`、ファイル名規約、EXIF の扱い）は
**変えるときに必ず両側が同時に変わります。** 片側だけを変えたブランチは、
それ単体では正しさを検証できません。分けると「マージするまで動くか分からない PR」が
2 本できるだけです。

ただし、**契約に関わらない変更**（Web の見た目だけ、Native の UI 文言だけ）は
分けても構いません。判断基準は「そのブランチ単体で動作を確認できるか」です。

### ブランチ名

```
<type>/<issue番号>-<slug>
```

`type` は `feat` / `fix` / `docs` / `test` / `refactor` / `perf` のいずれか。

例: `feat/30-appdata-uploader`、`docs/31-design-docs`、`fix/32-capture-skip`

issue が無い作業は番号を省いて構いません（例: `fix/capture-skip-regression`）。

### コミットメッセージ

```
<type>: 日本語で「何をしたか」 (#issue番号)
```

例: `feat: AppData 領域へのアップロードを実装する (#30)`

### 現状

**`develop` ブランチはまだ存在せず、デフォルトブランチは `main` のままです。**
切り替え作業は未実施です（→ [Q2](docs/00-openquestion.md) の作業ブランチの決定と併せて実施）。

---

## 7. リポジトリの現状（重要）

**`native/app/`（Flutter）と `web/app/`（FastAPI + Jinja2）は旧方針の実装で、破棄対象です。**

| 旧 | 新 |
|---|---|
| `native/app/` — Flutter 3.47、55 テスト、APK ビルド済み | Kotlin + CameraX + Foreground Service へ全面書き直し |
| `web/app/` — FastAPI + Jinja2 | React + Vite SPA へ全面書き直し |
| マイドライブ直下 `FarmCameraPOC` フォルダ | `appDataFolder`（AppData 領域） |
| `drive.file`（Native）/ `drive.readonly`（Web） | 両側とも `drive.appdata` |

以下のドキュメントは**旧方針のまま**です。`docs/` と矛盾したら **`docs/` が正**です。

`web/CLAUDE.md` / `web/README.md` / `web/docs/担当A向け連携仕様.md` /
`native/AGENTS.md` / `native/PROGRESS.md`

> 注意: **これらを読んで旧方針で実装しないでください。**
> `drive.file` で書いて `drive.readonly` で読む実装が生まれ、
> 原因不明の 0 枚問題として跳ね返ってきます。
> 処遇は未決（→ [Q2](docs/00-openquestion.md)）。

---

## 8. 実装の着手順序

```
[現実] GCP プロジェクト作成・Drive API 有効化・同意画面・クライアント ID 発行
   │
   ▼
[検証] ★ AppData が両クライアントから共有されることの実地確認   ← 最優先
   │     docs/02-google-drive.md 7 節
   ├──────────────────────┬─────────────────────┐
   ▼                      ▼                     │
[Native] 撮影 → 送信       [Web] ログイン → 一覧   │
   └──────────┬───────────┘                     │
              ▼                                 │
        [つなぎ込み] 実データでの結合確認  ◀───────┘
```

**★ の検証が通るまで、Native と Web の実装に人手を割かないでください。**
ここが崩れると設計ごとやり直しになり、書いたコードは全部無駄になります
（→ [Q1](docs/00-openquestion.md)）。
