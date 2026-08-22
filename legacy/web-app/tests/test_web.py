"""画面と認証の振る舞いのテスト（Red フェーズ）."""

from app.auth import get_current_user, require_user
from app.dependencies import get_repository
from app.main import app
from tests.conftest import JPEG_BYTES, TEST_USER


# --- ログインしていないとき -------------------------------------------


def test_未ログインで一覧を開くとログイン画面に誘導される(client):
    """農地の様子は個人の営農情報なので、ログインなしでは見せない。"""
    response = client.get("/")

    assert response.status_code in (302, 307)
    assert response.headers["location"] == "/login"


def test_ログイン画面はgoogleログインの導線を持つ(client):
    response = client.get("/login")

    assert response.status_code == 200
    assert "/auth/login" in response.text


def test_未ログインでは画像そのものも取得できない(client):
    """一覧を隠しても画像 URL を直叩きされたら意味がないため。"""
    response = client.get("/photos/newest/image")

    assert response.status_code in (302, 307)


# --- ログインしているとき ---------------------------------------------


def test_一覧に写真が新しい順に並ぶ(logged_in):
    response = logged_in.get("/")

    assert response.status_code == 200
    body = response.text
    assert body.index("/photos/newest/image") < body.index("/photos/middle/image")
    assert body.index("/photos/middle/image") < body.index("/photos/old/image")


def test_一覧に撮影日時が日本時間で表示される(logged_in):
    response = logged_in.get("/")

    # 2026-08-20T21:00:00Z は JST では翌日 06:00
    assert "2026/08/21 06:00" in response.text


def test_ログイン中のユーザーが画面に表示される(logged_in):
    response = logged_in.get("/")

    assert TEST_USER.email in response.text


def test_カメラで絞り込める(logged_in):
    response = logged_in.get("/?camera=第1ハウス")

    assert response.status_code == 200
    assert "/photos/middle/image" in response.text
    assert "/photos/newest/image" not in response.text


def test_絞り込み用にカメラ設置場所の一覧が出る(logged_in):
    response = logged_in.get("/")

    assert "第1ハウス" in response.text
    assert "第2ハウス" in response.text


def test_写真が0枚でもエラーにならず案内が出る(client, empty_repository):
    """使い始めたばかりの農家がエラー画面に当たらないようにする。"""
    app.dependency_overrides[require_user] = lambda: TEST_USER
    app.dependency_overrides[get_current_user] = lambda: TEST_USER
    app.dependency_overrides[get_repository] = lambda: empty_repository

    response = client.get("/")

    assert response.status_code == 200
    assert "まだ写真がありません" in response.text


# --- 画像の配信 -------------------------------------------------------


def test_画像を取得できる(logged_in):
    response = logged_in.get("/photos/newest/image")

    assert response.status_code == 200
    assert response.content == JPEG_BYTES
    assert response.headers["content-type"].startswith("image/jpeg")


def test_存在しない写真は404になる(logged_in):
    response = logged_in.get("/photos/nonexistent/image")

    assert response.status_code == 404


def test_画像はブラウザにキャッシュさせる(logged_in):
    """定点写真は後から変わらないため、毎回 Drive を叩かせない。"""
    response = logged_in.get("/photos/newest/image")

    assert "max-age" in response.headers.get("cache-control", "")


# --- 個別ページ -------------------------------------------------------


def test_写真の個別ページを開ける(logged_in):
    response = logged_in.get("/photos/newest")

    assert response.status_code == 200
    assert "/photos/newest/image" in response.text
    assert "2026/08/21 06:00" in response.text


def test_存在しない写真の個別ページは404(logged_in):
    response = logged_in.get("/photos/nonexistent")

    assert response.status_code == 404


# --- ログアウト -------------------------------------------------------


def test_ログアウトするとログイン画面に戻る(logged_in):
    response = logged_in.get("/logout")

    assert response.status_code in (302, 307)
    assert response.headers["location"] == "/login"


# --- OAuth の入口 -----------------------------------------------------


def test_googleの認可画面にリダイレクトする(client):
    """ネットワークに出ずに、行き先だけを検証する。"""
    response = client.get("/auth/login")

    assert response.status_code in (302, 307)
    assert response.headers["location"].startswith(
        "https://accounts.google.com/o/oauth2/v2/auth"
    )


def test_driveの読み取り権限を要求する(client):
    """画像を読むには drive.readonly スコープが要る。"""
    response = client.get("/auth/login")

    assert "drive.readonly" in response.headers["location"]
