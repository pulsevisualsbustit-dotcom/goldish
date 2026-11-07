"""Инжект клиента в уже запущенный процесс Minecraft.

Как это работает
----------------
Minecraft — обычная JVM. Пока она жива, к ней можно подключиться через
Java Attach API (`com.sun.tools.attach.VirtualMachine`) и загрузить свой
агент через `VirtualMachine.loadAgent(path, options)`.

Агент — маленький JAR с `Agent-Class` в MANIFEST. При `loadAgent` JVM
вызывает `agentmain(String args, Instrumentation inst)` уже *внутри*
игры, и агент:

1. добавляет JAR клиента в системный classpath
   (`Instrumentation.appendToSystemClassLoaderSearch`);
2. обнуляет `MinecraftClient.instance`, чтобы ванильный клиент
   пересоздал себя уже с нашим модом.

Attach-процесс должен запускаться той же java.exe, что и игра — иначе
Attach API просто не найдёт таргет. Поэтому нужную java мы берём из
командной строки целевого процесса.

Все классы (`Attach.class`, `VeloraAgent.class`) лежат рядом
в `agent/classes/dev/velora/agent/` — на машине пользователя JDK
не требуется, только JRE игры.
"""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import shutil
import subprocess
import tempfile
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

_CREATE_NO_WINDOW = 0x08000000
_PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

_kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

_kernel32.OpenProcess.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
_kernel32.OpenProcess.restype = wt.HANDLE
_kernel32.CloseHandle.argtypes = [wt.HANDLE]
_kernel32.CloseHandle.restype = wt.BOOL
_kernel32.QueryFullProcessImageNameW.argtypes = [
    wt.HANDLE, wt.DWORD, wt.LPWSTR, ctypes.POINTER(wt.DWORD)
]
_kernel32.QueryFullProcessImageNameW.restype = wt.BOOL

AGENT_PACKAGE = "dev/velora/agent"
AGENT_DIR = Path(__file__).parent / "agent"


# --------------------------------------------------------------------------
# Модель процесса
# --------------------------------------------------------------------------

@dataclass
class JavaProcess:
    """Один живой java.exe с полезной информацией о Minecraft."""

    pid: int
    exe: str
    main_class: str = ""
    mc_version: str = ""
    game_dir: str = ""
    username: str = ""
    is_fabric: bool = False
    is_forge: bool = False
    has_our_mod: bool = False
    command_line: str = ""

    @property
    def label(self) -> str:
        who = self.username or "игрок"
        ver = self.mc_version or "Minecraft"
        loader = "Fabric" if self.is_fabric else ("Forge" if self.is_forge else "Vanilla")
        return f"{who} — {ver} · {loader} · PID {self.pid}"

    @property
    def client_jar(self) -> Path | None:
        """
        Находит в командной строке JAR нашего клиента.

        Наш мод обычно подключён через `-cp` или `-javaagent`, поэтому
        ищем любой путь, в имени которого есть `velora` или `pastacode`.
        """
        for token in self.command_line.replace(";", " ").split():
            clean = token.strip('"').strip()
            if not clean.lower().endswith(".jar"):
                continue
            low = clean.lower()
            if "velora" in low or "pastacode" in low:
                p = Path(clean)
                if p.exists():
                    return p
        return None


# --------------------------------------------------------------------------
# WinAPI
# --------------------------------------------------------------------------

def _query_image_path(pid: int) -> str:
    handle = _kernel32.OpenProcess(_PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        return ""
    try:
        size = wt.DWORD(32768)
        buf = ctypes.create_unicode_buffer(size.value)
        if _kernel32.QueryFullProcessImageNameW(handle, 0, buf, ctypes.byref(size)):
            return buf.value
        return ""
    finally:
        _kernel32.CloseHandle(handle)


def _iter_java_pids() -> list[int]:
    try:
        out = subprocess.check_output(
            ["tasklist", "/FI", "IMAGENAME eq java.exe", "/FO", "CSV", "/NH"],
            creationflags=_CREATE_NO_WINDOW,
            text=True,
            errors="ignore",
            timeout=10,
        )
    except Exception:
        out = ""

    pids: list[int] = []
    for line in out.splitlines():
        line = line.strip()
        if not line.startswith('"'):
            continue
        parts = [p.strip('"') for p in line.split('","')]
        if len(parts) >= 2 and parts[1].isdigit():
            pids.append(int(parts[1]))
    return pids


def _command_line(pid: int) -> str:
    script = (
        f"(Get-CimInstance Win32_Process -Filter \"ProcessId={pid}\")"
        ".CommandLine"
    )
    try:
        return (subprocess.check_output(
            ["powershell", "-NoProfile", "-NoLogo", "-Command", script],
            creationflags=_CREATE_NO_WINDOW,
            text=True,
            errors="ignore",
            timeout=20,
        ) or "").strip()
    except Exception:
        return ""


def _parse(cmd: str) -> dict:
    info: dict = {}
    tokens = cmd.split()

    for i, tok in enumerate(tokens):
        if i + 1 >= len(tokens):
            continue
        value = tokens[i + 1].strip('"')
        if tok == "--version":
            info["mc_version"] = value
        elif tok == "--gameDir":
            info["game_dir"] = value
        elif tok == "--username":
            info["username"] = value

    low = cmd.lower()
    for tok in tokens:
        t = tok.lower()
        if "net.minecraft.client.main.main" in t:
            info["main_class"] = tok
        elif "knotclient" in t:
            info["main_class"] = tok
            info["is_fabric"] = True
        elif "bootstraplauncher" in t:
            info["main_class"] = tok
            info["is_forge"] = True

    if "fabric" in low:
        info["is_fabric"] = True
    if "forge" in low or "bootstraplauncher" in low:
        info["is_forge"] = True
    if "velora" in low or "pastacode" in low:
        info["has_our_mod"] = True

    return info


def find_minecraft_processes() -> list[JavaProcess]:
    """Все найденные запущенные процессы Minecraft."""
    found: list[JavaProcess] = []

    for pid in _iter_java_pids():
        exe = _query_image_path(pid)
        if not exe:
            continue

        cmd = _command_line(pid)
        if not cmd:
            continue

        low = cmd.lower()
        looks_like_mc = (
            "net.minecraft.client.main.main" in low
            or "knotclient" in low
            or ("minecraft" in low and "bootstraplauncher" in low)
        )
        if not looks_like_mc:
            continue

        info = _parse(cmd)
        found.append(JavaProcess(
            pid=pid,
            exe=exe,
            main_class=info.get("main_class", ""),
            mc_version=info.get("mc_version", ""),
            game_dir=info.get("game_dir", ""),
            username=info.get("username", ""),
            is_fabric=bool(info.get("is_fabric")),
            is_forge=bool(info.get("is_forge")),
            has_our_mod=bool(info.get("has_our_mod")),
            command_line=cmd,
        ))

    return found


# --------------------------------------------------------------------------
# Сборка агента и аттач
# --------------------------------------------------------------------------

def build_agent_jar(destination: Path) -> Path:
    """Собирает agent-JAR из уже скомпилированных классов."""
    classes_dir = AGENT_DIR / "classes"
    agent_class = classes_dir / AGENT_PACKAGE.replace("/", "\\") / "VeloraAgent.class"
    attach_class = classes_dir / AGENT_PACKAGE.replace("/", "\\") / "Attach.class"

    if not agent_class.is_file() or not attach_class.is_file():
        raise FileNotFoundError(
            "Классы агента не найдены. Соберите их: "
            "javac loader/goldishka/core/agent/VeloraAgent.java "
            "loader/goldishka/core/agent/Attach.java"
        )

    manifest = (
        "Manifest-Version: 1.0\r\n"
        "Agent-Class: dev.velora.agent.VeloraAgent\r\n"
        "Can-Redefine-Classes: false\r\n"
        "Can-Retransform-Classes: false\r\n"
        "\r\n"
    ).encode()

    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF", manifest)
        z.write(agent_class, f"{AGENT_PACKAGE}/VeloraAgent.class")
        z.write(attach_class, f"{AGENT_PACKAGE}/Attach.class")

    return destination


def _java_major(java_exe: str) -> int:
    """Определяет мажорную версию Java (8, 17, 21 …)."""
    try:
        out = subprocess.check_output(
            [java_exe, "-version"],
            stderr=subprocess.STDOUT,
            text=True,
            errors="ignore",
            creationflags=_CREATE_NO_WINDOW,
            timeout=15,
        )
    except Exception:
        return 0

    import re

    match = re.search(r'version "(\d+)(?:\.(\d+))?', out)
    if not match:
        return 0
    major = int(match.group(1))
    # Старый формат 1.8.0_xxx — реальная версия вторая цифра.
    if major == 1 and match.group(2):
        return int(match.group(2))
    return major


def inject_into(proc: JavaProcess, mod_jar: Path, timeout: float = 90.0) -> str:
    """
    Подключается к процессу и загружает агент.

    Args:
        proc: целевой процесс Minecraft.
        mod_jar: JAR нашего клиента, который нужно подсунуть игре.
        timeout: сколько ждать ответа от attach-процесса.

    Returns:
        Лог attach-процесса.
    """
    if not Path(proc.exe).exists():
        raise RuntimeError(f"java.exe не найдена: {proc.exe}")
    if not Path(mod_jar).is_file():
        raise RuntimeError(f"JAR клиента не найден: {mod_jar}")

    work = Path(tempfile.mkdtemp(prefix="velora-inject-"))
    agent_jar = build_agent_jar(work / "velora-agent.jar")

    options = f"modJar={Path(mod_jar).resolve()}"

    java_args = [
        proc.exe,
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
    ]

    # jdk.attach на JDK 9+ — отдельный модуль и доступен только по запросу;
    # на JDK 8 такой флаг просто не существует.
    if _java_major(proc.exe) >= 9:
        java_args += ["--add-modules", "jdk.attach"]

    java_args += [
        "-cp", str(agent_jar),
        "dev.velora.agent.Attach",
        str(proc.pid), str(agent_jar), options,
    ]

    proc_run = subprocess.Popen(
        java_args,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        creationflags=_CREATE_NO_WINDOW,
    )

    try:
        out, _ = proc_run.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc_run.kill()
        out, _ = proc_run.communicate()
        raise RuntimeError(
            "Таймаут инжекта. Обычно это значит, что игра не отвечает, "
            "либо java у игры и у аттача различаются.\n\n" + (out or "")
        )

    if proc_run.returncode != 0:
        raise RuntimeError(_translate_output(out) or "Attach завершился с ошибкой")

    shutil.rmtree(work, ignore_errors=True)
    return _translate_output(out) or "Агент загружен."


# Этапы, которые присылает Attach.java (латиницей, чтобы консоль Windows
# ни при каких кодовых страницах не превращала их в мусор).
_STAGE_TEXT = {
    "stage=attach": "Подключаемся к JVM…",
    "stage=load": "Загружаем агент…",
    "stage=ok": "Агент успешно загружен. Вернитесь в игру.",
    "stage=failed": "Инжект не удался",
}

# Сообщения самого агента (VeloraAgent), тоже машинно-читаемые.
_AGENT_TEXT = {
    "agent=cp-added": "Мод добавлен в classpath",
    "agent=already-early": "Клиент ещё не создан — мод подхватится сам",
    "agent=instance-reset": "Клиент перезапускается с модом",
    "agent-error=cp-failed": "Не удалось добавить мод в classpath",
    "agent-error=no-modjar": "Не передан путь к JAR клиента",
    "agent-error=no-mcclass": "MinecraftClient не найден (это не Minecraft?)",
    "agent-error=no-instance-field": "Поле instance не найдено (другая обфускация)",
    "agent-error=reset-failed": "Ошибка при сбросе клиента",
}


def _translate_output(raw: str) -> str:
    """Превращает машинный лог аттача и агента в читабельные строки."""
    if not raw:
        return ""

    lines: list[str] = []
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue

        first = line.split()[0]

        # Точные совпадения по всей строке (без хвоста).
        if line in _AGENT_TEXT:
            lines.append(_AGENT_TEXT[line])
            continue

        tail = line[len(first):].strip()

        if first in _STAGE_TEXT:
            text = _STAGE_TEXT[first]
            lines.append(f"{text} " + tail if tail else text)
            continue

        # Сообщения агента с хвостом (путь, текст исключения).
        if first in _AGENT_TEXT:
            text = _AGENT_TEXT[first]
            lines.append(f"{text}: {tail}" if tail else text)
            continue

        if first.startswith("agent-error="):
            text = _AGENT_TEXT.get(first, first)
            lines.append(f"{text}: {tail}" if tail else text)
            continue

        if first.startswith("agent="):
            lines.append(tail or first)
            continue

        if line.startswith("reason="):
            lines.append("Причина: " + line[len("reason="):])
            continue

        # Прочие строки (трассировка, прочие выводы) оставляем как есть.
        lines.append(line)

    return "\n".join(lines)