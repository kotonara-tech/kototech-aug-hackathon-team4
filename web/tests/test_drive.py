"""DrivePhotoRepository のテスト（Red フェーズ）.

Drive API 本体は叩かず、偽の API 実装を差し込んで振る舞いを固定する。
"""

import pytest

from app.drive import DrivePhotoRepository
from app.repository import PhotoRepository

ROOT = {"id": "root-id", "name": "定点カメラ"}
HOUSE1 = {"id": "house1-id", "name": "第1ハウス"}
HOUSE2 = {"id": "house2-id", "name": "第2ハウス"}


class FakeDriveApi:
    """呼ばれたクエリを記録し、あらかじめ決めた結果を返す偽 API."""

    def __init__(self, folders=None, files=None, contents=None):
        self.folders = folders if folders is not None else [ROOT, HOUSE1, HOUSE2]
        self.files = files or []
        self.contents = contents or {}
        self.queries: list[str] = []

    def list_files(self, query, fields=None, page_size=100):
        self.queries.append(query)
        if "application/vnd.google-apps.folder" in query:
            if "in parents" in query:
                # サブフォルダの問い合わせ
                return [f for f in self.folders if f["id"] != ROOT["id"]]
            # ルートフォルダを名前で探す問い合わせ
            return [f for f in self.folders if f["id"] == ROOT["id"]]
        return self.files

    def download_file(self, file_id):
        if file_id not in self.contents:
            raise KeyError(file_id)
        return self.contents[file_id]


def _file(file_id, created, parent):
    return {
        "id": file_id,
        "name": f"{file_id}.jpg",
        "mimeType": "image/jpeg",
        "createdTime": created,
        "parents": [parent],
    }


def test_drive実装はリポジトリのプロトコルを満たす():
    """画面が実装を意識せずに済むことを保証する。"""
    assert isinstance(DrivePhotoRepository(FakeDriveApi()), PhotoRepository)


def test_フォルダ内の写真を新しい順に取得できる():
    api = FakeDriveApi(
        files=[
            _file("a", "2026-08-18T06:00:00.000Z", HOUSE1["id"]),
            _file("b", "2026-08-20T06:00:00.000Z", HOUSE1["id"]),
        ]
    )

    photos = DrivePhotoRepository(api).list_photos()

    assert [p.id for p in photos] == ["b", "a"]


def test_サブフォルダ名がカメラ設置場所になる():
    """DB がなくても、フォルダ構成だけで圃場を区別できるようにする。"""
    api = FakeDriveApi(
        files=[
            _file("a", "2026-08-20T06:00:00.000Z", HOUSE1["id"]),
            _file("b", "2026-08-20T07:00:00.000Z", HOUSE2["id"]),
        ]
    )

    photos = DrivePhotoRepository(api).list_photos()

    assert {p.id: p.camera_id for p in photos} == {"a": "第1ハウス", "b": "第2ハウス"}


def test_ルート直下の写真は設置場所なしになる():
    """サブフォルダを作らずに使い始めても動くようにする。"""
    api = FakeDriveApi(files=[_file("a", "2026-08-20T06:00:00.000Z", ROOT["id"])])

    photos = DrivePhotoRepository(api).list_photos()

    assert photos[0].camera_id is None


def test_画像ファイルだけを問い合わせる():
    api = FakeDriveApi(files=[])

    DrivePhotoRepository(api).list_photos()

    photo_query = api.queries[-1]
    assert "mimeType contains 'image/'" in photo_query


def test_ゴミ箱のファイルは問い合わせから除外する():
    api = FakeDriveApi(files=[])

    DrivePhotoRepository(api).list_photos()

    assert all("trashed = false" in query for query in api.queries)


def test_対象フォルダが無いときは空リストを返す():
    """初回利用時にまだフォルダが無くても、エラー画面ではなく空の一覧を出す。"""
    api = FakeDriveApi(folders=[])

    repo = DrivePhotoRepository(api)

    assert repo.list_photos() == []
    assert repo.list_cameras() == []


def test_カメラで絞り込める():
    api = FakeDriveApi(
        files=[
            _file("a", "2026-08-20T06:00:00.000Z", HOUSE1["id"]),
            _file("b", "2026-08-20T07:00:00.000Z", HOUSE2["id"]),
        ]
    )

    photos = DrivePhotoRepository(api).list_photos(camera_id="第2ハウス")

    assert [p.id for p in photos] == ["b"]


def test_カメラ一覧はサブフォルダ名から作る():
    api = FakeDriveApi(files=[])

    assert DrivePhotoRepository(api).list_cameras() == ["第1ハウス", "第2ハウス"]


def test_件数を制限できる():
    api = FakeDriveApi(
        files=[
            _file(f"p{i}", f"2026-08-{i + 1:02d}T06:00:00.000Z", HOUSE1["id"])
            for i in range(5)
        ]
    )

    photos = DrivePhotoRepository(api).list_photos(limit=2)

    assert [p.id for p in photos] == ["p4", "p3"]


def test_フォルダ解決の結果は使い回す():
    """1 リクエストで Drive を何度も叩くと表示が遅くなるため。"""
    api = FakeDriveApi(files=[])
    repo = DrivePhotoRepository(api)

    repo.list_photos()
    repo.list_photos()

    folder_queries = [q for q in api.queries if "application/vnd.google-apps.folder" in q]
    assert len(folder_queries) == 2  # ルート検索 1 回 + サブフォルダ検索 1 回


def test_写真の中身を取得できる():
    """Drive の画像は認証が要るため、アプリが代理で取得して配信する。"""
    api = FakeDriveApi(
        files=[_file("a", "2026-08-20T06:00:00.000Z", HOUSE1["id"])],
        contents={"a": b"\xff\xd8\xff-jpeg-bytes"},
    )

    content, mime_type = DrivePhotoRepository(api).get_photo_content("a")

    assert content == b"\xff\xd8\xff-jpeg-bytes"
    assert mime_type == "image/jpeg"


def test_存在しない写真の中身は取得できない():
    api = FakeDriveApi(files=[])

    with pytest.raises(LookupError):
        DrivePhotoRepository(api).get_photo_content("missing")


def test_フォルダ名は設定で変更できる():
    api = FakeDriveApi(folders=[{"id": "root-id", "name": "ハウス監視"}], files=[])

    DrivePhotoRepository(api, root_folder_name="ハウス監視").list_photos()

    assert "name = 'ハウス監視'" in api.queries[0]


def test_フォルダ名のクォートはエスケープされる():
    """名前に ' が入っていてもクエリが壊れないこと（Drive API の構文エラー防止）。"""
    api = FakeDriveApi(folders=[], files=[])

    DrivePhotoRepository(api, root_folder_name="Bob's farm").list_photos()

    assert "name = 'Bob\\'s farm'" in api.queries[0]
