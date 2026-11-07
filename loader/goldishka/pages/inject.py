"""Страница «Инжект» — подгрузка клиента в уже запущенный Minecraft.

Игроку не нужно перезапускать игру через лаунчер: страница находит живые
процессы Minecraft, предлагает выбрать нужный и вливает в него наш клиент
через Java-агент.
"""

from __future__ import annotations

from pathlib import Path

from PyQt5.QtCore import Qt, QTimer
from PyQt5.QtWidgets import (
    QFileDialog,
    QHBoxLayout,
    QPlainTextEdit,
    QSizePolicy,
    QVBoxLayout,
    QWidget,
)

from ..theme import DIM, GREEN, MUTED, TEXT
from ..ui.widgets import (
    Divider,
    GlassCard,
    GoldButton,
    HoverRow,
    PageScroll,
    label,
    muted,
)
from ..ui.workers import InjectWorker, ScanProcessesWorker
from .base import BasePage


def _dot_css(color: str) -> str:
    return f"background: {color}; border-radius: 4px;"


class ProcessRow(HoverRow):
    """Одна строка списка найденных процессов Minecraft."""

    def __init__(self, proc, parent: QWidget | None = None):
        super().__init__(parent)
        self.proc = proc
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedHeight(48)

        lay = QHBoxLayout(self)
        lay.setContentsMargins(12, 0, 12, 0)
        lay.setSpacing(10)

        self.dot = QWidget()
        self.dot.setFixedSize(8, 8)
        self.dot.setStyleSheet(_dot_css(MUTED))
        lay.addWidget(self.dot)

        lay.addWidget(label(proc.label, 12, TEXT, bold=True), 1)

        state = "клиент уже внутри" if proc.has_our_mod else "готов к инжекту"
        color = GREEN if proc.has_our_mod else DIM
        badge = muted(state, 10)
        badge.setStyleSheet(f"color: {color}; background: transparent;")
        lay.addWidget(badge)

    def set_selected(self, value: bool) -> None:  # noqa: N802
        super().set_selected(value)
        self.dot.setStyleSheet(_dot_css(GREEN if value else MUTED))


class InjectPage(BasePage):
    """Список процессов Minecraft + кнопка инжекта."""

    def __init__(self, app):
        super().__init__(
            app,
            "Инжект в Minecraft",
            "Подгружает клиент в уже открытую игру — перезапуск не нужен",
            icon="sparkle",
        )

        self._processes: list = []
        self._rows: list[ProcessRow] = []
        self._selected = None
        self._scan_worker: ScanProcessesWorker | None = None
        self._inject_worker: InjectWorker | None = None
        self._mod_jar: Path | None = self._default_jar()

        self.rescan = GoldButton("Обновить", "secondary", "refresh", height=34)
        self.rescan.clicked.connect(self.refresh)
        self.add_action(self.rescan)

        body = QWidget()
        root = QVBoxLayout(body)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(14)

        # ------------------------------------------------ список процессов
        self.list_card = GlassCard()
        self.list_lay = QVBoxLayout()
        self.list_lay.setContentsMargins(0, 0, 0, 0)
        self.list_lay.setSpacing(4)
        self.list_card.body.addLayout(self.list_lay)

        self.empty = muted(
            "Процессы Minecraft не найдены. Запустите игру и нажмите «Обновить».",
            12,
        )
        self.empty.setAlignment(Qt.AlignCenter)
        self.empty.setMinimumHeight(64)
        self.list_lay.addWidget(self.empty)
        root.addWidget(self.list_card)

        # ------------------------------------------------ выбор JAR
        jar_card = GlassCard()
        jar_lay = QHBoxLayout()
        jar_lay.setSpacing(10)
        jar_info = QVBoxLayout()
        jar_info.setSpacing(2)
        jar_info.addWidget(label("JAR клиента", 12, TEXT, bold=True))
        self.jar_label = muted(
            str(self._mod_jar) if self._mod_jar else "не выбран", 11
        )
        self.jar_label.setWordWrap(False)
        self.jar_label.setTextInteractionFlags(Qt.TextSelectableByMouse)
        # Длинный путь не должен наползать на кнопку «Выбрать».
        self.jar_label.setMinimumWidth(0)
        self.jar_label.setSizePolicy(QSizePolicy.Ignored, QSizePolicy.Preferred)
        jar_info.addWidget(self.jar_label)
        jar_lay.addLayout(jar_info, 1)

        pick = GoldButton("Выбрать", "secondary", "folder", height=32)
        pick.clicked.connect(self._pick_jar)
        jar_lay.addWidget(pick)
        jar_card.body.addLayout(jar_lay)
        root.addWidget(jar_card)

        root.addWidget(Divider())

        # ------------------------------------------------ действие
        action = QHBoxLayout()
        action.setSpacing(10)

        self.inject_btn = GoldButton(
            "Инжектить в выбранный процесс", "primary", "play", height=44
        )
        self.inject_btn.setEnabled(False)
        self.inject_btn.clicked.connect(self._start_inject)
        action.addWidget(self.inject_btn)

        self.status = muted("Выберите процесс из списка", 12)
        action.addWidget(self.status, 1)
        root.addLayout(action)

        # ------------------------------------------------ лог
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setMinimumHeight(150)
        self.log.setStyleSheet(
            "QPlainTextEdit {"
            "  background: rgba(0,0,0,60);"
            f"  color: {MUTED};"
            "  border: 1px solid rgba(255,255,255,18);"
            "  border-radius: 10px;"
            "  padding: 8px;"
            "  font-family: Consolas, monospace;"
            "  font-size: 11px;"
            "}"
        )
        self.log.setPlaceholderText("Лог инжекта появится здесь…")
        root.addWidget(self.log)

        self.scroll.add(body)
        QTimer.singleShot(250, self.refresh)

    # ------------------------------------------------------------------ утилиты

    def _default_jar(self) -> Path | None:
        """Берёт собранный клиент из стандартных мест."""
        candidates: list[Path] = []

        config = getattr(self.app, "config", None)
        if config is not None and hasattr(config, "mods_path"):
            candidates.append(Path(config.mods_path))

        # Собранный мод в соседней папке build/libs (запуск из исходников).
        candidates.append(Path(__file__).resolve().parents[3] / "build" / "libs")

        for folder in candidates:
            if not folder.is_dir():
                continue

            jars = [p for p in folder.glob("*.jar") if p.is_file()]
            if not jars:
                continue

            # Сначала ищем наш клиент, а не соседние моды (fabric-api и т.п.).
            ours = [
                p for p in jars
                if "velora" in p.name.lower() or "goldish" in p.name.lower()
            ]
            pool = ours or jars
            pool.sort(key=lambda p: p.stat().st_mtime, reverse=True)
            return pool[0]

        return None

    def _pick_jar(self) -> None:
        start = str(self._mod_jar.parent) if self._mod_jar else ""
        path, _ = QFileDialog.getOpenFileName(
            self, "Выберите JAR клиента", start, "Java (*.jar)"
        )
        if path:
            self._mod_jar = Path(path)
            self.jar_label.setText(path)
            self._sync_button()

    def _sync_button(self) -> None:
        ready = (
            self._selected is not None
            and self._mod_jar is not None
            and self._mod_jar.is_file()
            and self._inject_worker is None
        )
        self.inject_btn.setEnabled(ready)

    # ------------------------------------------------------------------ скан

    def refresh(self) -> None:
        if self._scan_worker is not None and self._scan_worker.isRunning():
            return
        self.rescan.setEnabled(False)
        self.status.setText("Ищем процессы Minecraft…")

        self._scan_worker = ScanProcessesWorker(self)
        self._scan_worker.done.connect(self._on_scan_done)
        self._scan_worker.failed.connect(self._on_scan_failed)
        self._scan_worker.finished.connect(lambda: self.rescan.setEnabled(True))
        self._scan_worker.start()

    def on_show(self) -> None:
        self.refresh()

    def _on_scan_failed(self, message: str) -> None:
        self.status.setText(f"Ошибка поиска: {message}")

    def _on_scan_done(self, processes) -> None:
        self._processes = list(processes)

        for row in self._rows:
            self.list_lay.removeWidget(row)
            row.setParent(None)
            row.deleteLater()
        self._rows.clear()

        self.empty.setVisible(not self._processes)

        for proc in self._processes:
            row = ProcessRow(proc)
            row.clicked.connect(lambda p=proc: self._select(p))
            self.list_lay.addWidget(row)
            self._rows.append(row)

        if self._processes:
            self.status.setText(
                f"Найдено процессов: {len(self._processes)}. Выберите нужный."
            )
        else:
            self.status.setText("Процессы не найдены. Запустите игру и обновите.")

        keep = next(
            (p for p in self._processes
             if self._selected is not None and p.pid == self._selected.pid),
            None,
        )
        if keep is not None:
            self._select(keep)
        elif self._processes:
            self._select(self._processes[0])
        else:
            self._select(None)

    def _select(self, proc) -> None:
        self._selected = proc
        for row in self._rows:
            row.set_selected(proc is not None and row.proc.pid == proc.pid)
        if proc is not None:
            state = "клиент уже в процессе" if proc.has_our_mod else "готов к инжекту"
            self.status.setText(f"Выбран PID {proc.pid} — {state}")
        self._sync_button()

    # ------------------------------------------------------------------ инжект

    def _start_inject(self) -> None:
        if self._selected is None or self._mod_jar is None:
            return
        if self._inject_worker is not None and self._inject_worker.isRunning():
            return

        self.log.clear()
        self.inject_btn.setEnabled(False)
        self.inject_btn.setText("Инжектим…")
        self.status.setText("Инжект идёт, не закрывайте игру…")

        self._inject_worker = InjectWorker(self._selected, self._mod_jar, self)
        self._inject_worker.log.connect(self._append_log)
        self._inject_worker.done.connect(self._on_inject_done)
        self._inject_worker.failed.connect(self._on_inject_failed)
        self._inject_worker.finished.connect(self._on_inject_finished)
        self._inject_worker.start()

    def _append_log(self, line: str) -> None:
        self.log.appendPlainText(line)

    def _on_inject_done(self, output: str) -> None:
        self.status.setText(
            "Готово. Вернитесь в игру — клиент подгрузится автоматически."
        )
        self._append_log("")
        self._append_log("✓ Инжект завершён успешно.")

    def _on_inject_failed(self, message: str) -> None:
        self.status.setText("Инжект не удался — подробности в логе.")
        self._append_log("")
        for line in message.splitlines():
            self._append_log(f"✗ {line}")

    def _on_inject_finished(self) -> None:
        self.inject_btn.setText("Инжектить в выбранный процесс")
        self._inject_worker = None
        self._sync_button()