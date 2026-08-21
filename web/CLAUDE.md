# CLAUDE.md — 定点カメラ写真ビューアー（Web）

ホームディレクトリの `~/.claude/CLAUDE.md`（TDD グランドルール）が優先される。
ここにはこのプロジェクト固有の情報だけを書く。

## テスト実行コマンド

```powershell
.\.venv\Scripts\python.exe -m pytest          # 全件
.\.venv\Scripts\python.exe -m pytest tests/test_drive.py -v
```

Windows PowerShell 5.1 のため `&&` は使えない。`;` または `if ($?) { ... }` を使う。

## 起動

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload
```

## 設計の要点

- **DB は未定**。`app/repository.py` の `PhotoRepository` Protocol にだけ画面が依存する。
  取得元を変えるときは `app/dependencies.py` の `get_repository()` を差し替える。
- **Drive アクセスはユーザー本人の OAuth**。サーバーは鍵を持たない。
- **ルートフォルダ名は `FarmCameraPOC`**。担当 A（Android）と一致必須。
  ここを変えるときは `docs/担当A向け連携仕様.md` も必ず更新する。
- **カメラ設置場所は Drive のサブフォルダ名**。DB なしで圃場を区別するための約束。
- **アクセストークンは更新しない**方針。401/403 は `/login?error=expired` に戻す。
- **撮影時刻は EXIF 優先**。圃場は電波が弱くアップロードが遅れるため、
  Drive の `createdTime` では実態と合わない。

## テストの書き方

- Drive API は叩かない。`tests/test_drive.py` の `FakeDriveApi` を使う。
- Web 層は `tests/conftest.py` の `logged_in` fixture で
  `require_user` / `get_repository` を差し替える。
- テスト名は日本語で「何を保証するか」を書く。

## 触るときの注意

- `app/photo.py` の撮影時刻ロジックは EXIF の現地時刻を JST として解釈している。
  海外運用の話が出たら、ここが最初に壊れる。
- `.env` は絶対にコミットしない。
