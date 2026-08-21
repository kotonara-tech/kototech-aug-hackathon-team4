"""POC 必須 ToDo（No.6〜8）の振る舞いのテスト（Red フェーズ）.

- No.6 画像を新しい順に並べ、最新画像とサムネイル一覧を表示する
- No.7 画像選択による拡大表示と手動更新ボタンを実装する
- No.8 画像なし、認証失敗、API失敗の簡易メッセージを表示する
"""

import httpx
import pytest

from app.auth import get_current_user, require_user
from app.dependencies import get_repository
from app.main import app
from tests.conftest import TEST_USER


def _login(client, repository):
    app.dependency_overrides[require_user] = lambda: TEST_USER
    app.dependency_overrides[get_current_user] = lambda: TEST_USER
    app.dependency_overrides[get_repository] = lambda: repository
    return client


# --- No.6 最新画像 ----------------------------------------------------


def test_最新の写真が専用の枠で大きく表示される(logged_in):
    """農家が一番知りたいのは「今どうなっているか」なので、
    サムネイルを探させずに最新の 1 枚を最初に見せる。"""
    body = logged_in.get("/").text

    assert "最新の写真" in body
    assert body.index("最新の写真") < body.index('class="grid"')


def test_最新の枠は一番新しい写真を指す(logged_in):
    body = logged_in.get("/").text
    latest_section = body.split("最新の写真")[1].split('class="grid"')[0]

    assert "/photos/newest/image" in latest_section
    assert "/photos/old/image" not in latest_section


def test_絞り込み中はその中の最新が表示される(logged_in):
    body = logged_in.get("/?camera=第1ハウス").text
    latest_section = body.split("最新の写真")[1].split('class="grid"')[0]

    assert "/photos/middle/image" in latest_section


def test_写真が0枚のときは最新の枠を出さない(client, empty_repository):
    body = _login(client, empty_repository).get("/").text

    assert "最新の写真" not in body


# --- No.7 手動更新ボタン ----------------------------------------------


def test_手動更新ボタンがある(logged_in):
    """自動更新は実装しないため、農家が自分で最新を取りに行けるようにする。"""
    body = logged_in.get("/").text

    assert 'class="refresh"' in body
    assert "更新" in body


def test_更新ボタンは絞り込みを保ったまま再読込する(logged_in):
    """「第1ハウスだけ見ていたのに更新したら全部に戻る」を防ぐ。"""
    body = logged_in.get("/?camera=第1ハウス").text

    assert '<input type="hidden" name="camera" value="第1ハウス">' in body


def test_絞り込んでいないときは余計なパラメータを付けない(logged_in):
    body = logged_in.get("/").text

    assert 'name="camera"' not in body.split('class="grid"')[0].split("<form")[-1]


def test_一覧はキャッシュせず毎回取りに行く(logged_in):
    """更新ボタンを押してもブラウザのキャッシュが返っては意味がない。"""
    response = logged_in.get("/")

    assert "no-store" in response.headers.get("cache-control", "")


# --- No.8 API 失敗のメッセージ ----------------------------------------


class BrokenRepository:
    """Drive API が落ちている状態を再現する."""

    def __init__(self, error: Exception):
        self._error = error

    def list_photos(self, camera_id=None, limit=None):
        raise self._error

    def list_cameras(self):
        raise self._error

    def get_photo(self, photo_id):
        raise self._error

    def get_photo_content(self, photo_id):
        raise self._error


def _http_status_error(status_code: int) -> httpx.HTTPStatusError:
    request = httpx.Request("GET", "https://www.googleapis.com/drive/v3/files")
    response = httpx.Response(status_code, request=request)
    return httpx.HTTPStatusError("error", request=request, response=response)


def test_driveに繋がらないときは簡易メッセージを出す(client):
    """スタックトレースではなく、農家が読んで分かる文言を出す。"""
    broken = BrokenRepository(httpx.ConnectError("接続できません"))

    response = _login(client, broken).get("/")

    assert response.status_code == 502
    assert "写真を取得できませんでした" in response.text


def test_driveがエラーを返したときも簡易メッセージを出す(client):
    broken = BrokenRepository(_http_status_error(500))

    response = _login(client, broken).get("/")

    assert response.status_code == 502
    assert "写真を取得できませんでした" in response.text


def test_認証が切れたときはログイン画面に戻す(client):
    """アクセストークンは更新しない方針のため、期限切れは再ログインで解決させる。"""
    broken = BrokenRepository(_http_status_error(401))

    response = _login(client, broken).get("/")

    assert response.status_code in (302, 307)
    assert response.headers["location"] == "/login?error=expired"


def test_権限不足のときもログイン画面に戻す(client):
    broken = BrokenRepository(_http_status_error(403))

    response = _login(client, broken).get("/")

    assert response.status_code in (302, 307)
    assert response.headers["location"] == "/login?error=expired"


def test_認証切れのログイン画面には理由が表示される(client):
    response = client.get("/login?error=expired")

    assert response.status_code == 200
    assert "ログインの有効期限" in response.text


def test_ログイン失敗の理由も表示される(client):
    response = client.get("/login?error=denied")

    assert "キャンセル" in response.text


@pytest.mark.parametrize("path", ["/", "/photos/newest", "/photos/newest/image"])
def test_どの画面でもapi失敗が500にならない(client, path):
    """POC のデモ中に素の 500 画面を出さない。"""
    broken = BrokenRepository(httpx.ConnectError("接続できません"))

    response = _login(client, broken).get(path)

    assert response.status_code != 500
