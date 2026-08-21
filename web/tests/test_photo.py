"""Photo モデルのテスト（Red フェーズ: 実装より先に書く）."""

from datetime import datetime, timezone, timedelta

import pytest

from app.photo import JST, Photo


def test_exif撮影時刻がある場合はそれを採用する():
    """定点カメラでは「アップロード時刻」ではなく「撮影時刻」が意味を持つ。

    圃場は電波が弱くアップロードが数時間遅れることがあるため、
    EXIF の撮影時刻が取れるならそちらを優先しなければならない。
    """
    drive_file = {
        "id": "file-1",
        "name": "IMG_20260820_063000.jpg",
        "mimeType": "image/jpeg",
        "createdTime": "2026-08-20T09:31:12.000Z",  # アップロードは 18:31 JST
        "imageMediaMetadata": {"time": "2026:08:20 06:30:00"},  # 撮影は 06:30 JST
    }

    photo = Photo.from_drive_file(drive_file)

    # EXIF の時刻はタイムゾーンを持たない現地時刻なので JST として解釈する
    assert photo.captured_at == datetime(2026, 8, 20, 6, 30, 0, tzinfo=JST)


def test_exif撮影時刻がない場合はdriveの作成時刻にフォールバックする():
    drive_file = {
        "id": "file-2",
        "name": "no_exif.jpg",
        "mimeType": "image/jpeg",
        "createdTime": "2026-08-20T09:31:12.000Z",
    }

    photo = Photo.from_drive_file(drive_file)

    assert photo.captured_at == datetime(2026, 8, 20, 9, 31, 12, tzinfo=timezone.utc)


def test_撮影時刻は常にタイムゾーン付きで返る():
    """naive な datetime が混ざると比較・ソートで例外になるため。"""
    drive_file = {
        "id": "file-3",
        "name": "x.jpg",
        "mimeType": "image/jpeg",
        "createdTime": "2026-08-20T09:31:12.000Z",
    }

    photo = Photo.from_drive_file(drive_file)

    assert photo.captured_at.tzinfo is not None


def test_表示用の日時は日本時間の文字列になる():
    """農家の方が読む画面なので UTC のまま出さない。"""
    drive_file = {
        "id": "file-4",
        "name": "x.jpg",
        "mimeType": "image/jpeg",
        "createdTime": "2026-08-20T21:05:00.000Z",  # JST では翌日 06:05
    }

    photo = Photo.from_drive_file(drive_file)

    assert photo.display_datetime() == "2026/08/21 06:05"


def test_日付だけの表示もできる():
    drive_file = {
        "id": "file-5",
        "name": "x.jpg",
        "mimeType": "image/jpeg",
        "createdTime": "2026-08-20T21:05:00.000Z",
    }

    photo = Photo.from_drive_file(drive_file)

    assert photo.display_date() == "2026/08/21"


def test_idがないdriveファイルは受け付けない():
    """id がないと画像を取得できないので、静かに壊れるより早く失敗させる。"""
    with pytest.raises(ValueError):
        Photo.from_drive_file({"name": "x.jpg", "createdTime": "2026-08-20T09:31:12.000Z"})


def test_撮影時刻が全く分からないファイルは受け付けない():
    with pytest.raises(ValueError):
        Photo.from_drive_file({"id": "file-6", "name": "x.jpg"})


def test_カメラ設置場所を保持できる():
    """将来 DB が決まったとき、圃場名をここに載せる。"""
    drive_file = {
        "id": "file-7",
        "name": "x.jpg",
        "mimeType": "image/jpeg",
        "createdTime": "2026-08-20T09:31:12.000Z",
    }

    photo = Photo.from_drive_file(drive_file, camera_id="第1ハウス")

    assert photo.camera_id == "第1ハウス"


def test_カメラ設置場所は省略できる():
    drive_file = {
        "id": "file-8",
        "name": "x.jpg",
        "mimeType": "image/jpeg",
        "createdTime": "2026-08-20T09:31:12.000Z",
    }

    photo = Photo.from_drive_file(drive_file)

    assert photo.camera_id is None


def test_jstは9時間のオフセットである():
    assert JST.utcoffset(None) == timedelta(hours=9)
