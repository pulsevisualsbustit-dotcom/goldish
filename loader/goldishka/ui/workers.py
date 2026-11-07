"""Фоновые задачи: пинг серверов, установка и запуск клиента."""

from __future__ import annotations

import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from PyQt5.QtCore import QObject, QThread, pyqtSignal

from ..core import java as javalib
from ..core import injector
from ..core.injector import inject_into
from ..core.config import LoaderConfig
from ..core.launcher import Cancelled, MinecraftLauncher
from ..core.servers import PingResult, ping


class PingWorker(QThread):
    result = pyqtSignal(int, object)

    def __init__(self, entries: list[tuple[int, str]], parent=None):
        super().__init__(parent)
        self.entries = entries
        self._stop = False

    def stop(self) -> None:
        self._stop = True

    def run(self) -> None:
        with ThreadPoolExecutor(max_workers=6) as pool:
            futures = {pool.submit(ping, address): index for index, address in self.entries}
            for future in as_completed(futures):
                if self._stop:
                    break
                index = futures[future]
                try:
                    result = future.result()
                except Exception as err:  # noqa: BLE001 - пинг не должен ронять поток
                    result = PingResult(error=str(err))
                self.result.emit(index, result)


class LaunchWorker(QObject):
    """Установка и запуск в демон-потоке, чтобы окно можно было закрыть."""

    progress = pyqtSignal(str, str, float)
    log = pyqtSignal(str)
    launched = pyqtSignal()
    failed = pyqtSignal(str)
    crashed = pyqtSignal(str)
    finished = pyqtSignal()

    def __init__(self, config: LoaderConfig, server: str | None = None, parent=None):
        super().__init__(parent)
        self.config = config
        self.server = server
        self.launcher: MinecraftLauncher | None = None
        self._cancel = False
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="goldish-launch", daemon=True)
        self._thread.start()

    def _run(self) -> None:
        launcher = MinecraftLauncher(
            self.config,
            on_progress=lambda stage, detail, fraction: self.progress.emit(stage, detail, fraction),
            on_log=lambda line: self.log.emit(line),
        )
        self.launcher = launcher
        try:
            launcher.install()
            launcher.launch(self.server)
            self.launched.emit()
            threading.Thread(
                target=self._watch, args=(launcher, True),
                name="goldish-watch", daemon=True,
            ).start()
        except Cancelled:
            self.failed.emit("Запуск отменён")
        except Exception as err:  # noqa: BLE001 - показываем текст в интерфейсе
            self.failed.emit(str(err))
        finally:
            self.finished.emit()

    def _console_tail(self, limit: int = 8000) -> str:
        log = Path(self.config.game_path) / "logs" / "launcher-console.log"
        try:
            return log.read_text(encoding="utf-8", errors="replace")[-limit:]
        except OSError:
            return ""

    def _watch(self, launcher: MinecraftLauncher, retry: bool) -> None:
        """Следит за игрой: лечит сбой remap и сообщает о падении на старте."""
        proc = launcher.process
        if proc is None:
            return
        started = time.monotonic()
        code = proc.wait()
        if self._cancel or launcher.cancel_event.is_set():
            return
        elapsed = time.monotonic() - started
        tail = self._console_tail()
        remap_failed = "error remapping game jars" in tail or "Incomplete remapped file" in tail
        if remap_failed and retry:
            self.log.emit("Fabric не смог подготовить клиент — очищаю кеш и запускаю заново…")
            launcher.clean_remap_cache()
            try:
                launcher.launch(self.server)
            except Exception as err:  # noqa: BLE001
                self.crashed.emit(f"Повторный запуск не удался: {err}")
                return
            self._watch(launcher, retry=False)
            return
        if code != 0 and elapsed < 120:
            self.crashed.emit(self._crash_message(code, tail))

    @staticmethod
    def _crash_message(code: int, tail: str) -> str:
        if "error remapping game jars" in tail:
            return ("Fabric не смог переразметить клиент. Перезапустите лаунчер "
                    "и нажмите «Играть» ещё раз.")
        if "UnsupportedClassVersionError" in tail:
            return "Нужна Java 21 — выбранная версия Java не подходит для клиента."
        if "OutOfMemoryError" in tail:
            return "Игре не хватило памяти — увеличьте её в «Параметрах запуска»."
        if "Incompatible mod set" in tail or "ModResolutionException" in tail:
            return "Конфликт модов — отключите несовместимые моды на странице «Моды»."
        return f"Клиент закрылся с ошибкой (код {code}). Лог: game/logs/launcher-console.log"

    def cancel(self) -> None:
        self._cancel = True
        if self.launcher is not None:
            self.launcher.stop()

    def isRunning(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def wait(self, msecs: int = 3000) -> bool:
        if self._thread is None:
            return True
        self._thread.join(max(0, msecs) / 1000)
        return not self._thread.is_alive()


class PreflightWorker(QThread):
    step = pyqtSignal(str, float)
    done = pyqtSignal(object)

    def __init__(self, config: LoaderConfig, parent=None):
        super().__init__(parent)
        self.config = config

    def run(self) -> None:
        self.step.emit("Проверяем папки", 0.12)
        try:
            self.config.ensure_dirs()
        except OSError:
            pass
        self.step.emit("Ищем Java 21", 0.38)
        info = javalib.find_best(self.config.java)
        self.step.emit("Проверяем файлы клиента", 0.68)
        launcher = MinecraftLauncher(self.config)
        try:
            launcher.components()
        except Exception:  # noqa: BLE001
            pass
        self.step.emit("Готово", 1.0)
        self.done.emit(info)


class ScanProcessesWorker(QThread):
    """Ищет запущенные процессы Minecraft в фоне."""

    done = pyqtSignal(object)
    failed = pyqtSignal(str)

    def run(self) -> None:
        try:
            procs = injector.find_minecraft_processes()
            self.done.emit(procs)
        except Exception as exc:  # noqa: BLE001
            self.failed.emit(str(exc))


class InjectWorker(QThread):
    """Загружает агент клиента в живой процесс игры."""

    log = pyqtSignal(str)
    done = pyqtSignal(str)
    failed = pyqtSignal(str)

    def __init__(self, process, mod_jar, parent=None):
        super().__init__(parent)
        self.process = process
        self.mod_jar = mod_jar

    def run(self) -> None:
        try:
            self.log.emit(f"Подключаемся к PID {self.process.pid} ...")
            output = inject_into(
                self.process,
                self.mod_jar,
                timeout=90.0,
            )
            for line in (output or "").splitlines():
                if line.strip():
                    self.log.emit(line.rstrip())
            self.done.emit(output)
        except Exception as exc:  # noqa: BLE001
            self.failed.emit(str(exc))
