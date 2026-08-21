"""Google アカウントによるログイン.

ユーザー本人の OAuth トークンで Drive を読む方式のため、
サーバーはサービスアカウントの鍵を持たない。
"""

from __future__ import annotations

import secrets
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlencode

import httpx
from starlette.requests import Request

from app.config import get_settings

AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo"

# drive.readonly は「ユーザーの Drive を読むだけ」の権限。書き込みは要求しない。
SCOPES = (
    "openid email profile https://www.googleapis.com/auth/drive.readonly"
)

_SESSION_USER_KEY = "user"
_SESSION_STATE_KEY = "oauth_state"


@dataclass(frozen=True)
class User:
    email: str
    name: str
    picture: str | None = None
    access_token: str = ""


class NotAuthenticatedError(Exception):
    """ログインが必要なページに未ログインで来たとき."""


# --- セッション -------------------------------------------------------


def save_user(request: Request, user: User) -> None:
    request.session[_SESSION_USER_KEY] = {
        "email": user.email,
        "name": user.name,
        "picture": user.picture,
        "access_token": user.access_token,
    }


def clear_user(request: Request) -> None:
    request.session.clear()


def get_current_user(request: Request) -> User | None:
    data = request.session.get(_SESSION_USER_KEY)
    if not data:
        return None
    return User(
        email=data.get("email", ""),
        name=data.get("name", ""),
        picture=data.get("picture"),
        access_token=data.get("access_token", ""),
    )


def require_user(request: Request) -> User:
    user = get_current_user(request)
    if user is None:
        raise NotAuthenticatedError
    return user


# --- OAuth 2.0 の手続き -----------------------------------------------


def build_authorize_url(request: Request, redirect_uri: str) -> str:
    """Google の同意画面へ送るための URL を作る."""
    settings = get_settings()
    state = secrets.token_urlsafe(24)
    request.session[_SESSION_STATE_KEY] = state

    params = {
        "client_id": settings.google_client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": SCOPES,
        "state": state,
        # 毎回同意を求めず、既に許可済みならそのまま通す
        "prompt": "select_account",
        "include_granted_scopes": "true",
    }
    return f"{AUTHORIZE_URL}?{urlencode(params)}"


def verify_state(request: Request, state: str | None) -> bool:
    """CSRF 対策。送り出したときの state と一致するか確かめる."""
    expected = request.session.pop(_SESSION_STATE_KEY, None)
    return bool(expected) and secrets.compare_digest(expected, state or "")


def exchange_code_for_token(code: str, redirect_uri: str) -> dict[str, Any]:
    settings = get_settings()
    response = httpx.post(
        TOKEN_URL,
        data={
            "code": code,
            "client_id": settings.google_client_id,
            "client_secret": settings.google_client_secret,
            "redirect_uri": redirect_uri,
            "grant_type": "authorization_code",
        },
        timeout=15.0,
    )
    response.raise_for_status()
    return response.json()


def fetch_user(access_token: str) -> User:
    """アクセストークンを使って本人情報を取得する.

    ID トークンの署名検証（JWKS 取得）を避けたいので userinfo を使う。
    トークンは今取得したばかりのものなので、経路上は安全。
    """
    response = httpx.get(
        USERINFO_URL,
        headers={"Authorization": f"Bearer {access_token}"},
        timeout=15.0,
    )
    response.raise_for_status()
    info = response.json()
    return User(
        email=info.get("email", ""),
        name=info.get("name") or info.get("email", ""),
        picture=info.get("picture"),
        access_token=access_token,
    )
