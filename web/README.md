# 定点カメラ写真ビューアー（Web）

農家向け定点カメラアプリの **Web 側**。Android アプリが Google ドライブに保存した
撮影データを、Google アカウントでログインして閲覧します。

## 仕組み

```
[Androidアプリ] ──画像──> [Google ドライブ（ユーザー本人のアカウント）]
                                      ^
                                      │ ユーザー本人の OAuth トークン
[ブラウザ] ──Googleログイン──> [この Web アプリ] ──> 写真一覧
```

サーバーはサービスアカウントの鍵を持ちません。ログインしたユーザー自身の権限で
ドライブを読むため、**他人の写真は原理的に見えません**。

### ドライブのフォルダ構成

DB を使わず、フォルダ構成をそのまま「カメラの設置場所」として扱います。
Android 側はこの構成で保存するだけで済みます。

```
マイドライブ/
└── FarmCameraPOC/         ← 担当 A と一致させる（DRIVE_FOLDER_NAME で変更可）
    ├── 第1ハウス/         ← サブフォルダ名がそのまま設置場所の名前になる
    │   └── IMG_20260820_063000.jpg
    └── 第2ハウス/
        └── IMG_20260820_063000.jpg
```

サブフォルダを作らず `FarmCameraPOC/` 直下に置いても動きます（設置場所なし扱い）。
担当 A と揃えるべき取り決めは [docs/担当A向け連携仕様.md](docs/担当A向け連携仕様.md) にまとめています。

撮影日時は **EXIF の撮影時刻**を優先します。圃場は電波が弱くアップロードが遅れることが
あるため、ドライブへの到着時刻では実態と合わないためです。

## セットアップ

### 1. Google Cloud 側の準備

1. [Google Cloud Console](https://console.cloud.google.com/) でプロジェクトを作成
2. **API とサービス > ライブラリ** から `Google Drive API` を有効化
3. **OAuth 同意画面** を設定
   - ユーザーの種類: 外部
   - スコープに `.../auth/drive.readonly` を追加
   - 公開ステータスが「テスト」の間は、**テストユーザーに利用者のメールアドレスを登録**
4. **認証情報 > OAuth クライアント ID** を作成
   - 種類: ウェブ アプリケーション
   - 承認済みのリダイレクト URI: `http://localhost:8000/auth/callback`

### 2. アプリの設定

```powershell
# 依存パッケージ
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt

# 環境変数
Copy-Item .env.example .env
# .env を開いて GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET / SESSION_SECRET を記入
```

### 3. 起動

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload
```

ブラウザで http://localhost:8000 を開きます。

## テスト

```powershell
.\.venv\Scripts\python.exe -m pytest
```

本プロジェクトは TDD（Red → Green → Refactor）で開発しています。
**テストを書かずに本体コードを追加しないでください。**

## 構成

| ファイル | 役割 |
|---|---|
| `app/photo.py` | 写真のドメインモデル。撮影時刻の決定ロジック |
| `app/repository.py` | 取得元を抽象化する層。一覧の共通ルール |
| `app/drive.py` | Google ドライブから読む実装 |
| `app/auth.py` | Google ログイン（OAuth 2.0） |
| `app/dependencies.py` | 取得元の差し替え口 |
| `app/main.py` | 画面とルーティング |

## DB を導入するとき

Android 側と共有する DB が決まったら、`PhotoRepository` を満たすクラスを 1 つ作り、
`app/dependencies.py` の `get_repository()` が返すものを差し替えるだけで済みます。
画面側の変更は不要です。

```python
class FirestorePhotoRepository:
    def list_photos(self, camera_id=None, limit=None) -> list[Photo]: ...
    def list_cameras(self) -> list[str]: ...
    def get_photo(self, photo_id) -> Photo | None: ...
    def get_photo_content(self, photo_id) -> tuple[bytes, str]: ...
```

## POC 必須 ToDo との対応

| No. | 作業内容 | 状態 |
|---|---|---|
| 1 | Google Cloud、Drive API、OAuth同意画面を準備 | 手順は「セットアップ」に記載（実施は各自） |
| 2 | Android用・Web用OAuthクライアントを作成し担当Aへ共有 | 仕様は `docs/担当A向け連携仕様.md` |
| 3 | Webプロジェクトを作成しPCブラウザで起動 | 実装済み |
| 4 | GoogleログインとDrive参照権限 | 実装済み |
| 5 | `FarmCameraPOC` フォルダー内のJPEG一覧を取得 | 実装済み |
| 6 | 新しい順に並べ、最新画像とサムネイル一覧を表示 | 実装済み |
| 7 | 画像選択による拡大表示と手動更新ボタン | 実装済み |
| 8 | 画像なし・認証失敗・API失敗の簡易メッセージ | 実装済み |

## 現時点の制約

- ログインは Google アカウントがあれば誰でも可能（アクセス制限なし）。
  制限が必要になったら `app/auth.py` に許可リストの判定を追加します。
- アクセストークンは署名付き Cookie に保存しています。HTTPS で運用してください。
- **トークンの自動更新（リフレッシュ）は実装しない方針**です。期限が切れた場合は
  「ログインの有効期限が切れました」と表示してログイン画面に戻します。
- 一覧は最大 `MAX_PHOTOS`（既定 300）枚まで読み込みます。ページ送りはありません。
