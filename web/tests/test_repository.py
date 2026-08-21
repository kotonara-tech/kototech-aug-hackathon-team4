"""PhotoRepository のテスト（Red フェーズ）.

DB が未定のため、リポジトリ層を挟んで差し替え可能にする。
ここでは実装非依存の「守るべき振る舞い」を固定する。
"""

from datetime import datetime, timezone

import pytest

from app.photo import Photo
from app.repository import InMemoryPhotoRepository, PhotoRepository


def _photo(photo_id: str, iso: str, camera_id: str | None = None) -> Photo:
    return Photo(
        id=photo_id,
        name=f"{photo_id}.jpg",
        mime_type="image/jpeg",
        captured_at=datetime.fromisoformat(iso).replace(tzinfo=timezone.utc),
        camera_id=camera_id,
    )


def test_写真は新しい順に並ぶ():
    """定点観測では最新の様子が最も知りたい情報なので先頭に来る。"""
    repo = InMemoryPhotoRepository(
        [
            _photo("old", "2026-08-18T06:00:00"),
            _photo("newest", "2026-08-20T06:00:00"),
            _photo("middle", "2026-08-19T06:00:00"),
        ]
    )

    result = repo.list_photos()

    assert [p.id for p in result] == ["newest", "middle", "old"]


def test_カメラを指定するとそのカメラの写真だけ返る():
    repo = InMemoryPhotoRepository(
        [
            _photo("a", "2026-08-20T06:00:00", camera_id="第1ハウス"),
            _photo("b", "2026-08-20T07:00:00", camera_id="第2ハウス"),
            _photo("c", "2026-08-20T08:00:00", camera_id="第1ハウス"),
        ]
    )

    result = repo.list_photos(camera_id="第1ハウス")

    assert [p.id for p in result] == ["c", "a"]


def test_件数を制限できる():
    """1日1枚でも1年で365枚になるため、一覧は必ず上限を持つ。"""
    repo = InMemoryPhotoRepository(
        [_photo(f"p{i}", f"2026-08-{i + 1:02d}T06:00:00") for i in range(10)]
    )

    result = repo.list_photos(limit=3)

    assert len(result) == 3
    assert result[0].id == "p9"


def test_写真がなくても空リストを返す():
    """初回ログイン時にエラー画面を出さないため。"""
    repo = InMemoryPhotoRepository([])

    assert repo.list_photos() == []


def test_カメラ一覧を重複なく取得できる():
    """画面の絞り込みプルダウンに使う。"""
    repo = InMemoryPhotoRepository(
        [
            _photo("a", "2026-08-20T06:00:00", camera_id="第1ハウス"),
            _photo("b", "2026-08-20T07:00:00", camera_id="第2ハウス"),
            _photo("c", "2026-08-20T08:00:00", camera_id="第1ハウス"),
            _photo("d", "2026-08-20T09:00:00", camera_id=None),
        ]
    )

    assert repo.list_cameras() == ["第1ハウス", "第2ハウス"]


def test_1枚だけ取得できる():
    repo = InMemoryPhotoRepository([_photo("a", "2026-08-20T06:00:00")])

    assert repo.get_photo("a").id == "a"


def test_存在しない写真はNoneを返す():
    repo = InMemoryPhotoRepository([])

    assert repo.get_photo("missing") is None


def test_インメモリ実装はプロトコルを満たす():
    """後から Firestore 実装等に差し替えられることを保証する。"""
    assert isinstance(InMemoryPhotoRepository([]), PhotoRepository)


def test_写真の中身を取得できる():
    """画面は取得元を問わずバイト列を受け取れる必要がある。"""
    repo = InMemoryPhotoRepository(
        [_photo("a", "2026-08-20T06:00:00")], contents={"a": b"jpeg-bytes"}
    )

    assert repo.get_photo_content("a") == (b"jpeg-bytes", "image/jpeg")


def test_存在しない写真の中身は取得できない():
    repo = InMemoryPhotoRepository([])

    with pytest.raises(LookupError):
        repo.get_photo_content("missing")
