"""Google Drive を写真の取得元として使う実装.

ユーザー本人の OAuth トークンで Drive を読むため、サーバーは鍵を持たない。
"""

from __future__ import annotations

from typing import Any, Protocol

import httpx

from app.photo import Photo
from app.repository import select_photos

DRIVE_FILES_URL = "https://www.googleapis.com/drive/v3/files"
FOLDER_MIME = "application/vnd.google-apps.folder"

_FOLDER_FIELDS = "nextPageToken, files(id, name)"
_PHOTO_FIELDS = (
    "nextPageToken, "
    "files(id, name, mimeType, createdTime, parents, imageMediaMetadata/time)"
)

# 担当 A の Android アプリが書き込むフォルダ名と一致させること。
# ここがずれると、結合したときに 1 枚も表示されない。
DEFAULT_ROOT_FOLDER_NAME = "FarmCameraPOC"


def escape_query_value(value: str) -> str:
    """Drive のクエリ文字列に安全に埋め込めるようにする.

    圃場名に ' が含まれるとクエリ構文が壊れるため、必ず通す。
    """
    return value.replace("\\", "\\\\").replace("'", "\\'")


class DriveApi(Protocol):
    """Drive API のうち、このアプリが必要とする操作だけ."""

    def list_files(
        self, query: str, fields: str | None = None, page_size: int = 100
    ) -> list[dict[str, Any]]: ...

    def download_file(self, file_id: str) -> bytes: ...


class GoogleDriveApi:
    """実際の Drive REST API を叩く実装."""

    def __init__(self, access_token: str, timeout: float = 15.0) -> None:
        self._access_token = access_token
        self._timeout = timeout

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._access_token}"}

    def list_files(
        self, query: str, fields: str | None = None, page_size: int = 100
    ) -> list[dict[str, Any]]:
        files: list[dict[str, Any]] = []
        page_token: str | None = None

        with httpx.Client(timeout=self._timeout) as client:
            while True:
                params = {
                    "q": query,
                    "fields": fields or _PHOTO_FIELDS,
                    "pageSize": page_size,
                    "orderBy": "createdTime desc",
                    # 共有ドライブに置かれても読めるようにしておく
                    "supportsAllDrives": "true",
                    "includeItemsFromAllDrives": "true",
                }
                if page_token:
                    params["pageToken"] = page_token

                response = client.get(DRIVE_FILES_URL, params=params, headers=self._headers())
                response.raise_for_status()
                payload = response.json()

                files.extend(payload.get("files", []))
                page_token = payload.get("nextPageToken")
                if not page_token or len(files) >= page_size:
                    break

        return files

    def download_file(self, file_id: str) -> bytes:
        with httpx.Client(timeout=self._timeout) as client:
            response = client.get(
                f"{DRIVE_FILES_URL}/{file_id}",
                params={"alt": "media", "supportsAllDrives": "true"},
                headers=self._headers(),
            )
            response.raise_for_status()
            return response.content


class DrivePhotoRepository:
    """Drive のフォルダ構成をそのまま「カメラ設置場所」として扱う.

        マイドライブ/定点カメラ/第1ハウス/IMG_xxx.jpg
                     ^ルート    ^カメラ設置場所

    DB が決まるまでの実装だが、Android 側がフォルダに置くだけで成立するため
    そのまま本番でも使える。
    """

    def __init__(
        self,
        api: DriveApi,
        root_folder_name: str = DEFAULT_ROOT_FOLDER_NAME,
        max_photos: int = 300,
    ) -> None:
        self._api = api
        self._root_folder_name = root_folder_name
        self._max_photos = max_photos
        self._resolved = False
        self._root_id: str | None = None
        self._camera_names: dict[str, str] = {}

    # --- PhotoRepository の実装 ---------------------------------------

    def list_photos(
        self, camera_id: str | None = None, limit: int | None = None
    ) -> list[Photo]:
        return select_photos(self._load_photos(), camera_id, limit)

    def list_cameras(self) -> list[str]:
        self._resolve_folders()
        return sorted(set(self._camera_names.values()))

    def get_photo(self, photo_id: str) -> Photo | None:
        return next((p for p in self._load_photos() if p.id == photo_id), None)

    # --- 画像の実体 ---------------------------------------------------

    def get_photo_content(self, photo_id: str) -> tuple[bytes, str]:
        """画像のバイト列と MIME タイプを返す.

        Drive の画像 URL は認証が必要で <img> から直接は読めないため、
        アプリが代理で取得して配信する。
        """
        photo = self.get_photo(photo_id)
        if photo is None:
            raise LookupError(f"写真が見つかりません: {photo_id}")
        return self._api.download_file(photo.id), photo.mime_type

    # --- 内部 ---------------------------------------------------------

    def _resolve_folders(self) -> None:
        """ルートフォルダとサブフォルダを 1 回だけ解決する."""
        if self._resolved:
            return
        self._resolved = True

        name = escape_query_value(self._root_folder_name)
        found = self._api.list_files(
            f"name = '{name}' and mimeType = '{FOLDER_MIME}' and trashed = false",
            fields=_FOLDER_FIELDS,
        )
        if not found:
            # まだフォルダを作っていないだけなので、エラーにはしない。
            return

        self._root_id = found[0]["id"]
        subfolders = self._api.list_files(
            f"'{self._root_id}' in parents "
            f"and mimeType = '{FOLDER_MIME}' and trashed = false",
            fields=_FOLDER_FIELDS,
        )
        self._camera_names = {f["id"]: f["name"] for f in subfolders}

    def _load_photos(self) -> list[Photo]:
        self._resolve_folders()
        if not self._root_id:
            return []

        parent_ids = [self._root_id, *self._camera_names]
        parents_clause = " or ".join(f"'{pid}' in parents" for pid in parent_ids)
        files = self._api.list_files(
            f"({parents_clause}) and mimeType contains 'image/' and trashed = false",
            fields=_PHOTO_FIELDS,
            page_size=self._max_photos,
        )

        photos: list[Photo] = []
        for data in files:
            try:
                photos.append(
                    Photo.from_drive_file(data, camera_id=self._camera_of(data))
                )
            except ValueError:
                # 撮影時刻が読めない個体は一覧から外す（画面全体を落とさない）
                continue

        # 並び順は select_photos が受け持つ
        return photos

    def _camera_of(self, data: dict[str, Any]) -> str | None:
        for parent_id in data.get("parents") or []:
            if parent_id in self._camera_names:
                return self._camera_names[parent_id]
        return None
