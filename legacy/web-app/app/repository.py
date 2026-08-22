"""写真の取得口を抽象化する層.

Android 側と共有する DB がまだ決まっていないため、画面はこの Protocol にだけ依存する。
Drive を直接読む実装で今すぐ動かし、DB が決まったら実装を差し替える。
"""

from __future__ import annotations

from typing import Iterable, Protocol, runtime_checkable

from app.photo import Photo


@runtime_checkable
class PhotoRepository(Protocol):
    """写真の一覧・単票を返せるもの."""

    def list_photos(
        self, camera_id: str | None = None, limit: int | None = None
    ) -> list[Photo]: ...

    def list_cameras(self) -> list[str]: ...

    def get_photo(self, photo_id: str) -> Photo | None: ...

    def get_photo_content(self, photo_id: str) -> tuple[bytes, str]: ...


def select_photos(
    photos: Iterable[Photo], camera_id: str | None = None, limit: int | None = None
) -> list[Photo]:
    """一覧の共通ルール: カメラで絞り、新しい順に並べ、件数を制限する.

    取得元（Drive / DB）が変わっても一覧の見え方が揃うよう、ここに集約する。
    """
    selected = [
        photo
        for photo in photos
        if camera_id is None or photo.camera_id == camera_id
    ]
    selected.sort(key=lambda photo: photo.captured_at, reverse=True)
    return selected[:limit] if limit is not None else selected


class InMemoryPhotoRepository:
    """テストと開発用の実装."""

    def __init__(
        self, photos: Iterable[Photo], contents: dict[str, bytes] | None = None
    ) -> None:
        self._photos = list(photos)
        self._contents = contents or {}

    def list_photos(
        self, camera_id: str | None = None, limit: int | None = None
    ) -> list[Photo]:
        return select_photos(self._photos, camera_id, limit)

    def list_cameras(self) -> list[str]:
        return sorted({photo.camera_id for photo in self._photos if photo.camera_id})

    def get_photo(self, photo_id: str) -> Photo | None:
        return next((photo for photo in self._photos if photo.id == photo_id), None)

    def get_photo_content(self, photo_id: str) -> tuple[bytes, str]:
        photo = self.get_photo(photo_id)
        if photo is None:
            raise LookupError(f"写真が見つかりません: {photo_id}")
        return self._contents.get(photo_id, b""), photo.mime_type
