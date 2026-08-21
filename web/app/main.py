"""定点カメラ写真ビューアー（Web）."""

from __future__ import annotations

from pathlib import Path

import httpx
from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.middleware.sessions import SessionMiddleware

from app.auth import (
    NotAuthenticatedError,
    User,
    build_authorize_url,
    clear_user,
    exchange_code_for_token,
    fetch_user,
    get_current_user,
    require_user,
    save_user,
    verify_state,
)
from app.config import get_settings
from app.dependencies import get_repository
from app.repository import PhotoRepository

BASE_DIR = Path(__file__).parent

app = FastAPI(title="定点カメラ写真ビューアー")
app.add_middleware(SessionMiddleware, secret_key=get_settings().session_secret)
app.mount("/static", StaticFiles(directory=BASE_DIR / "static"), name="static")

templates = Jinja2Templates(directory=BASE_DIR / "templates")


@app.exception_handler(NotAuthenticatedError)
async def _redirect_to_login(request: Request, exc: NotAuthenticatedError) -> Response:
    """未ログインは 401 ではなくログイン画面へ誘導する（利用者向けの配慮）."""
    return RedirectResponse("/login", status_code=302)


@app.exception_handler(httpx.HTTPError)
async def _drive_failed(request: Request, exc: httpx.HTTPError) -> Response:
    """Drive に繋がらない・拒否されたときに素の 500 画面を出さない."""
    if isinstance(exc, httpx.HTTPStatusError) and exc.response.status_code in (401, 403):
        # アクセストークンの自動更新はしない方針なので、再ログインで解決させる。
        # 期限切れのセッションを消さないとログイン画面と往復してしまう。
        clear_user(request)
        return RedirectResponse("/login?error=expired", status_code=302)

    return templates.TemplateResponse(
        request,
        "error.html",
        {
            "user": get_current_user(request),
            "message": "写真を取得できませんでした。",
            "hint": "通信状況を確かめて、しばらくしてからもう一度お試しください。",
        },
        status_code=502,
    )


# --- 画面 -------------------------------------------------------------


@app.get("/")
def gallery(
    request: Request,
    camera: str | None = None,
    user: User = Depends(require_user),
    repository: PhotoRepository = Depends(get_repository),
) -> Response:
    photos = repository.list_photos(camera_id=camera)
    return templates.TemplateResponse(
        request,
        "gallery.html",
        {
            "user": user,
            "photos": photos,
            # 農家が一番知りたいのは「今どうなっているか」なので最新を別枠で見せる
            "latest": photos[0] if photos else None,
            "cameras": repository.list_cameras(),
            "selected_camera": camera,
        },
        # 手動更新ボタンでキャッシュが返ってきては意味がない
        headers={"Cache-Control": "no-store"},
    )


@app.get("/photos/{photo_id}")
def photo_detail(
    request: Request,
    photo_id: str,
    user: User = Depends(require_user),
    repository: PhotoRepository = Depends(get_repository),
) -> Response:
    photo = repository.get_photo(photo_id)
    if photo is None:
        raise HTTPException(status_code=404, detail="写真が見つかりません")

    return templates.TemplateResponse(
        request, "photo.html", {"user": user, "photo": photo}
    )


@app.get("/photos/{photo_id}/image")
def photo_image(
    photo_id: str,
    repository: PhotoRepository = Depends(get_repository),
) -> Response:
    """Drive の画像を代理取得して返す.

    Drive の画像 URL は認証が必要で <img src> から直接は読めないため。
    """
    try:
        content, mime_type = repository.get_photo_content(photo_id)
    except LookupError:
        raise HTTPException(status_code=404, detail="写真が見つかりません")

    return Response(
        content,
        media_type=mime_type,
        # 撮り終えた定点写真は変化しないので、ブラウザに持たせて Drive を守る
        headers={"Cache-Control": "private, max-age=86400"},
    )


@app.get("/login")
def login(request: Request) -> Response:
    if get_current_user(request) is not None:
        return RedirectResponse("/", status_code=302)
    return templates.TemplateResponse(
        request, "login.html", {"error": request.query_params.get("error")}
    )


@app.get("/logout")
def logout(request: Request) -> Response:
    clear_user(request)
    return RedirectResponse("/login", status_code=302)


# --- OAuth -----------------------------------------------------------


def _redirect_uri(request: Request) -> str:
    return str(request.url_for("auth_callback"))


@app.get("/auth/login")
def auth_login(request: Request) -> Response:
    return RedirectResponse(
        build_authorize_url(request, _redirect_uri(request)), status_code=302
    )


@app.get("/auth/callback", name="auth_callback")
def auth_callback(request: Request) -> Response:
    params = request.query_params
    if params.get("error"):
        return RedirectResponse("/login?error=denied", status_code=302)

    if not verify_state(request, params.get("state")):
        return RedirectResponse("/login?error=state", status_code=302)

    code = params.get("code")
    if not code:
        return RedirectResponse("/login?error=nocode", status_code=302)

    try:
        token = exchange_code_for_token(code, _redirect_uri(request))
        user = fetch_user(token["access_token"])
    except (httpx.HTTPError, KeyError):
        return RedirectResponse("/login?error=failed", status_code=302)

    save_user(request, user)
    return RedirectResponse("/", status_code=302)
