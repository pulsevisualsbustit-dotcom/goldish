"""Моды и ресурспаки: список, включение, установка, удаление."""

from __future__ import annotations

import json
import shutil
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

DISABLED = ".disabled"


@dataclass
class ModInfo:
    path: Path
    name: str
    filename: str
    size: int
    enabled: bool
    builtin: bool = False
    version: str = ""
    description: str = ""
    icon: bytes | None = None

    @property
    def title(self) -> str:
        return self.name or self.filename


@dataclass
class PackInfo:
    path: Path
    filename: str
    name: str
    size: int
    enabled: bool
    description: str = ""
    icon: bytes | None = None


_meta_cache: dict[tuple[str, float], dict[str, Any]] = {}


def _read_json_from_zip(path: Path, member: str) -> dict[str, Any] | None:
    try:
        with zipfile.ZipFile(path) as zf:
            if member not in zf.namelist():
                return None
            return json.loads(zf.read(member).decode("utf-8", "replace"))
    except (OSError, zipfile.BadZipFile, json.JSONDecodeError):
        return None


def _read_bytes_from_zip(path: Path, member: str) -> bytes | None:
    try:
        with zipfile.ZipFile(path) as zf:
            if member not in zf.namelist():
                return None
            return zf.read(member)
    except (OSError, zipfile.BadZipFile):
        return None


def mod_meta(path: Path) -> dict[str, Any]:
    try:
        key = (str(path), path.stat().st_mtime)
    except OSError:
        return {}
    if key in _meta_cache:
        return _meta_cache[key]
    meta = _read_json_from_zip(path, "fabric.mod.json") or {}
    _meta_cache[key] = meta
    return meta


def scan_mods(mods_dir: Path) -> list[ModInfo]:
    mods: list[ModInfo] = []
    if not mods_dir.is_dir():
        return mods
    for path in mods_dir.iterdir():
        if not path.is_file():
            continue
        lower = path.name.lower()
        enabled = lower.endswith(".jar")
        if not (enabled or lower.endswith(".jar" + DISABLED)):
            continue
        meta = mod_meta(path)
        filename = path.name[:-len(DISABLED)] if not enabled else path.name
        name = str(meta.get("name") or filename.rsplit(".jar", 1)[0])
        version = str(meta.get("version") or "")
        description = str(meta.get("description") or "")
        icon_path = meta.get("icon")
        icon = None
        if isinstance(icon_path, str) and icon_path.endswith(".png"):
            icon = _read_bytes_from_zip(path, icon_path)
        builtin = "goldish" in filename.lower() or "fabric-api" in filename.lower()
        mods.append(ModInfo(
            path=path,
            name=name,
            filename=filename,
            size=path.stat().st_size,
            enabled=enabled,
            builtin=builtin,
            version=version,
            description=description,
            icon=icon,
        ))
    mods.sort(key=lambda m: (not m.builtin, not m.enabled, m.name.lower()))
    return mods


def set_mod_enabled(path: Path, enabled: bool) -> Path:
    if enabled and path.name.endswith(DISABLED):
        target = path.with_name(path.name[:-len(DISABLED)])
    elif not enabled and path.name.endswith(".jar"):
        target = path.with_name(path.name + DISABLED)
    else:
        return path
    if target.exists() and target != path:
        raise FileExistsError(f"файл уже существует: {target.name}")
    path.rename(target)
    return target


def delete_mod(path: Path) -> None:
    try:
        path.unlink()
    except OSError as err:
        raise OSError(f"не удалось удалить {path.name}: {err}") from err


def install_mod(source: Path, mods_dir: Path) -> Path:
    mods_dir.mkdir(parents=True, exist_ok=True)
    target = mods_dir / source.name
    if target.exists():
        raise FileExistsError(f"мод уже установлен: {source.name}")
    shutil.copy2(source, target)
    return target


# ---------------------------------------------------------------------------
# ресурспаки
# ---------------------------------------------------------------------------
def _pack_description(raw: Any) -> str:
    if isinstance(raw, str):
        return raw
    if isinstance(raw, dict):
        text = raw.get("text", "")
        for extra in raw.get("extra", []):
            if isinstance(extra, dict):
                text += extra.get("text", "")
        return str(text)
    return ""


def pack_meta(path: Path) -> tuple[str, str, bytes | None]:
    """Возвращает (название, описание, иконка)."""
    if path.is_dir():
        meta_path = path / "pack.mcmeta"
        icon_path = path / "pack.png"
        meta: dict[str, Any] = {}
        if meta_path.is_file():
            try:
                meta = json.loads(meta_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                meta = {}
        icon = icon_path.read_bytes() if icon_path.is_file() else None
    else:
        meta = _read_json_from_zip(path, "pack.mcmeta") or {}
        icon = _read_bytes_from_zip(path, "pack.png")
    pack = meta.get("pack") or {}
    description = _pack_description(pack.get("description", ""))
    name = path.stem if path.is_file() else path.name
    return name, description, icon


def read_options(options: Path) -> tuple[list[str], int, list[str]]:
    if not options.is_file():
        return [], -1, ["vanilla"]
    try:
        lines = options.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return [], -1, ["vanilla"]
    for index, line in enumerate(lines):
        if line.startswith("resourcePacks:"):
            try:
                value = json.loads(line.split(":", 1)[1])
                if isinstance(value, list):
                    return lines, index, [str(v) for v in value]
            except (json.JSONDecodeError, IndexError):
                break
    return lines, -1, ["vanilla"]


def enabled_packs(options: Path) -> set[str]:
    _, _, values = read_options(options)
    result: set[str] = set()
    for value in values:
        if value.startswith("file/"):
            result.add(value[5:])
    return result


def scan_packs(packs_dir: Path, options: Path) -> list[PackInfo]:
    if not packs_dir.is_dir():
        return []
    active = enabled_packs(options)
    packs: list[PackInfo] = []
    for path in packs_dir.iterdir():
        is_zip = path.is_file() and path.suffix.lower() == ".zip"
        is_dir = path.is_dir() and (path / "pack.mcmeta").is_file()
        if not (is_zip or is_dir):
            continue
        name, description, icon = pack_meta(path)
        size = path.stat().st_size if path.is_file() else sum(
            p.stat().st_size for p in path.rglob("*") if p.is_file()
        )
        packs.append(PackInfo(
            path=path,
            filename=path.name,
            name=name,
            size=size,
            enabled=path.name in active,
            description=description,
            icon=icon,
        ))
    packs.sort(key=lambda p: (not p.enabled, p.name.lower()))
    return packs


def toggle_pack(options: Path, filename: str) -> tuple[bool, str]:
    lines, index, values = read_options(options)
    if index < 0:
        return False, "Запустите игру один раз, чтобы появился options.txt"
    entry = f"file/{filename}"
    if entry in values:
        values = [v for v in values if v != entry]
        action = "выключен"
    else:
        values.append(entry)
        action = "включён"
    lines[index] = "resourcePacks:" + json.dumps(values, ensure_ascii=False, separators=(",", ":"))
    try:
        options.write_text("\n".join(lines) + "\n", encoding="utf-8")
    except OSError as err:
        return False, f"Не удалось сохранить options.txt: {err}"
    return True, f"Ресурспак {action}. Перезапустите игру."


def install_pack(source: Path, packs_dir: Path) -> Path:
    packs_dir.mkdir(parents=True, exist_ok=True)
    target = packs_dir / source.name
    if target.exists():
        raise FileExistsError(f"ресурспак уже установлен: {source.name}")
    if source.is_dir():
        shutil.copytree(source, target)
    else:
        shutil.copy2(source, target)
    return target


def delete_pack(path: Path) -> None:
    try:
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()
    except OSError as err:
        raise OSError(f"не удалось удалить {path.name}: {err}") from err
