"""Конфиг лаунчера: JSON рядом с exe/скриптом."""

from __future__ import annotations

import json
import os
import platform
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any


def _app_root() -> Path:
    """Папка рядом с exe (PyInstaller) или корень loader при запуске из исходников."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent.parent.parent


ROOT = _app_root()
CONFIG_FILE = ROOT / "config.json"
GAME_DIR = ROOT / "game"
LOG_DIR = ROOT / "logs"


def total_ram_gb() -> int:
    """Сколько всего оперативной памяти в системе."""
    total = 0.0
    try:
        total = os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / (1024 ** 3)  # type: ignore[attr-defined]
    except (AttributeError, ValueError, OSError):
        total = 0.0
    if platform.system() == "Windows":
        try:
            import ctypes

            class MEMORYSTATUSEX(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            stat = MEMORYSTATUSEX()
            stat.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat)):
                total = stat.ullTotalPhys / (1024 ** 3)
        except Exception:
            pass
    return max(1, int(total))


def default_memory_gb() -> int:
    """Разумный стартовый объём памяти под клиент."""
    total = total_ram_gb()
    if total <= 1:
        return 4
    return max(2, min(8, total // 4))


@dataclass
class LoaderConfig:
    nickname: str = "Goldishka"
    mc_version: str = "1.21.11"
    loader_version: str = "0.19.5"
    memory_gb: int = field(default_factory=default_memory_gb)
    java: str = ""
    game_dir: str = str(GAME_DIR)
    jvm_args: str = ""
    game_args: str = ""

    hide_on_launch: bool = True
    show_console: bool = False
    check_updates: bool = True
    notifications: bool = True
    animations: bool = True
    particles: bool = True

    theme: str = "gold"
    background: str = "gold"

    window_width: int = 1280
    window_height: int = 812
    servers: list[dict[str, Any]] = field(default_factory=list)
    last_page: str = "home"
    first_run: bool = True

    # ------------------------------------------------------------- пути
    @property
    def game_path(self) -> Path:
        if not (self.game_dir or "").strip():
            return GAME_DIR
        path = Path(self.game_dir)
        return path if path.is_absolute() else (ROOT / path)

    @property
    def logs_path(self) -> Path:
        return self.game_path / "logs"

    @property
    def mods_path(self) -> Path:
        return self.game_path / "mods"

    @property
    def packs_path(self) -> Path:
        return self.game_path / "resourcepacks"

    # ------------------------------------------------------------- JSON
    @classmethod
    def load(cls, path: Path | None = None) -> "LoaderConfig":
        path = path or CONFIG_FILE
        config = cls()
        if not path.is_file():
            return config
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return config
        known = {f for f in cls.__dataclass_fields__}
        for key, value in raw.items():
            if key in known:
                setattr(config, key, value)
        if not isinstance(config.servers, list):
            config.servers = []
        return config

    def save(self, path: Path | None = None) -> None:
        path = path or CONFIG_FILE
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            data = asdict(self)
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        except OSError:
            pass

    # ------------------------------------------------------------ прочее
    def ensure_dirs(self) -> None:
        for sub in ("versions", "libraries", "assets", "mods", "resourcepacks", "saves", "logs", "config"):
            (self.game_path / sub).mkdir(parents=True, exist_ok=True)
        LOG_DIR.mkdir(parents=True, exist_ok=True)

    def set_game_dir(self, path: Path) -> None:
        self.game_dir = str(path)
