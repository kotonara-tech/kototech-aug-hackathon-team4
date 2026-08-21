"""定点カメラ写真のドメインモデル."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

# 農家の方が読む画面なので、表示は常に日本時間に揃える。
JST = timezone(timedelta(hours=9), "JST")

# Drive が返す EXIF 時刻の形式（例: "2026:08:20 06:30:00"）。
# タイムゾーン情報を持たない現地時刻である点に注意。
_EXIF_TIME_FORMAT = "%Y:%m:%d %H:%M:%S"


@dataclass(frozen=True)
class Photo:
    """1 枚の撮影データ.

    DB が未定のため、この型がアプリ内の共通通貨になる。
    Drive 由来でも将来の Firestore 由来でも、画面はこの型だけを見る。
    """

    id: str
    name: str
    mime_type: str
    captured_at: datetime
    camera_id: str | None = None

    @classmethod
    def from_drive_file(
        cls, data: dict[str, Any], camera_id: str | None = None
    ) -> "Photo":
        """Google Drive の files リソースから生成する."""
        file_id = data.get("id")
        if not file_id:
            raise ValueError("Drive ファイルに id がありません")

        return cls(
            id=file_id,
            name=data.get("name") or file_id,
            mime_type=data.get("mimeType") or "image/jpeg",
            captured_at=_extract_captured_at(data),
            camera_id=camera_id,
        )

    def display_datetime(self) -> str:
        return self.captured_at.astimezone(JST).strftime("%Y/%m/%d %H:%M")

    def display_date(self) -> str:
        return self.captured_at.astimezone(JST).strftime("%Y/%m/%d")


def _extract_captured_at(data: dict[str, Any]) -> datetime:
    """撮影時刻を決める.

    圃場は電波が弱く、撮影から Drive への到着まで数時間ずれることがある。
    そのため EXIF の撮影時刻を最優先し、無い場合だけ Drive の作成時刻を使う。
    """
    exif_time = (data.get("imageMediaMetadata") or {}).get("time")
    if exif_time:
        try:
            return datetime.strptime(exif_time, _EXIF_TIME_FORMAT).replace(tzinfo=JST)
        except ValueError:
            # EXIF が壊れている個体もあるので、ここでは諦めて createdTime に落とす。
            pass

    created_time = data.get("createdTime")
    if created_time:
        parsed = datetime.fromisoformat(created_time.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed

    raise ValueError("撮影時刻を特定できません")
