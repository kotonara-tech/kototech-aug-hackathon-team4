"""設定の既定値のテスト（Red フェーズ）."""

from app.config import get_settings
from app.drive import DEFAULT_ROOT_FOLDER_NAME


def test_既定のフォルダ名はPOCの取り決めに従う():
    """担当 A の Android アプリが書き込むフォルダ名と一致していないと、
    結合したときに 1 枚も表示されない。"""
    assert DEFAULT_ROOT_FOLDER_NAME == "FarmCameraPOC"


def test_環境変数がなければ既定のフォルダ名を使う(monkeypatch):
    monkeypatch.delenv("DRIVE_FOLDER_NAME", raising=False)
    get_settings.cache_clear()
    try:
        assert get_settings().drive_folder_name == "FarmCameraPOC"
    finally:
        get_settings.cache_clear()


def test_フォルダ名は環境変数で変更できる(monkeypatch):
    monkeypatch.setenv("DRIVE_FOLDER_NAME", "別のフォルダ")
    get_settings.cache_clear()
    try:
        assert get_settings().drive_folder_name == "別のフォルダ"
    finally:
        get_settings.cache_clear()
