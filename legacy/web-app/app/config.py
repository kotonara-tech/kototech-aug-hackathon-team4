"""環境変数から読み込む設定."""

from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache

from dotenv import load_dotenv

from app.drive import DEFAULT_ROOT_FOLDER_NAME

load_dotenv()


@dataclass(frozen=True)
class Settings:
    google_client_id: str
    google_client_secret: str
    session_secret: str
    drive_folder_name: str
    max_photos: int


@lru_cache
def get_settings() -> Settings:
    return Settings(
        google_client_id=os.getenv("GOOGLE_CLIENT_ID", ""),
        google_client_secret=os.getenv("GOOGLE_CLIENT_SECRET", ""),
        # 本番では必ず環境変数で上書きする（README 参照）
        session_secret=os.getenv("SESSION_SECRET", "dev-only-secret-change-me"),
        drive_folder_name=os.getenv("DRIVE_FOLDER_NAME", DEFAULT_ROOT_FOLDER_NAME),
        max_photos=int(os.getenv("MAX_PHOTOS", "300")),
    )
