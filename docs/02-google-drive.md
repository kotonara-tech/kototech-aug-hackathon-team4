# 02. Google Drive AppData 領域 設計

**この文書は Native と Web が共通して守る契約層です。**

ここに書かれた取り決めが 1 つでもずれると、実装が完璧でも
**Web に 1 枚も表示されません**。しかも多くの場合、**エラーが出ずに 0 件が返るだけ**です。

「0 枚問題」を踏んだら、この文書を上から順に確認してください。

---

## 1. AppData 領域とは

Google Drive の各ユーザーのアカウント内にある**アプリ専用の隠し領域**です。

| 特性 | 内容 |
|---|---|
| 可視性 | **drive.google.com には表示されない。** ユーザーは中身を閲覧できない |
| 到達手段 | `drive.appdata` スコープを持つ API 呼び出しのみ |
| 隔離単位 | **アプリ（GCP プロジェクト）× ユーザー** |
| 容量 | **ユーザーの Drive 容量を消費する**（無料枠 15GB） |
| 削除手段 | Drive 設定 > アプリの管理（全消し）、または API の `files.delete` |
| 参照名 | 予約 ID `appDataFolder` を親 ID / spaces として使う |

### なぜこれを選んだか

- ユーザーのマイドライブを汚さない
- ユーザーが誤って削除・移動できない
- 「1 アカウント = 1 端末 = 1 Web クライアント」契約と相性が良い

### 代償

**目視でデバッグできません。** アップロードできているかを確かめるには API 経由の
確認手段が必須です（7 節）。デバッグ時間の大半はここで溶けます。**先に用意してください。**

---

## 2. ★ 最重要: 隔離単位は「GCP プロジェクト」であって「クライアント ID」ではない

**これが本システム最大の落とし穴であり、成立条件そのものです。**

AppData 領域は「その領域を作ったアプリ」だけがアクセスできます。
ここでの「アプリ」の単位は **GCP プロジェクト**です。OAuth クライアント ID 単位ではありません。

```
［正］ GCP プロジェクト「farmcamera」     ← この単位で AppData が決まる
       ├── OAuth クライアント（Android 用）  ─┐
       └── OAuth クライアント（ウェブ用）    ─┴→ 同じ appDataFolder を見る

［誤］ GCP プロジェクト A              GCP プロジェクト B
       └── Android クライアント        └── Web クライアント
               │                              │
               ▼                              ▼
          appDataFolder(A)               appDataFolder(B)   ← 別物
               └─ 画像あり                    └─ 空。0 件が返る
```

### したがって

- **Android 用と Web 用の OAuth クライアント ID は、必ず同じ GCP プロジェクト内に作る。**
- 別プロジェクトに分けると、**永久に共有されません。エラーも出ず、ただ 0 件が返ります。**

### 未検証である

**この挙動をこのプロジェクトで実地検証していません**（→ [Q1](00-openquestion.md)）。
7 節の疎通検証を、他の実装より先に通してください。

---

## 3. ★ スコープ

| 側 | スコープ |
|---|---|
| Android | `https://www.googleapis.com/auth/drive.appdata` |
| Web | `https://www.googleapis.com/auth/drive.appdata` |

### `drive.readonly` では AppData は見えません

権限が「広い」から見える、という話ではありません。
**AppData は専用スコープでしか到達できない別空間**です。
`drive`（フルアクセス）でも AppData には届きません。

### 非機微スコープである

`drive.appdata` は Google の分類上**非機微（non-sensitive）スコープ**です。

- 公開ステータスが「テスト」の間は、テストユーザー登録だけで動きます
- このスコープだけであれば、本番化に際してスコープ利用理由・デモ動画・CASA 監査を伴う OAuth スコープ審査は不要です
- 将来 sensitive / restricted スコープを追加する場合は、公開条件を改めて確認します

本番の Web OAuth は、スコープ審査とは別に、公開ホームページ・プライバシーポリシー・所有を確認できるドメインなどの一般的な OAuth 運用要件を満たす必要があります。これは M2 の検証ゲートには含めません。

---

## 4. ★ ファイル配置

### 4.1 フラット配置。フォルダは切らない

```
appDataFolder/
├── CAM001_20260822_063000.jpg
├── CAM001_20260822_063500.jpg
└── CAM001_20260822_064000.jpg
```

サブフォルダは作りません。

**理由**: 1 端末契約なのでカメラを区別する必要がなく、フォルダ ID を解決するための
API 往復が消えます。

> 旧設計にあった「サブフォルダ名＝カメラ設置場所」は、複数台前提の名残りです。**採用しません。**

### 4.2 ファイル名規約

```
CAM001_yyyyMMdd_HHmmss.jpg
```

| 要素 | 内容 |
|---|---|
| `CAM001` | 端末 ID プレースホルダー。1 端末契約なので**固定値** |
| `yyyyMMdd_HHmmss` | **撮影時点のローカル時刻（JST）**。秒精度 |
| `.jpg` | 拡張子 |

例: `CAM001_20260822_063000.jpg`

- 撮影間隔は最短でも数分なので、同一秒の衝突は起きません
- **Web 側はファイル名を表示にしか使いません。**時刻判定には使いません（5 節）
- ただし人間がデバッグで読むので、規約は守ってください

### 4.3 MIME タイプ

`image/jpeg` で保存します。アップロード時のメタデータにも明示します。

---

## 5. ★ 撮影時刻の決定

Web 側は次の優先順で撮影日時を決めます。

1. **EXIF の撮影日時** — Drive の `imageMediaMetadata.time`
2. なければ **`createdTime`** — Drive への到着時刻

### なぜ EXIF 優先なのか

圃場は電波が弱く、アップロードが遅れます。
EXIF が無いと **「朝 6 時の写真」が「夕方 18 時」と表示されます。**

### Native 側の義務

**CameraX の出力から EXIF を落とさないでください。**
JPEG を再エンコードしたり、バイト列を加工したりすると EXIF が消えます。

### タイムゾーンの扱い

EXIF の日時は**タイムゾーン情報を持たない現地時刻**です。
**Web 側は JST として解釈します。** 海外運用の話が出たら、ここが最初に壊れます。

### 未検証である

`imageMediaMetadata.time` が AppData 領域でも埋まるかは未確認です
（→ [Q6](00-openquestion.md)）。7 節の疎通検証で同時に確認してください。

---

## 6. API 契約

エンドポイントは Google Drive API v3。すべて `Authorization: Bearer {accessToken}` が必要です。

### 6.1 アップロード（Native のみ）

```http
POST https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart
Authorization: Bearer {accessToken}
Content-Type: multipart/related; boundary=BOUNDARY

--BOUNDARY
Content-Type: application/json; charset=UTF-8

{"name":"CAM001_20260822_063000.jpg","parents":["appDataFolder"],"mimeType":"image/jpeg"}
--BOUNDARY
Content-Type: image/jpeg

<JPEG のバイト列>
--BOUNDARY--
```

レスポンス `200`:
```json
{ "kind": "drive#file", "id": "1AbC...", "name": "CAM001_20260822_063000.jpg", "mimeType": "image/jpeg" }
```

> 注意: **`"parents": ["appDataFolder"]` を忘れると、マイドライブ直下に落ちます。**
> エラーになりません。**ユーザーのドライブを汚した上で Web からは見えない**という
> 最悪の状態になります。ここは必ずテストで固定してください。

> 注意: URL は `www.googleapis.com/**upload**/drive/v3/files` です。
> `upload/` が無いとメタデータだけの空ファイルが作られます。

### 6.2 一覧取得（Web のみ）

```http
GET https://www.googleapis.com/drive/v3/files
  ?spaces=appDataFolder
  &q=mimeType contains 'image/' and trashed = false
  &fields=nextPageToken,files(id,name,mimeType,size,createdTime,imageMediaMetadata/time)
  &orderBy=createdTime desc
  &pageSize=100
Authorization: Bearer {accessToken}
```

レスポンス `200`:
```json
{
  "files": [
    {
      "id": "1AbC...",
      "name": "CAM001_20260822_063000.jpg",
      "mimeType": "image/jpeg",
      "size": "2097152",
      "createdTime": "2026-08-22T00:35:12.000Z",
      "imageMediaMetadata": { "time": "2026:08:22 06:30:00" }
    }
  ]
}
```

| パラメータ | 必須 | 注意 |
|---|---|---|
| `spaces=appDataFolder` | ★必須 | **忘れるとマイドライブを見に行き、静かに 0 件が返ります。**0 枚問題の第一容疑者 |
| `fields` | 実質必須 | 省略すると `id` と `name` しか返らず、`imageMediaMetadata` も `createdTime` も入りません |
| `orderBy=createdTime desc` | 推奨 | 最新側から取れるので、`pageSize` で打ち切っても最新が残ります |
| `pageSize` | 推奨 | 最大 1000。既定 100（→ [Q8](00-openquestion.md)） |

> `imageMediaMetadata.time` の形式は `yyyy:MM:dd HH:mm:ss`（EXIF 生の形式）です。
> ISO 8601 ではありません。**タイムゾーンを持ちません。**
> 一方 `createdTime` は RFC 3339 の UTC（末尾 `Z`）です。**両者を同じパーサに通さないこと。**

### 6.3 画像の実体取得（Web のみ）

```http
GET https://www.googleapis.com/drive/v3/files/{fileId}?alt=media
Authorization: Bearer {accessToken}
```

レスポンス `200`: JPEG のバイト列

> 注意: **`<img src="...">` にこの URL を直接入れても表示されません。**
> `Authorization` ヘッダが必要なためです。`fetch` で Blob を取得し、
> `URL.createObjectURL()` で表示してください（`04-frontend.md` 参照）。

### 6.4 削除（現時点では実装しない）

```http
DELETE https://www.googleapis.com/drive/v3/files/{fileId}
Authorization: Bearer {accessToken}
```

現設計に削除機構はありません（→ [Q5](00-openquestion.md)）。
API 契約としてだけ記載しておきます。

### 6.5 CORS

**Drive API は CORS に対応しています。** ブラウザから直接 `fetch` できます。
プロキシサーバーは不要です。これが「サーバーを持たない SPA」を成立させています。

---

## 7. ★ 疎通検証（他の実装より先にやる）

**この検証が通るまで、Native と Web の実装に人手を割かないでください。**
ここが崩れると、書いたコードは全部無駄になります。

### 準備: AppData を覗く手段

drive.google.com では見えないため、**OAuth 2.0 Playground** を使います。

1. https://developers.google.com/oauthplayground を開く
2. 右上の歯車 > 「Use your own OAuth credentials」にチェック
3. **本プロジェクトの Web 用クライアント ID / シークレット**を入力
   （※ GCP 側で Playground のリダイレクト URI
   `https://developers.google.com/oauthplayground` を承認済みにしておくこと）
4. 左のスコープ入力欄に `https://www.googleapis.com/auth/drive.appdata` を手入力
5. Authorize → デモ用アカウントで同意
6. Exchange authorization code for tokens

### 検証手順

| # | 操作 | 期待 |
|---|---|---|
| 1 | Playground で `GET https://www.googleapis.com/drive/v3/files?spaces=appDataFolder` | `{"files": []}`（空）。**401/403 ならスコープか同意画面の問題** |
| 2 | Android アプリから 1 枚アップロード | `200` とファイル ID が返る |
| 3 | Playground で再度 1 の GET | **アップロードしたファイルが 1 件見える** |
| 4 | `fields` を付けて再取得し `imageMediaMetadata.time` を確認 | EXIF 由来の時刻が入っている（→ [Q6](00-openquestion.md)） |
| 5 | Web SPA から**同一アカウント**でログインし一覧取得 | **同じファイルが見える** |

**5 が通れば設計は成立します。**

### 5 が通らないときに疑う順序

```
1. Android 用と Web 用のクライアント ID が別 GCP プロジェクトにある   ← 2 節。最有力
2. Android と Web で別の Google アカウントを使っている              ← 8 節
3. Web 側のスコープが drive.appdata になっていない                  ← 3 節
4. Web 側のリクエストに spaces=appDataFolder が付いていない          ← 6.2
5. Android 側の parents に "appDataFolder" が入っていない            ← 6.1
   （この場合、マイドライブ直下にファイルが落ちているはず。目視で確認できる）
6. ファイルがゴミ箱にある（q の trashed = false に引っかかっている）
```

---

## 8. ★ アカウントの一致

AppData 領域はユーザーごとに隔離されます。
**Android と Web は同じ Google アカウントでログインしてください。**
別アカウントで試すと、実装が正しくても 0 枚です。

---

## 9. エラーハンドリング契約

| ステータス | 意味 | 両側の扱い |
|---|---|---|
| `401` | アクセストークンが無効・失効 | **再ログインへ誘導する。**黙って再試行しない |
| `403` | スコープ不足、またはレート制限（`reason` を見る） | スコープ不足なら再ログイン。レート制限ならポーリング間隔を延ばす |
| `404` | ファイルが存在しない、または**自アプリのファイルではない** | 一覧から除外して継続。画面全体を落とさない |
| `429` | レート制限 | ポーリング間隔を延ばす。指数バックオフ |
| `5xx` | Drive 側の一時障害 | 次のサイクルで再試行。即時リトライしない |

### 共通方針

- **トークンは更新しません。**失効したトークンで叩き続けても復活しません
- **無限リトライループを書かないでください。**次のポーリング／撮影サイクルに任せます
- 個別ファイルの失敗で**一覧全体を落とさない**でください
  （撮影時刻が読めない 1 枚のために画面が真っ白になる、という壊れ方をします）

---

## 10. 実装時に必ず踏む地雷（まとめ）

**いずれもエラーが出ず、0 件が返るか静かに間違った場所に書き込むだけです。**

| # | 地雷 | 症状 | 対策 |
|---|---|---|---|
| 1 | Native と Web が別 GCP プロジェクト | Web が永久に 0 件 | 2 節。認証情報画面で両方が並んでいることを目視 |
| 2 | Web が `spaces=appDataFolder` 未指定 | マイドライブを見に行って 0 件 | 6.2。リクエスト組み立てをテストで固定 |
| 3 | Native が `parents: ["appDataFolder"]` 未指定 | マイドライブ直下に落ちる。Web から見えない | 6.1。リクエストボディをテストで固定 |
| 4 | Web のスコープが `drive.readonly` | 0 件、または 403 | 3 節 |
| 5 | アップロード URL に `upload/` がない | 空ファイルが作られる | 6.1 |
| 6 | `fields` 未指定 | `createdTime` も EXIF も返らず、時刻がすべて不明になる | 6.2 |
| 7 | 別アカウントでログイン | 0 件 | 8 節 |
| 8 | `imageMediaMetadata.time` を ISO 8601 としてパース | 時刻が壊れる、または例外 | 5 節。形式は `yyyy:MM:dd HH:mm:ss` |
