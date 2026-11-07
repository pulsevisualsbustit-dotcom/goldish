"""Чтение данных клиента Goldish (друзья, альты, модули)."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from .config import LoaderConfig


@dataclass
class ClientData:
    friends: list[str] = field(default_factory=list)
    alts: list[str] = field(default_factory=list)
    modules_total: int = 0
    modules_enabled: int = 0
    theme: str = "gold"
    available: bool = False


def read_client_data(config: LoaderConfig) -> ClientData:
    path = config.game_path / "config" / "goldish" / "config.json"
    if not path.is_file():
        # разработческий run/ рядом с проектом
        dev = config.game_path.parent / "run" / "config" / "goldish" / "config.json"
        if dev.is_file():
            path = dev
        else:
            return ClientData()
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return ClientData()
    friends = [str(name) for name in raw.get("friends", []) if str(name).strip()]
    alts_raw = raw.get("alts", [])
    alts: list[str] = []
    if isinstance(alts_raw, list):
        for alt in alts_raw:
            if isinstance(alt, dict):
                name = alt.get("name") or alt.get("nickname") or alt.get("username")
                if name:
                    alts.append(str(name))
            elif isinstance(alt, str):
                alts.append(alt)
    modules = raw.get("modules") or {}
    enabled = sum(1 for data in modules.values() if isinstance(data, dict) and data.get("enabled"))
    return ClientData(
        friends=friends,
        alts=alts,
        modules_total=len(modules),
        modules_enabled=enabled,
        theme=str(raw.get("theme", "gold")),
        available=True,
    )


def client_config_path(config: LoaderConfig) -> Path:
    return config.game_path / "config" / "goldish" / "config.json"
