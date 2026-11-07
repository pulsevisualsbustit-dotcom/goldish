"""Настоящий лаунчер Minecraft: скачивание, Fabric, моды и запуск.

Никаких сторонних библиотек — только стандартный Python.
"""

from __future__ import annotations

import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable

from . import java as javalib
from .config import LoaderConfig, ROOT

MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
RESOURCES_URL = "https://resources.download.minecraft.net"
FABRIC_META = "https://meta.fabricmc.net/v2"
MOJANG_LIBS = "https://libraries.minecraft.net/"
MODRINTH_API = "https://api.modrinth.com/v2"
USER_AGENT = "GoldishkaLoader/1.0 (Minecraft launcher)"
JAVA_MIN = 21

ProgressFn = Callable[[str, str, float], None]
LogFn = Callable[[str], None]

IS_WIN = platform.system() == "Windows"
OS_KEY = "windows" if IS_WIN else ("osx" if platform.system() == "Darwin" else "linux")
ARCH = "x86_64"


class LauncherError(RuntimeError):
    pass


class Cancelled(LauncherError):
    pass


@dataclass
class Component:
    key: str
    title: str
    ok: bool
    detail: str = ""


def _sha1(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def offline_uuid(nickname: str) -> str:
    digest = hashlib.md5(f"OfflinePlayer:{nickname}".encode("utf-8")).digest()
    return str(uuid.UUID(bytes=digest, version=3))


def rules_allow(rules: list[dict[str, Any]] | None) -> bool:
    if not rules:
        return True
    allowed = False
    for rule in rules:
        if rule.get("features"):
            continue
        os_rule = rule.get("os", {})
        if "name" in os_rule and os_rule["name"] != OS_KEY:
            continue
        if "arch" in os_rule and os_rule["arch"] not in (ARCH, "amd64", "x64", "x86_64"):
            continue
        if "version" in os_rule:
            try:
                if not re.search(os_rule["version"], platform.version()):
                    continue
            except re.error:
                continue
        allowed = rule.get("action") == "allow"
    return allowed


def maven_path(name: str) -> Path | None:
    name = name.split("@", 1)[0]
    parts = name.split(":")
    if len(parts) < 3:
        return None
    group, artifact, version = parts[0], parts[1], parts[2]
    classifier = parts[3] if len(parts) > 3 else ""
    suffix = f"-{classifier}" if classifier else ""
    return Path(*group.split(".")) / artifact / version / f"{artifact}-{version}{suffix}.jar"


class MinecraftLauncher:
    def __init__(
        self,
        config: LoaderConfig,
        on_progress: ProgressFn | None = None,
        on_log: LogFn | None = None,
    ):
        self.config = config
        self.on_progress = on_progress
        self.on_log = on_log
        self.cancel_event = threading.Event()
        self.process: subprocess.Popen | None = None
        self._console_file = None
        self.meta: dict[str, Any] = {}
        self.profile: dict[str, Any] | None = None
        self._last_stage = ""

        self.game = config.game_path
        self.versions = self.game / "versions"
        self.libraries = self.game / "libraries"
        self.assets = self.game / "assets"
        self.mods = self.game / "mods"

    # ------------------------------------------------------------------ лог
    def log(self, message: str) -> None:
        if self.on_log:
            self.on_log(message)

    def emit(self, stage: str, detail: str = "", fraction: float = 0.0) -> None:
        if stage != self._last_stage:
            self.log(f"{stage}{(': ' + detail) if detail else ''}")
            self._last_stage = stage
        if self.on_progress:
            self.on_progress(stage, detail, max(0.0, min(1.0, fraction)))

    def check_cancel(self) -> None:
        if self.cancel_event.is_set():
            raise Cancelled("Установка отменена")

    # --------------------------------------------------------------- сеть
    def _open(self, url: str, timeout: int = 60):
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        return urllib.request.urlopen(request, timeout=timeout)

    def _fetch(self, url: str, timeout: int = 60) -> bytes:
        last: Exception | None = None
        for attempt in range(3):
            self.check_cancel()
            try:
                with self._open(url, timeout) as response:
                    return response.read()
            except (urllib.error.URLError, TimeoutError, OSError) as err:
                last = err
                time.sleep(0.6 * (attempt + 1))
        raise LauncherError(f"Не удалось скачать {url}: {last}")

    def _fetch_json(self, url: str) -> Any:
        return json.loads(self._fetch(url).decode("utf-8"))

    def _download(
        self,
        url: str,
        target: Path,
        sha1: str = "",
        size: int = 0,
        byte_progress: Callable[[int, int], None] | None = None,
    ) -> Path:
        self.check_cancel()
        if target.is_file() and target.stat().st_size > 0:
            same_size = not size or target.stat().st_size == size
            if same_size and (not sha1 or _sha1(target).lower() == sha1.lower()):
                if byte_progress:
                    byte_progress(target.stat().st_size, target.stat().st_size)
                return target
        target.parent.mkdir(parents=True, exist_ok=True)
        tmp = target.with_suffix(target.suffix + ".part")
        last: Exception | None = None
        for attempt in range(3):
            self.check_cancel()
            try:
                with self._open(url) as response:
                    total = int(response.headers.get("Content-Length") or size or 0)
                    received = 0
                    with tmp.open("wb") as fh:
                        while True:
                            self.check_cancel()
                            chunk = response.read(1 << 16)
                            if not chunk:
                                break
                            fh.write(chunk)
                            received += len(chunk)
                            if byte_progress and total:
                                byte_progress(received, total)
                if sha1 and _sha1(tmp).lower() != sha1.lower():
                    raise LauncherError(f"контрольная сумма не сошлась: {target.name}")
                if size and tmp.stat().st_size != size:
                    raise LauncherError(f"размер не сошёлся: {target.name}")
                tmp.replace(target)
                return target
            except LauncherError:
                raise
            except (urllib.error.URLError, TimeoutError, OSError) as err:
                last = err
                time.sleep(0.6 * (attempt + 1))
        tmp.unlink(missing_ok=True)
        raise LauncherError(f"Не удалось скачать {target.name}: {last}")

    # ------------------------------------------------------------ проверка
    def java(self) -> javalib.JavaInfo | None:
        return javalib.resolve(self.config.java)

    def components(self) -> list[Component]:
        info = self.java()
        java_ok = info is not None and info.suitable
        client = self.versions / self.config.mc_version / f"{self.config.mc_version}.jar"
        profile = self.versions / f"fabric-loader-{self.config.loader_version}-{self.config.mc_version}" / \
            f"fabric-loader-{self.config.loader_version}-{self.config.mc_version}.json"
        assets_marker = self.assets / ".goldish-assets-ok"
        mods = list(self.mods.glob("*.jar")) if self.mods.is_dir() else []
        goldish = any(p.name.lower().startswith("goldish") for p in mods)
        api = any("fabric-api" in p.name.lower() for p in mods)
        return [
            Component("java", "Java", java_ok,
                      info.label() if info else "не найдена"),
            Component("client", "Клиент Minecraft", client.is_file(),
                      "скачан" if client.is_file() else "не скачан"),
            Component("assets", "Ресурсы", assets_marker.is_file(),
                      "готовы" if assets_marker.is_file() else "не загружены"),
            Component("fabric", "Fabric Loader", profile.is_file(),
                      f"{self.config.loader_version}" if profile.is_file() else "не установлен"),
            Component("goldish", "Goldish", goldish,
                      "в mods" if goldish else "jar не найден"),
            Component("api", "Fabric API", api,
                      "в mods" if api else "не установлен"),
        ]

    def ready(self) -> bool:
        return all(c.ok for c in self.components() if c.key in ("java", "client", "fabric", "assets"))

    # ------------------------------------------------------------ установка
    def install(self) -> None:
        self.cancel_event.clear()
        self.config.ensure_dirs()
        self.emit("Подготовка", "проверяем Java", 0.01)
        info = self.java()
        if info is None:
            raise LauncherError(
                "Java не найдена. Установите JDK 21 и укажите путь в «Параметрах запуска»."
            )
        if info.major < JAVA_MIN:
            raise LauncherError(f"Нужна Java 21 или новее, найдена Java {info.major}.")

        self.emit("Версия", f"Minecraft {self.config.mc_version}", 0.03)
        self._ensure_meta()

        self.emit("Клиент", "client.jar", 0.06)
        self._ensure_client()

        self.emit("Библиотеки", "", 0.10)
        self._ensure_libraries(self.meta.get("libraries", []), 0.10, 0.28)

        self.emit("Нативы", "распаковка", 0.29)
        self._extract_natives(self.meta.get("libraries", []))

        self.emit("Ресурсы", "проверка ассетов", 0.31)
        self._ensure_assets()

        self.emit("Fabric", f"loader {self.config.loader_version}", 0.80)
        self.profile = self._ensure_fabric()
        self._ensure_libraries(self.profile.get("libraries", []), 0.80, 0.88, base_url="https://maven.fabricmc.net/")

        self.emit("Моды", "Goldish + Fabric API", 0.89)
        self._ensure_mods()

        marker = self.game / ".goldish-installed.json"
        marker.write_text(json.dumps({
            "mc": self.config.mc_version,
            "loader": self.config.loader_version,
            "time": int(time.time()),
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        self.emit("Готово", "всё на месте", 1.0)

    # -------------------------------------------------------------- мета
    def _ensure_meta(self) -> None:
        cached = self.versions / self.config.mc_version / f"{self.config.mc_version}.json"
        if cached.is_file():
            try:
                self.meta = json.loads(cached.read_text(encoding="utf-8"))
                self.log(f"метаданные версии из кэша: {self.config.mc_version}")
                return
            except (OSError, json.JSONDecodeError):
                pass
        manifest = self._fetch_json(MANIFEST_URL)
        url = None
        for entry in manifest.get("versions", []):
            if entry.get("id") == self.config.mc_version:
                url = entry.get("url")
                break
        if not url:
            raise LauncherError(f"Версия {self.config.mc_version} не найдена у Mojang.")
        self.meta = self._fetch_json(url)
        cached.parent.mkdir(parents=True, exist_ok=True)
        cached.write_text(json.dumps(self.meta, ensure_ascii=False, indent=2), encoding="utf-8")
        self.log(f"метаданные версии скачаны: {self.config.mc_version}")

    def _ensure_client(self) -> None:
        client = (self.meta.get("downloads") or {}).get("client")
        if not client:
            raise LauncherError("В метаданных нет client.jar.")
        target = self.versions / self.config.mc_version / f"{self.config.mc_version}.jar"
        last = [0.0]

        def progress(done: int, total: int) -> None:
            if total:
                now = time.monotonic()
                if now - last[0] > 0.15 or done == total:
                    last[0] = now
                    self.emit("Клиент", f"{done / 1048576:.0f} / {total / 1048576:.0f} МБ",
                              0.06 + 0.05 * done / total)

        self._download(client["url"], target, client.get("sha1", ""), client.get("size", 0), progress)

    # --------------------------------------------------------- библиотеки
    def _library_target(self, lib: dict[str, Any], base_url: str | None = None) -> tuple[Path, str, str, int]:
        downloads = lib.get("downloads") or {}
        artifact = downloads.get("artifact")
        if artifact:
            return self.libraries / artifact["path"], artifact["url"], artifact.get("sha1", ""), artifact.get("size", 0)
        name = lib.get("name", "")
        path = maven_path(name)
        if path is None:
            raise LauncherError(f"Неизвестная библиотека: {name}")
        base = (lib.get("url") or base_url or MOJANG_LIBS)
        if not base.endswith("/"):
            base += "/"
        url = base + path.as_posix()
        return self.libraries / path, url, lib.get("sha1", ""), lib.get("size", 0)

    def _ensure_libraries(
        self,
        libraries: Iterable[dict[str, Any]],
        start: float,
        end: float,
        base_url: str | None = None,
    ) -> None:
        libs = [lib for lib in libraries if rules_allow(lib.get("rules"))]
        total = max(1, len(libs))
        for index, lib in enumerate(libs):
            self.check_cancel()
            try:
                target, url, sha1, size = self._library_target(lib, base_url)
            except LauncherError as err:
                self.log(f"[!] {err}")
                continue
            if target.is_file() and (not size or target.stat().st_size == size):
                fraction = start + (end - start) * (index + 1) / total
                self.emit("Библиотеки", f"{index + 1} / {total}", fraction)
                continue
            try:
                self._download(url, target, sha1, size)
            except LauncherError as err:
                self.log(f"[!] {err}")
            fraction = start + (end - start) * (index + 1) / total
            self.emit("Библиотеки", f"{index + 1} / {total}", fraction)

    def _extract_natives(self, libraries: Iterable[dict[str, Any]]) -> None:
        natives_dir = self.versions / self.config.mc_version / "natives"
        natives_dir.mkdir(parents=True, exist_ok=True)
        extracted = 0
        for lib in libraries:
            if not rules_allow(lib.get("rules")):
                continue
            downloads = lib.get("downloads") or {}
            classifiers = downloads.get("classifiers") or {}
            keys = [key for key in classifiers if OS_KEY in key]
            if not keys:
                continue
            try:
                jar = self.libraries / classifiers[keys[0]]["path"]
            except (KeyError, TypeError):
                continue
            if not jar.is_file():
                continue
            try:
                with zipfile.ZipFile(jar) as zf:
                    for member in zf.namelist():
                        if member.endswith("/"):
                            continue
                        if member.lower().endswith((".dll", ".so", ".dylib", ".jnilib")):
                            data = zf.read(member)
                            out = natives_dir / Path(member).name
                            if not out.is_file() or out.stat().st_size != len(data):
                                out.write_bytes(data)
                                extracted += 1
            except (zipfile.BadZipFile, OSError) as err:
                self.log(f"[!] нативы из {jar.name}: {err}")
        if extracted:
            self.log(f"нативов распаковано: {extracted}")

    # ------------------------------------------------------------- ассеты
    def _ensure_assets(self) -> None:
        index_info = self.meta.get("assetIndex")
        if not index_info:
            self.log("[!] в метаданных нет assetIndex — пропускаю ресурсы")
            return
        index_path = self.assets / "indexes" / f"{index_info['id']}.json"
        self._download(index_info["url"], index_path, index_info.get("sha1", ""), index_info.get("size", 0))
        try:
            index = json.loads(index_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as err:
            raise LauncherError(f"Индекс ресурсов повреждён: {err}")

        objects: dict[str, dict[str, Any]] = index.get("objects", {})
        marker = self.assets / ".goldish-assets-ok"
        if marker.is_file():
            try:
                saved = json.loads(marker.read_text(encoding="utf-8"))
                if saved.get("index") == index_info["id"] and saved.get("count") == len(objects):
                    self.emit("Ресурсы", "уже загружены", 0.79)
                    return
            except (OSError, json.JSONDecodeError):
                pass

        objects_dir = self.assets / "objects"
        missing: list[tuple[str, dict[str, Any]]] = []
        for name, obj in objects.items():
            target = objects_dir / obj["hash"][:2] / obj["hash"]
            if not target.is_file() or target.stat().st_size != obj.get("size", -1):
                missing.append((name, obj))

        if not missing:
            marker.write_text(json.dumps({"index": index_info["id"], "count": len(objects)}), encoding="utf-8")
            self.emit("Ресурсы", "всё на месте", 0.79)
            return

        self.log(f"ассетов к загрузке: {len(missing)} из {len(objects)}")
        total = len(objects)
        done = total - len(missing)
        lock = threading.Lock()
        failures = 0
        last_update = [0.0]

        def fetch(obj: dict[str, Any]) -> None:
            nonlocal done, failures
            digest = obj["hash"]
            target = objects_dir / digest[:2] / digest
            try:
                self._download(f"{RESOURCES_URL}/{digest[:2]}/{digest}", target, digest, obj.get("size", 0))
            except LauncherError:
                with lock:
                    failures += 1
                return
            with lock:
                done += 1
                now = time.monotonic()
                if now - last_update[0] > 0.12 or done == total:
                    last_update[0] = now
                    fraction = 0.31 + 0.48 * done / total
                    self.emit("Ресурсы", f"{done} / {total}", fraction)

        with ThreadPoolExecutor(max_workers=10) as pool:
            futures = [pool.submit(fetch, obj) for _, obj in missing]
            for future in as_completed(futures):
                self.check_cancel()
                future.result()

        if failures:
            self.log(f"[!] не скачалось ассетов: {failures}")
            if failures > 50:
                raise LauncherError(f"Не удалось скачать {failures} файлов ресурсов. Проверьте интернет.")
        else:
            marker.write_text(json.dumps({"index": index_info["id"], "count": len(objects)}), encoding="utf-8")
        self.emit("Ресурсы", "готово", 0.79)

    # ------------------------------------------------------------- fabric
    def _ensure_fabric(self) -> dict[str, Any]:
        version_id = f"fabric-loader-{self.config.loader_version}-{self.config.mc_version}"
        cached = self.versions / version_id / f"{version_id}.json"
        profile: dict[str, Any] | None = None
        if cached.is_file():
            try:
                profile = json.loads(cached.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                profile = None
        if profile is None:
            url = f"{FABRIC_META}/versions/loader/{self.config.mc_version}/{self.config.loader_version}/profile/json"
            profile = self._fetch_json(url)
            cached.parent.mkdir(parents=True, exist_ok=True)
            cached.write_text(json.dumps(profile, ensure_ascii=False, indent=2), encoding="utf-8")
            self.log(f"Fabric {self.config.loader_version} для {self.config.mc_version} установлен")
        return profile

    # --------------------------------------------------------------- моды
    def _goldish_jar(self) -> Path | None:
        candidates: list[Path] = []
        project = ROOT.parent
        for libs in (project / "build" / "libs", ROOT / "build" / "libs", ROOT):
            if libs.is_dir():
                candidates.extend(p for p in libs.glob("goldish*.jar") if not p.name.endswith("-sources.jar"))
        existing = self.mods.glob("goldish*.jar") if self.mods.is_dir() else []
        candidates.extend(p for p in existing if not p.name.endswith("-sources.jar"))
        candidates = [p for p in candidates if p.is_file()]
        if not candidates:
            return None
        candidates.sort(key=lambda p: p.stat().st_mtime, reverse=True)
        return candidates[0]

    def _fabric_api_jar(self) -> Path | None:
        if self.mods.is_dir():
            existing = [p for p in self.mods.glob("fabric-api*.jar") if p.is_file()]
            if existing:
                return existing[0]
        self.log("ищу Fabric API для этой версии…")
        try:
            query = urllib.parse.urlencode({
                "game_versions": json.dumps([self.config.mc_version]),
                "loaders": json.dumps(["fabric"]),
            })
            versions = self._fetch_json(f"{MODRINTH_API}/project/fabric-api/version?{query}")
            if versions:
                version = versions[0]
                files = version.get("files") or []
                file = next((f for f in files if f.get("primary")), files[0] if files else None)
                if file:
                    target = self.mods / file["filename"]
                    self._download(file["url"], target, (file.get("hashes") or {}).get("sha1", ""),
                                   file.get("size", 0))
                    return target
        except (LauncherError, json.JSONDecodeError, KeyError) as err:
            self.log(f"[!] Modrinth: {err}")
        # запасной вариант — maven Fabric
        try:
            props = (ROOT.parent / "gradle.properties").read_text(encoding="utf-8")
            match = re.search(r"fabric_api_version=(.+)", props)
            api_version = match.group(1).strip() if match else f"0.141.6+{self.config.mc_version}"
            quoted = urllib.parse.quote(api_version, safe="+")
            path = f"net/fabricmc/fabric-api/fabric-api/{quoted}/fabric-api-{quoted}.jar"
            target = self.mods / f"fabric-api-{api_version}.jar"
            self._download(f"https://maven.fabricmc.net/{path}", target)
            return target
        except (OSError, LauncherError) as err:
            self.log(f"[!] Fabric API не установлен: {err}")
        return None

    def _ensure_mods(self) -> None:
        self.mods.mkdir(parents=True, exist_ok=True)
        goldish = self._goldish_jar()
        if goldish is not None:
            target = self.mods / goldish.name
            if not target.is_file() or target.stat().st_size != goldish.stat().st_size or \
                    target.stat().st_mtime < goldish.stat().st_mtime:
                shutil.copy2(goldish, target)
                self.log(f"Goldish установлен: {target.name}")
        else:
            self.log("[!] jar Goldish не найден — соберите клиент (gradlew build)")
        self._fabric_api_jar()

    # -------------------------------------------------------------- запуск
    def _classpath(self, profile: dict[str, Any]) -> list[Path]:
        entries: list[Path] = []
        client = self.versions / self.config.mc_version / f"{self.config.mc_version}.jar"
        if client.is_file():
            entries.append(client)
        seen: set[str] = set()
        for lib in list(self.meta.get("libraries", [])) + list(profile.get("libraries", [])):
            if not rules_allow(lib.get("rules")):
                continue
            try:
                target, _, _, _ = self._library_target(lib)
            except LauncherError:
                continue
            key = str(target).lower()
            if target.is_file() and key not in seen:
                seen.add(key)
                entries.append(target)
        return entries

    def build_command(self, server: str | None = None) -> list[str]:
        info = self.java()
        if info is None or not info.suitable:
            raise LauncherError("Не найдена подходящая Java 21+.")
        profile = self.profile
        if profile is None:
            version_id = f"fabric-loader-{self.config.loader_version}-{self.config.mc_version}"
            cached = self.versions / version_id / f"{version_id}.json"
            if not cached.is_file():
                raise LauncherError("Fabric Loader не установлен.")
            profile = json.loads(cached.read_text(encoding="utf-8"))
            self.profile = profile

        classpath = self._classpath(profile)
        if not classpath:
            raise LauncherError("Classpath пуст — клиент не установлен.")
        natives = self.versions / self.config.mc_version / "natives"
        asset_index = (self.meta.get("assetIndex") or {}).get("id", "")
        memory = max(1, int(self.config.memory_gb))
        nickname = (self.config.nickname or "Player").strip()
        main_class = profile.get("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient")

        cmd = [
            info.path,
            f"-Xmx{memory}G",
            f"-Xms{min(memory, 2)}G",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=50",
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            f"-Djava.library.path={natives}",
            "-Dminecraft.launcher.brand=Goldishka",
            "-Dminecraft.launcher.version=1.0",
        ]
        if self.config.jvm_args.strip():
            cmd.extend(self.config.jvm_args.split())
        cmd.extend(["-cp", os.pathsep.join(str(p) for p in classpath), main_class])
        cmd.extend([
            "--username", nickname,
            "--version", self.config.mc_version,
            "--gameDir", str(self.game),
            "--assetsDir", str(self.assets),
            "--assetIndex", asset_index,
            "--uuid", offline_uuid(nickname),
            "--accessToken", "0",
            "--userType", "legacy",
            "--versionType", "release",
        ])
        if self.config.game_args.strip():
            cmd.extend(self.config.game_args.split())
        if server:
            cmd.extend(["--quickPlayMultiplayer", server])
        return cmd

    def clean_remap_cache(self) -> bool:
        """Удаляет недописанный кеш remap Fabric, если он остался после сбоя.

        Fabric при первом запуске переразмечает клиент во временный jar. Если
        запуск прервался, рядом остаётся пустой ``*.tmp``, из-за которого все
        следующие запуски падают с «the jar file … can't be written».
        """
        cache = self.game / ".fabric" / "remappedJars"
        if not cache.is_dir():
            return False
        incomplete = any(cache.rglob("*.tmp"))
        if not incomplete:
            for jar in cache.rglob("*.jar"):
                try:
                    if jar.stat().st_size < 1024:
                        incomplete = True
                        break
                except OSError:
                    incomplete = True
                    break
        if not incomplete:
            return False
        for child in cache.iterdir():
            if child.is_dir():
                shutil.rmtree(child, ignore_errors=True)
            else:
                try:
                    child.unlink()
                except OSError:
                    pass
        return True

    def launch(self, server: str | None = None) -> subprocess.Popen:
        self.game.mkdir(parents=True, exist_ok=True)
        if self.clean_remap_cache():
            self.log("Кеш Fabric был повреждён — очищен, клиент подготовится заново.")
        cmd = self.build_command(server)
        self.log("запуск: " + " ".join(cmd[:3]) + " …")
        flags = 0
        if IS_WIN:
            flags = getattr(subprocess, "CREATE_NEW_CONSOLE", 0) if self.config.show_console else \
                getattr(subprocess, "CREATE_NO_WINDOW", 0)
        console = None
        stdout = None
        stderr = None
        if not self.config.show_console:
            if self._console_file is not None:
                try:
                    self._console_file.close()
                except OSError:
                    pass
                self._console_file = None
            logs = self.game / "logs"
            logs.mkdir(parents=True, exist_ok=True)
            console = (logs / "launcher-console.log").open(
                "w", encoding="utf-8", errors="replace")
            stdout = console
            stderr = subprocess.STDOUT
        self.process = subprocess.Popen(
            cmd,
            cwd=str(self.game),
            stdout=stdout,
            stderr=stderr,
            stdin=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            errors="replace",
            creationflags=flags,
        )
        self._console_file = console
        if console is not None:
            self.log("лог игры: logs/launcher-console.log")
        return self.process

    def stop(self) -> None:
        self.cancel_event.set()
        if self.process is not None and self.process.poll() is None:
            try:
                self.process.terminate()
            except OSError:
                pass

    @property
    def running(self) -> bool:
        return self.process is not None and self.process.poll() is None
