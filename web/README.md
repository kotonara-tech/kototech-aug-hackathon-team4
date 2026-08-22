# web — 定点観測ビューアー（React + Vite SPA）

Android 端末が Google Drive の **AppData 領域**へ上げた画像を、ブラウザから読んで見せるだけの
静的 SPA。**バックエンドは存在しない。**

**設計正本は [`../docs/`](../docs/) 。この README は動かし方だけを書く。**
実装の判断に迷ったら [`../docs/04-frontend.md`](../docs/04-frontend.md) と
[`../docs/02-google-drive.md`](../docs/02-google-drive.md) を読むこと。

---

## デモ画面（Node.js 不要・ネットワーク不要）

```
web/public/demo.html
```

**ダブルクリックでブラウザが開けばそれで動く。** ビルドも `npm install` も要らない。

- Drive API は呼ばない。画像は canvas でその場で合成している（36 枚 = 5 分間隔 3 時間ぶん）
- ログイン → 最新写真 → サムネイル一覧 → 拡大表示 → 更新間隔変更 → 手動更新まで一通り触れる
- 「デモ操作」欄のボタンで **0 件 / 401 / 429 / 404** の挙動をその場で見せられる
  （[docs/04-frontend.md 8 節](../docs/04-frontend.md) のエラーハンドリング表そのまま）

`npm run dev` 中は `http://localhost:5173/demo.html` でも開ける。

別の PC やチームに見せたいときは、同じ画面を共有 URL でも置いてある。
https://claude.ai/code/artifact/c27afa35-9bfa-417c-aead-72fdeee17726

> デモ画面は**見せるための実物大モック**であって、本番の SPA ではない。
> `src/` の React 実装とはコードを共有していない。

---

## 本体（React + Vite）

### 必要なもの

- **Node.js 20 以上**（この開発機には v24.19.0 を導入済み）
- Google OAuth クライアント ID（種類: ウェブ アプリケーション）

### 準備

```powershell
npm install
Copy-Item .env.example .env.local
# .env.local の VITE_GOOGLE_CLIENT_ID を実際の値に書き換える
```

> `.env.local` はコミット禁止。
> クライアント ID は **Android 用と同じ GCP プロジェクト**に作ったものを使うこと。
> 別プロジェクトだと AppData 領域が共有されず、エラーも出ずに永久に 0 件になる。

### コマンド

```powershell
npm run dev       # http://localhost:5173
npm run build     # dist/ に静的ファイルを出力
npm run test      # Vitest
```

> 開発機は Windows / PowerShell 5.1。`&&` は使えない。`;` か `if ($?) { ... }` を使う。

### ポートを 5173 から動かさないこと

OAuth クライアントの「承認済みの JavaScript 生成元」に登録したオリジンと一致しないと、
**ログインだけが失敗する。** `vite.config.ts` で `strictPort: true` にしてある。

---

## 実装状況

`npm run test` = **54 件 全通過**、`npm run build` 成功（tsc --noEmit 込み）。

| | 状態 |
|---|---|
| デモ画面 `public/demo.html` | 完成。ヘッドレス Chrome で描画確認済み |
| `src/drive/photo.ts` — 撮影時刻の決定 | 完成（6 テスト） |
| `src/drive/driveClient.ts` — Drive API | 完成（11 テスト） |
| `src/hooks/usePolling.ts` | 完成（5 テスト） |
| `src/hooks/usePhotoObjectUrls.ts` — Blob と revoke | 完成（5 テスト） |
| `src/components/*` | 完成（21 テスト） |
| `src/auth/useGoogleAuth.ts` | 完成。**実クライアント ID での疎通は未確認** |
| `src/App.tsx` / `src/main.tsx` | 完成（4 テスト） |

### まだ通っていないこと

- **実際の Google アカウントでのログイン**（クライアント ID 未発行 → [Q11](../docs/00-openquestion.md)）
- **AppData 領域が Android と Web で共有されること**（→ [Q1](../docs/00-openquestion.md)。ここが崩れると設計ごとやり直し）
- `imageMediaMetadata.time` が AppData でも返るか（→ [Q6](../docs/00-openquestion.md)）

テストは全部フェイクの `fetch` に対して通しているので、
**「実装が正しい」ことは示せても「Drive と会話できる」ことは示せていない。**

### ヘッダーにアカウント名を出していない

[docs/04-frontend.md 9 節](../docs/04-frontend.md) の画面図はヘッダーにメールアドレスを
出しているが、`drive.appdata` スコープだけでは取得できない。
出すなら `userinfo.email` スコープの追加が要る（同意画面の表示も変わる）。
勝手に足していないので、必要なら決めてほしい。

## 旧実装

FastAPI + Jinja2 のサーバーサイド実装は [`../legacy/web-app/`](../legacy/web-app/) にある。
**読んで実装しないこと。** スコープも保存場所も違うので、混ぜると 0 枚問題になる。
