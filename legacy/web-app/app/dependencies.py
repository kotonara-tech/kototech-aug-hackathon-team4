"""FastAPI の依存関係.

写真の取得元をここ 1 箇所で決める。DB が決まったらこの関数だけを差し替える。
"""

from __future__ import annotations

from fastapi import Depends

from app.auth import User, require_user
from app.config import Settings, get_settings
from app.drive import DrivePhotoRepository, GoogleDriveApi
from app.repository import PhotoRepository


def get_repository(
    user: User = Depends(require_user),
    settings: Settings = Depends(get_settings),
) -> PhotoRepository:
    api = GoogleDriveApi(user.access_token)
    return DrivePhotoRepository(
        api,
        root_folder_name=settings.drive_folder_name,
        max_photos=settings.max_photos,
    )
