"""Web 層テストの共通fixture."""

from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from app.auth import User, get_current_user, require_user
from app.dependencies import get_repository
from app.main import app
from app.photo import Photo
from app.repository import InMemoryPhotoRepository

TEST_USER = User(email="farmer@example.com", name="農家 太郎", picture=None)

JPEG_BYTES = b"\xff\xd8\xff-fake-jpeg"


def make_photo(photo_id: str, iso: str, camera_id: str | None = None) -> Photo:
    return Photo(
        id=photo_id,
        name=f"{photo_id}.jpg",
        mime_type="image/jpeg",
        captured_at=datetime.fromisoformat(iso).replace(tzinfo=timezone.utc),
        camera_id=camera_id,
    )


@pytest.fixture
def repository() -> InMemoryPhotoRepository:
    return InMemoryPhotoRepository(
        [
            make_photo("old", "2026-08-18T21:00:00", camera_id="第1ハウス"),
            make_photo("newest", "2026-08-20T21:00:00", camera_id="第2ハウス"),
            make_photo("middle", "2026-08-19T21:00:00", camera_id="第1ハウス"),
        ],
        contents={"newest": JPEG_BYTES},
    )


@pytest.fixture
def empty_repository() -> InMemoryPhotoRepository:
    return InMemoryPhotoRepository([])


@pytest.fixture
def client():
    app.dependency_overrides.clear()
    with TestClient(app, follow_redirects=False) as test_client:
        yield test_client
    app.dependency_overrides.clear()


@pytest.fixture
def logged_in(client, repository):
    """ログイン済みかつ写真がある状態のクライアント."""
    app.dependency_overrides[require_user] = lambda: TEST_USER
    app.dependency_overrides[get_current_user] = lambda: TEST_USER
    app.dependency_overrides[get_repository] = lambda: repository
    return client
