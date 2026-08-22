# 04. Frontend（Web / React + Vite SPA）設計

Web アプリケーションの設計正本。
Drive とのやりとりの契約は [02-google-drive.md](02-google-drive.md) が正です。**必ず先に読んでください。**

---

## 1. 責務

**「読んで、見せる」だけ。**

| やること | やらないこと |
|---|---|
| Google アカウントでログインする | Drive への書き込み・削除 |
| AppData 領域をポーリングして一覧を取得する | 端末の制御・設定変更 |
| 最新画像とサムネイル一覧を表示する | SSE / WebSocket によるリアルタイム通信 |
| 画像を選んで拡大表示する | トークンの自動更新 |
| ポーリング間隔を変更できるようにする | バックエンド API との通信（**そもそもサーバーがない**） |

---

## 2. サーバーを持たない

**バックエンドは存在しません。** ブラウザから Google Drive API を直接叩きます。

```
[ブラウザ] ──fetch (CORS)──> https://www.googleapis.com/drive/v3/...
```

- BFF なし、プロキシなし、DB なし
- ビルド成果物は**静的ファイルのみ**。任意の静的ホスティングに置けます
- **クライアントシークレットは使えません**（ブラウザに置けないため）

### 帰結: リフレッシュトークンが取れない

シークレットを使えないため、Google Identity Services の**トークンクライアント方式**に
なります。得られるのはアクセストークンのみで、**リフレッシュトークンはありません。**

**アクセストークンは約 1 時間で失効します。**
失効したら再ログインしてもらいます。**トークンは更新しません**（→ 7 節）。

---

## 3. 技術スタック

| 領域 | 採用 |
|---|---|
| フレームワーク | React + TypeScript |
| ビルド | Vite |
| 認証 | Google Identity Services (GIS) — `google.accounts.oauth2.initTokenClient` |
| Drive アクセス | `fetch` で Drive API v3 を直接呼ぶ（CORS 対応済み） |
| テスト | Vitest + React Testing Library |
| 状態管理 | React の `useState` / `useReducer` で足ります。**ライブラリを入れないこと** |

### 入れないもの

- 状態管理ライブラリ（Redux 等）— 状態は「トークン」「写真一覧」「エラー」だけです
- データフェッチライブラリ — ポーリングは `setInterval` 1 つで足ります
- `googleapis` の JS クライアント — REST を直接叩くほうが挙動が読めます
- UI コンポーネントライブラリ — 画面が 1 枚しかありません

**YAGNI を守ってください。**

---

## 4. ディレクトリ構成

```
web/
├── index.html
├── vite.config.ts
├── .env.local                  VITE_GOOGLE_CLIENT_ID（★ コミット禁止）
├── .env.example
└── src/
    ├── main.tsx
    ├── App.tsx                 画面の組み立てのみ
    ├── auth/
    │   └── useGoogleAuth.ts    GIS トークンクライアントの薄いラッパ
    ├── drive/
    │   ├── driveClient.ts      Drive API の呼び出し（listPhotos / fetchPhotoBlob）
    │   └── photo.ts            Photo 型 と resolveCapturedAt()（純粋関数）
    ├── hooks/
    │   └── usePolling.ts       間隔可変のポーリング
    └── components/
        ├── LoginGate.tsx       未ログイン時の画面
        ├── LatestPhoto.tsx     最新写真の大表示
        ├── PhotoGrid.tsx       サムネイル一覧
        ├── PhotoViewer.tsx     拡大表示
        ├── PollControl.tsx     ポーリング間隔の選択 + 手動更新ボタン
        └── ErrorBanner.tsx     エラー表示
```

### 設計方針: 純粋関数を切り出してテストする

`resolveCapturedAt()`（撮影時刻の決定）と、Drive のクエリ組み立ては
**副作用のない関数**として切り出してください。ここがバグの温床であり、
かつネットワークなしでテストできる部分です。

`App.tsx` は組み立てるだけの薄い層に保ってください。

---

## 5. 認証

### 5.1 OAuth クライアント

- 種類: **ウェブ アプリケーション**
- 承認済みの JavaScript 生成元: `http://localhost:5173`
- **クライアントシークレットは使いません**（SPA では意味がないため）
- ★ **Android 用クライアントと同じ GCP プロジェクト内に作ること**
  （→ [02 §2](02-google-drive.md#-最重要-隔離単位は-gcp-プロジェクトであってクライアント-id-ではない)）

クライアント ID は `.env.local` に置きます。

```
VITE_GOOGLE_CLIENT_ID=xxxxxxxx.apps.googleusercontent.com
```

> `VITE_` 接頭辞の環境変数は**バンドルに埋め込まれ、ブラウザから見えます。**
> クライアント ID は公開情報なので問題ありませんが、**シークレットは絶対に入れないこと。**

### 5.2 トークンの取得

```ts
const client = google.accounts.oauth2.initTokenClient({
  client_id: import.meta.env.VITE_GOOGLE_CLIENT_ID,
  scope: 'https://www.googleapis.com/auth/drive.appdata',   // ★ これ以外では見えない
  callback: (response) => { /* response.access_token */ },
});
client.requestAccessToken();
```

★ スコープは `drive.appdata`。**`drive.readonly` では AppData は見えません**
（→ [02 §3](02-google-drive.md#-スコープ)）。

### 5.3 トークンの保持

**メモリのみ**（React state）に保持します。`localStorage` にも `sessionStorage` にも
書きません（→ [Q7](00-openquestion.md)）。

リロードすると再ログインが必要になりますが、デモ中にリロードしなければ困りません。

---

## 6. Drive アクセス

### 6.1 一覧取得

```ts
const params = new URLSearchParams({
  spaces: 'appDataFolder',                                     // ★ 必須
  q: "mimeType contains 'image/' and trashed = false",
  fields: 'nextPageToken,files(id,name,mimeType,size,createdTime,imageMediaMetadata/time)',
  orderBy: 'createdTime desc',
  pageSize: '100',
});
const res = await fetch(`https://www.googleapis.com/drive/v3/files?${params}`, {
  headers: { Authorization: `Bearer ${accessToken}` },
});
```

> 注意: **`spaces=appDataFolder` を忘れるとマイドライブを見に行き、静かに 0 件が返ります。**
> エラーになりません。0 枚問題の第一容疑者です。**テストで固定してください。**

> 注意: `fields` を省略すると `id` と `name` しか返らず、
> **`createdTime` も `imageMediaMetadata` も入りません。**時刻がすべて不明になります。

取得上限は最新 100 件（1 ページのみ）。ページングは実装しません（→ [Q8](00-openquestion.md)）。

### 6.2 画像の実体取得

```ts
const res = await fetch(
  `https://www.googleapis.com/drive/v3/files/${fileId}?alt=media`,
  { headers: { Authorization: `Bearer ${accessToken}` } },
);
const blob = await res.blob();
const objectUrl = URL.createObjectURL(blob);
```

> 注意: **`<img src="https://www.googleapis.com/...">` は表示されません。**
> `Authorization` ヘッダが必要なためです。必ず `fetch` → Blob → `createObjectURL` を通します。

> 注意: **`URL.revokeObjectURL()` を忘れないこと。**
> ポーリングで画像を取り続けるため、解放しないとメモリを食い潰します。
> `useEffect` のクリーンアップで確実に解放してください。

### 6.3 撮影時刻の決定

```ts
export function resolveCapturedAt(file: DriveFile): Date {
  const exif = file.imageMediaMetadata?.time;   // "2026:08:22 06:30:00" 形式
  if (exif) return parseExifAsJst(exif);
  return new Date(file.createdTime);            // RFC 3339 UTC
}
```

| 優先 | ソース | 形式 | タイムゾーン |
|---|---|---|---|
| 1 | `imageMediaMetadata.time` | `yyyy:MM:dd HH:mm:ss` | **なし → JST として解釈する** |
| 2 | `createdTime` | RFC 3339（末尾 `Z`） | UTC |

> 注意: **この 2 つを同じパーサに通さないこと。** 形式もタイムゾーンの扱いも違います。
> `new Date("2026:08:22 06:30:00")` は `Invalid Date` になります。

**なぜ EXIF 優先なのか**: 圃場は電波が弱くアップロードが遅れます。
EXIF が無いと「朝 6 時の写真」が「夕方 18 時」と表示されます
（→ [02 §5](02-google-drive.md#5--撮影時刻の決定)）。

**海外運用の話が出たら、JST 決め打ちのここが最初に壊れます。**

---

## 7. ポーリング

### 方針

**SSE / WebSocket は使いません。ポーリングのみです。**

サーバーがない以上プッシュの受け口が作れず、Drive の変更通知（`changes.watch`）は
受信用の公開エンドポイントを要求するため SPA では成立しません。

**リアルタイム性が必要なら、間隔を短くして殴ります。** 力技であることは承知の上です。

### 実装

`setInterval` で `listPhotos()` を呼び、結果を state に入れるだけです。

| 項目 | 値 |
|---|---|
| 既定間隔 | 30 秒（暫定 → [Q4](00-openquestion.md)） |
| 選択肢 | 10 秒 / 30 秒 / 1 分 / 5 分 |
| 下限 | 10 秒（レート制限に当たるため、これ以下は許可しない） |

### 注意

- **前回のリクエストが終わる前に次を撃たないこと。** 実行中フラグで抑止します
- **手動更新ボタンを必ず置くこと。** デモで「今すぐ反映してほしい」場面に効きます
- ポーリングでエラーが出ても**ループを止めないこと**（401 を除く。次項）
- タブが非表示のときに止めるかどうかは自由。止めるなら `visibilitychange` を使います

---

## 8. エラーハンドリング

| ステータス | 画面の挙動 |
|---|---|
| `401` | **ポーリングを止め**、「ログインの有効期限が切れました」を出してログイン画面へ戻す |
| `403`（スコープ不足） | 同上 |
| `403` / `429`（レート制限） | ポーリング間隔を延ばす。エラーバナーを出す。止めない |
| `404`（個別ファイル） | そのファイルを一覧から除外して継続。**画面全体を落とさない** |
| `5xx` | エラーバナーを出す。次のポーリングで自然に回復する。即時リトライしない |
| 写真 0 件 | 空リストではなく「まだ写真がありません」と説明文を出す |

### ★ トークンは更新しない

リフレッシュトークンがないため、**失効したトークンで叩き続けても復活しません。**
黙って再試行するループを書かないでください。**再ログイン導線へ落とすのが正しい挙動です。**

### ★ 1 枚の失敗で全体を落とさない

撮影時刻が読めない、あるいは実体が取れない画像が 1 枚あったせいで
画面が真っ白になる、という壊れ方をしないでください。
問題のある個体は一覧から外して、残りを表示します。

---

## 9. 画面

**1 画面。** ルーティングは不要です。

```
┌──────────────────────────────────────────────┐
│  定点観測ビューアー         user@example.com   │
├──────────────────────────────────────────────┤
│  更新間隔 [30秒 ▾]   [ 今すぐ更新 ]  最終取得 06:35│
├──────────────────────────────────────────────┤
│                                              │
│         ┌────────────────────────┐            │
│         │                        │            │
│         │      最新の写真          │            │
│         │                        │            │
│         └────────────────────────┘            │
│           2026-08-22 06:30:00 撮影             │
├──────────────────────────────────────────────┤
│  [img] [img] [img] [img] [img] [img] ...      │
│   ← クリックで拡大表示                          │
└──────────────────────────────────────────────┘
```

- 未ログイン時は `LoginGate` のみを出します
- 撮影時刻は**撮影時刻**として表示します（アップロード時刻ではない）。
  EXIF が無くフォールバックした場合は、その旨が分かる表示にしてください
- レスポンシブ対応をしてください。旧実装は縦積みのみで横向きに崩れました

---

## 10. テスト方針

TDD（Red → Green → Refactor）。**テストを書かずに本体コードを追加しないこと。**
テスト名は日本語で「何を保証するか」を書きます。

### 必ず書くテスト

| 対象 | 保証すること |
|---|---|
| `resolveCapturedAt` | EXIF があれば EXIF を使う |
| `resolveCapturedAt` | EXIF がなければ `createdTime` を使う |
| `resolveCapturedAt` | EXIF の `yyyy:MM:dd HH:mm:ss` を JST として解釈する |
| `resolveCapturedAt` | `createdTime` を UTC として解釈する |
| クエリ組み立て | **`spaces=appDataFolder` が必ず含まれる** |
| クエリ組み立て | `fields` に `imageMediaMetadata/time` と `createdTime` が含まれる |
| `driveClient` | `Authorization: Bearer` ヘッダが付く |
| `driveClient` | 401 で再ログイン用のエラー型を返す |
| `driveClient` | 個別ファイルの 404 が一覧全体を落とさない |
| `usePolling` | 間隔どおりに発火する / 停止できる |
| `usePolling` | 前回のリクエストが終わるまで次を撃たない |
| 一覧表示 | 0 件のときに説明文が出る |

### 禁止

**外部 API を叩くテストを書かないこと。** `fetch` はフェイクに差し替えます。
ネットワークに依存するテストは、当日必ず落ちます。

---

## 11. 開発コマンド

```bash
npm install
npm run dev       # http://localhost:5173
npm run build     # 静的ファイルを dist/ に出力
npm run test
```

> 開発機は **Windows / PowerShell 5.1** です。`&&` は使えません。
> `;` か `if ($?) { ... }` を使ってください。

> **`.env.local` は絶対にコミットしないこと。**

---

## 12. 実装時の注意

- **`spaces=appDataFolder` の付け忘れは静かに失敗します。**
  エラーではなく 0 件が返るため「実装が動いていない」ように見えます。
  0 枚問題を踏んだら真っ先にここを見てください
- **`revokeObjectURL` を忘れるとメモリリークします。**
  ポーリングで画像を取り続ける設計なので、通常の SPA より露呈が早いです
- **EXIF と `createdTime` を同じパーサに通さないこと**（6.3）
- **JST 決め打ちである**ことを忘れないでください
- **状態管理ライブラリを入れないこと。**状態は 3 つしかありません
- 旧 `web/app/`（FastAPI + Jinja2）は `legacy/web-app/` へ退避済みです。
  削除タイミングは未決（→ [Q2](00-openquestion.md)）
