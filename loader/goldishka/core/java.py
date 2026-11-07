"""Поиск и проверка Java."""

from __future__ import annotations

import os
import platform
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

JAVA_MIN = 21
_IS_WIN = platform.system() == "Windows"
_CACHE: dict[str, "JavaInfo | None"] = {}


@dataclass
class JavaInfo:
    path: str
    major: int
    vendor: str = ""

    @property
    def version(self) -> str:
        return f"Java {self.major}"

    @property
    def suitable(self) -> bool:
        return self.major >= JAVA_MIN

    def label(self) -> str:
        name = Path(self.path).parent.parent.name
        return f"{name} · Java {self.major}"


def _hidden_flags() -> int:
    return getattr(subprocess, "CREATE_NO_WINDOW", 0) if _IS_WIN else 0


def probe(path: str | Path) -> JavaInfo | None:
    key = str(path)
    if key in _CACHE:
        return _CACHE[key]
    exe = Path(path)
    if not exe.is_file():
        _CACHE[key] = None
        return None
    try:
        res = subprocess.run(
            [str(exe), "-version"],
            capture_output=True,
            text=True,
            timeout=25,
            creationflags=_hidden_flags(),
        )
    except (OSError, subprocess.TimeoutExpired):
        _CACHE[key] = None
        return None
    text = (res.stderr or "") + (res.stdout or "")
    match = re.search(r'version "(\d+)(?:\.(\d+))?', text)
    if not match:
        _CACHE[key] = None
        return None
    major = int(match.group(1))
    if major == 1 and match.group(2):
        major = int(match.group(2))
    vendor = ""
    for name in ("Temurin", "Adoptium", "Zulu", "Microsoft", "GraalVM", "BellSoft", "Liberica", "Corretto", "OpenJDK"):
        if name.lower() in text.lower():
            vendor = name
            break
    info = JavaInfo(str(exe), major, vendor)
    _CACHE[key] = info
    return info


def candidates(hint: str = "") -> list[str]:
    found: list[str] = []
    if hint:
        found.append(hint)
    home = os.environ.get("JAVA_HOME", "").strip()
    if home:
        exe = Path(home) / "bin" / ("java.exe" if _IS_WIN else "java")
        found.append(str(exe))
    which = shutil.which("java")
    if which:
        found.append(which)
    if _IS_WIN:
        roots = [
            Path(r"C:\Program Files\Java"),
            Path(r"C:\Program Files\Eclipse Adoptium"),
            Path(r"C:\Program Files\Microsoft"),
            Path(r"C:\Program Files\Zulu"),
            Path(r"C:\Program Files\BellSoft"),
            Path(r"C:\Program Files\Amazon Corretto"),
            Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "Eclipse Adoptium",
        ]
        for root in roots:
            if root.is_dir():
                found.extend(str(p) for p in root.glob("*/bin/java.exe"))
    elif platform.system() == "Darwin":
        found.extend(str(p) for p in Path("/Library/Java/JavaVirtualMachines").glob("*/Contents/Home/bin/java"))
    else:
        found.extend(str(p) for p in Path("/usr/lib/jvm").glob("*/bin/java"))

    unique: list[str] = []
    seen: set[str] = set()
    for path in found:
        key = str(Path(path)).lower()
        if key not in seen:
            seen.add(key)
            unique.append(path)
    return unique


def _score(info: JavaInfo) -> tuple[int, int]:
    """21 — идеально; новее 25 может ломать LWJGL, старее 21 — не подходит."""
    if info.major == 21:
        return (0, 0)
    if info.major == 22:
        return (1, 0)
    if info.major in (23, 24, 25):
        return (2, -info.major)
    if info.major > 25:
        return (4, -info.major)
    return (8, -info.major)


def find_all(hint: str = "") -> list[JavaInfo]:
    infos = [info for path in candidates(hint) if (info := probe(path)) is not None]
    infos.sort(key=_score)
    return infos


def find_best(hint: str = "") -> JavaInfo | None:
    infos = find_all(hint)
    for info in infos:
        if info.major == 21:
            return info
    return infos[0] if infos else None


def resolve(hint: str = "") -> JavaInfo | None:
    """Сначала явный путь, затем лучшая найденная Java."""
    if hint:
        info = probe(hint)
        if info is not None:
            return info
    return find_best()
