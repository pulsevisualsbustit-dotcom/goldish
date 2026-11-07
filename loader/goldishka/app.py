"""Главное окно Goldishka Loader и точка входа."""

from __future__ import annotations

import ctypes
import os
import random
import subprocess
import sys
import traceback
from ctypes import wintypes
from pathlib import Path
from typing import Any

from PyQt5.QtCore import QPoint, QPointF, QRectF, QSize, Qt, QTimer, pyqtSignal
from PyQt5.QtGui import (
    QColor,
    QFont,
    QIcon,
    QLinearGradient,
    QPainter,
    QPainterPath,
    QPen,
    QPixmap,
    QRadialGradient,
)
from PyQt5.QtWidgets import (
    QApplication,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
)

from .core import java as javalib
from .core.client_data import ClientData, read_client_data
from .core.config import CONFIG_FILE, LoaderConfig, total_ram_gb
from .core.launcher import MinecraftLauncher
from .core.servers import PingResult, ServerEntry, ServerStore
from .pages.appearance import AppearancePage
from .pages.home import HomePage
from .pages.inject import InjectPage
from .pages.launch import LaunchPage
from .pages.mods import ModsPage
from .pages.packs import PacksPage
from .pages.play import PlayPage
from .pages.settings import SettingsPage
from .theme import (
    ASSETS,
    DIM,
    GREEN,
    MUTED,
    RED,
    TEXT,
    THEME,
    YELLOW,
    app_qss,
    font,
    heading,
)
from .ui.assets import paint_cover, pixmap, scaled_cover
from .ui.dialogs import ProfileDialog
from .ui.widgets import (
    Avatar,
    Chip,
    ConfirmDialog,
    GlassCard,
    GoldButton,
    HoverRow,
    IconButton,
    NavButton,
    ProgressBar,
    StatusDot,
    Toast,
    _animate,
    apply_shadow,
    h1,
    h2,
    label,
    muted,
    rounded,
)
from .ui.workers import LaunchWorker, PingWorker, PreflightWorker

# Держим ссылку на QApplication до завершения интерпретатора. Если оставить её
# только локальной в main(), Python уничтожает приложение при выходе из функции
# раньше, чем окно и вложенные объекты Qt, и процесс падает с 0xC0000409.
_APP: QApplication | None = None

NAV_ITEMS: list[tuple[str, str, str, str | None]] = [
    ("home", "Главная", "home", "ИГРА"),
    ("play", "Играть", "play", None),
    ("inject", "Инжект", "sparkle", None),
    ("launch", "Параметры запуска", "sliders", None),
    ("mods", "Моды", "mods", "КОНТЕНТ"),
    ("packs", "Ресурспаки", "packs", None),
    ("settings", "Настройки", "settings", "ЛАУНЧЕР"),
    ("appearance", "Внешний вид", "appearance", None),
]


def _section_label(text: str) -> QLabel:
    w = QLabel(text)
    f = font(9, QFont.DemiBold)
    f.setLetterSpacing(QFont.AbsoluteSpacing, 1.5)
    w.setFont(f)
    w.setStyleSheet(f"color: {DIM}; background: transparent;")
    return w


class ProfileCard(HoverRow):
    def __init__(self, app: "MainWindow", parent: QWidget | None = None):
        super().__init__(parent, radius=12)
        self.app = app
        lay = QHBoxLayout(self)
        lay.setContentsMargins(8, 7, 6, 7)
        lay.setSpacing(10)
        self.avatar = Avatar(app.config.nickname, 36)
        lay.addWidget(self.avatar)
        text = QVBoxLayout()
        text.setSpacing(1)
        self.name = label(app.config.nickname or "Игрок", 12, TEXT, bold=True)
        text.addWidget(self.name)
        text.addWidget(muted("Оффлайн", 9))
        lay.addLayout(text, 1)
        edit = IconButton("edit", size=26, icon_size=12, tooltip="Изменить профиль")
        edit.clicked.connect(self._open)
        lay.addWidget(edit)
        self.setFixedHeight(56)
        self.clicked.connect(self._open)

    def _open(self) -> None:
        ProfileDialog(self.app).show_centered()

    def refresh(self) -> None:
        self.avatar.set_name(self.app.config.nickname)
        self.name.setText(self.app.config.nickname or "Игрок")


class Sidebar(QWidget):
    def __init__(self, app: "MainWindow", parent: QWidget | None = None):
        super().__init__(parent)
        self.app = app
        self.setFixedWidth(252)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 18, 16, 16)
        lay.setSpacing(2)

        logo_row = QHBoxLayout()
        logo_row.setSpacing(10)
        logo = QLabel()
        pm = QPixmap(str(ASSETS / "logo.png"))
        logo.setPixmap(pm.scaled(44, 44, Qt.KeepAspectRatio, Qt.SmoothTransformation))
        logo.setFixedSize(44, 44)
        logo.setStyleSheet("background: transparent;")
        logo_row.addWidget(logo)
        word = QVBoxLayout()
        word.setSpacing(0)
        title = QLabel("GOLDISHKA")
        title.setFont(heading(17))
        title.setStyleSheet(f"color: {TEXT}; background: transparent;")
        word.addWidget(title)
        client = QLabel("CLIENT")
        f = font(9, QFont.DemiBold)
        f.setLetterSpacing(QFont.AbsoluteSpacing, 3.2)
        client.setFont(f)
        client.setStyleSheet(f"color: {THEME.accent.name()}; background: transparent;")
        word.addWidget(client)
        logo_row.addLayout(word, 1)
        lay.addLayout(logo_row)
        lay.addSpacing(8)

        version_row = QHBoxLayout()
        version_row.setSpacing(5)
        self.version_chip = Chip(f"{app.config.mc_version}", THEME.accent, size=9)
        version_row.addWidget(self.version_chip)
        self.fabric_chip = Chip(f"Fabric {app.config.loader_version}", QColor(MUTED), size=9)
        version_row.addWidget(self.fabric_chip)
        version_row.addStretch(1)
        lay.addLayout(version_row)
        lay.addSpacing(14)

        self.nav: dict[str, NavButton] = {}
        for key, title, icon, group in NAV_ITEMS:
            if group:
                lay.addSpacing(10)
                lay.addWidget(_section_label(group))
                lay.addSpacing(3)
            button = NavButton(title, icon)
            button.clicked.connect(lambda _=False, k=key: self.app.open_page(k))
            lay.addWidget(button)
            self.nav[key] = button

        lay.addStretch(1)
        self.profile = ProfileCard(app)
        lay.addWidget(self.profile)
        footer = QHBoxLayout()
        footer.setContentsMargins(4, 6, 4, 0)
        footer.addWidget(muted("Loader 1.0.0", 9))
        footer.addStretch(1)
        self.status_dot = StatusDot(GREEN, 5)
        footer.addWidget(self.status_dot)
        lay.addLayout(footer)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect())
        path = rounded(rect, 18)
        g = QLinearGradient(rect.topLeft(), rect.bottomLeft())
        g.setColorAt(0.0, QColor(20, 16, 10, 250))
        g.setColorAt(0.55, QColor(15, 12, 8, 246))
        g.setColorAt(1.0, QColor(11, 9, 6, 250))
        p.fillPath(path, g)
        glow = QRadialGradient(QPointF(rect.width() * 0.3, 30), 280)
        glow.setColorAt(0.0, THEME.accent_a(34))
        glow.setColorAt(1.0, QColor(0, 0, 0, 0))
        p.fillPath(path, glow)
        p.setPen(QPen(QColor(255, 255, 255, 18), 1))
        p.setBrush(Qt.NoBrush)
        p.drawLine(QPointF(rect.right() - 0.5, 18), QPointF(rect.right() - 0.5, rect.height() - 18))
        p.end()

    def refresh(self) -> None:
        self.profile.refresh()
        self.version_chip.setText(self.app.config.mc_version)
        self.fabric_chip.setText(f"Fabric {self.app.config.loader_version}")


class TitleBar(QWidget):
    def __init__(self, app: "MainWindow", parent: QWidget | None = None):
        super().__init__(parent)
        self.app = app
        self._drag_offset: QPoint | None = None
        self.setFixedHeight(42)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(2, 0, 0, 0)
        lay.setSpacing(8)
        self.health_dot = StatusDot(GREEN, 6)
        lay.addWidget(self.health_dot)
        self.health = label("Проверяем файлы…", 10, MUTED)
        lay.addWidget(self.health)
        lay.addStretch(1)
        self.server_chip = Chip("Серверы: —", THEME.accent, dot=True, size=9)
        lay.addWidget(self.server_chip)
        lay.addSpacing(6)
        minimize = IconButton("minimize", size=32, icon_size=13, tooltip="Свернуть")
        minimize.clicked.connect(app.showMinimized)
        lay.addWidget(minimize)
        maximize = IconButton("monitor", size=32, icon_size=13, tooltip="Развернуть")
        maximize.clicked.connect(app.toggle_maximize)
        lay.addWidget(maximize)
        close = IconButton("close", size=32, icon_size=13, tooltip="Закрыть")
        close.clicked.connect(app.close)
        lay.addWidget(close)

    def mousePressEvent(self, event) -> None:  # noqa: N802
        if event.button() == Qt.LeftButton:
            self._drag_offset = event.globalPos() - self.window().frameGeometry().topLeft()
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        if self._drag_offset is not None and event.buttons() & Qt.LeftButton:
            if self.window().isMaximized():
                self.app.showNormal()
                self._drag_offset = QPoint(self.width() // 2, 18)
            self.window().move(event.globalPos() - self._drag_offset)
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        self._drag_offset = None
        super().mouseReleaseEvent(event)

    def mouseDoubleClickEvent(self, event) -> None:  # noqa: N802
        if event.button() == Qt.LeftButton:
            self.app.toggle_maximize()
        super().mouseDoubleClickEvent(event)


class LaunchOverlay(QWidget):
    cancelled = pyqtSignal()

    def __init__(self, app: "MainWindow"):
        super().__init__(app)
        self.app = app
        self._done = False
        self.setGeometry(app.rect())
        self.setAttribute(Qt.WA_NoSystemBackground)
        self.setAttribute(Qt.WA_StyledBackground, False)
        self.hide()

        outer = QVBoxLayout(self)
        outer.setContentsMargins(40, 30, 40, 30)
        outer.addStretch(1)
        row = QHBoxLayout()
        row.addStretch(1)
        self.card = GlassCard(radius=18)
        self.card.setFixedWidth(650)
        apply_shadow(self.card, 44, 180, 14)
        body = QVBoxLayout(self.card)
        body.setContentsMargins(26, 22, 26, 22)
        body.setSpacing(12)

        head = QHBoxLayout()
        head.setSpacing(12)
        logo = QLabel()
        pm = QPixmap(str(ASSETS / "logo_big.png"))
        logo.setPixmap(pm.scaled(52, 52, Qt.KeepAspectRatio, Qt.SmoothTransformation))
        logo.setFixedSize(52, 52)
        head.addWidget(logo)
        titles = QVBoxLayout()
        titles.setSpacing(1)
        titles.addWidget(h1("Запуск Goldish", 20))
        self.stage = muted("Подготовка…", 11)
        titles.addWidget(self.stage)
        head.addLayout(titles, 1)
        self.status_chip = Chip("Minecraft 1.21.11", THEME.accent, size=9)
        head.addWidget(self.status_chip)
        body.addLayout(head)

        self.progress = ProgressBar(height=8)
        body.addWidget(self.progress)
        self.detail = label("", 11, MUTED)
        body.addWidget(self.detail)

        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setFixedHeight(196)
        self.log.setMaximumBlockCount(600)
        self.log.setFont(QFont("Consolas", 9))
        self.log.setStyleSheet(
            "QPlainTextEdit { background: rgba(0, 0, 0, 95); color: #C9BEA9;"
            " border: 1px solid rgba(255,255,255,18); border-radius: 9px;"
            " padding: 8px 10px; selection-background-color: rgba(255,255,255,40); }"
        )
        body.addWidget(self.log)

        buttons = QHBoxLayout()
        buttons.setSpacing(8)
        self.hint = muted("Лаунчер скачает недостающие файлы и запустит игру.", 10)
        buttons.addWidget(self.hint, 1)
        self.hide_button = GoldButton("Свернуть", "ghost", height=34)
        self.hide_button.clicked.connect(self.hide)
        buttons.addWidget(self.hide_button)
        self.cancel_button = GoldButton("Отменить", "danger", "stop", height=34)
        self.cancel_button.clicked.connect(self._on_cancel)
        buttons.addWidget(self.cancel_button)
        body.addLayout(buttons)

        row.addWidget(self.card)
        row.addStretch(1)
        outer.addLayout(row)
        outer.addStretch(1)

    def _on_cancel(self) -> None:
        if self._done:
            self.hide()
            return
        self.cancel_button.setEnabled(False)
        self.stage.setText("Отмена…")
        self.cancelled.emit()

    def reset(self) -> None:
        self._done = False
        self.progress.set_value(0.0, animate=False)
        self.stage.setText("Подготовка…")
        self.stage.setStyleSheet(f"color: {MUTED}; background: transparent;")
        self.detail.setText("")
        self.log.clear()
        self.cancel_button.setEnabled(True)
        self.cancel_button.setText("Отменить")
        self.hide_button.setVisible(True)
        self.hint.setText("Лаунчер скачает недостающие файлы и запустит игру.")
        self.setGeometry(self.app.rect())
        self.show()
        self.raise_()

    def set_progress(self, stage: str, detail: str, fraction: float) -> None:
        self.stage.setText(stage)
        self.detail.setText(detail)
        self.progress.set_value(fraction)

    def append_log(self, line: str) -> None:
        self.log.appendPlainText(line)

    def finish_error(self, message: str) -> None:
        self._done = True
        self.stage.setText("Не удалось запустить")
        self.stage.setStyleSheet(f"color: {RED}; background: transparent;")
        self.detail.setText(message)
        self.cancel_button.setEnabled(True)
        self.cancel_button.setText("Закрыть")
        self.hide_button.setVisible(False)
        self.hint.setText("Проверьте Java и подключение к интернету, затем попробуйте снова.")

    def finish_ok(self) -> None:
        self._done = True

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect())
        p.fillRect(rect, QColor(5, 4, 3, 185))
        p.end()


class Splash(QWidget):
    def __init__(self):
        super().__init__(None, Qt.FramelessWindowHint | Qt.SplashScreen | Qt.WindowStaysOnTopHint)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setFixedSize(540, 320)
        self._stage = "Подготовка"
        self._fraction = 0.04
        self._logo = QPixmap(str(ASSETS / "logo_big.png"))
        self._bg = pixmap(THEME.background_path.name)
        screen = QApplication.primaryScreen()
        if screen is not None:
            geo = screen.availableGeometry()
            self.move(geo.center() - self.rect().center())

    def set_step(self, text: str, fraction: float) -> None:
        self._stage = text
        self._fraction = max(0.02, min(1.0, fraction))
        self.update()

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.TextAntialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = rounded(rect, 20)
        p.setClipPath(path)
        p.fillRect(rect, QColor(12, 10, 7))
        if not self._bg.isNull():
            paint_cover(p, rect, self._bg)
        overlay = QLinearGradient(rect.topLeft(), rect.bottomLeft())
        overlay.setColorAt(0.0, QColor(10, 8, 5, 120))
        overlay.setColorAt(0.55, QColor(10, 8, 5, 205))
        overlay.setColorAt(1.0, QColor(10, 8, 5, 245))
        p.fillRect(rect, overlay)
        glow = QRadialGradient(QPointF(rect.width() * 0.5, rect.height() * 0.38), 250)
        glow.setColorAt(0.0, THEME.accent_a(45))
        glow.setColorAt(1.0, QColor(0, 0, 0, 0))
        p.fillRect(rect, glow)

        if not self._logo.isNull():
            logo = self._logo.scaled(96, 96, Qt.KeepAspectRatio, Qt.SmoothTransformation)
            p.drawPixmap(int(rect.center().x() - logo.width() / 2), 42, logo)
        p.setPen(QColor(TEXT))
        p.setFont(heading(25))
        p.drawText(QRectF(0, 148, rect.width(), 34), Qt.AlignCenter, "GOLDISHKA")
        p.setPen(THEME.accent)
        f = font(10, QFont.DemiBold)
        f.setLetterSpacing(QFont.AbsoluteSpacing, 4.0)
        p.setFont(f)
        p.drawText(QRectF(0, 178, rect.width(), 20), Qt.AlignCenter, "CLIENT LOADER")
        p.setPen(QColor(MUTED))
        p.setFont(font(11))
        p.drawText(QRectF(0, 220, rect.width(), 20), Qt.AlignCenter, self._stage)
        track = QRectF(64, 256, rect.width() - 128, 6)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(255, 255, 255, 26))
        p.drawRoundedRect(track, 3, 3)
        fill = QRectF(track.left(), track.top(), max(8.0, track.width() * self._fraction), track.height())
        g = QLinearGradient(fill.topLeft(), fill.topRight())
        g.setColorAt(0, THEME.accent)
        g.setColorAt(1, THEME.accent2)
        p.setBrush(g)
        p.drawRoundedRect(fill, 3, 3)
        p.setPen(QColor(DIM))
        p.setFont(font(9))
        p.drawText(QRectF(0, 272, rect.width(), 18), Qt.AlignCenter,
                   f"Minecraft 1.21.11 · Fabric 0.19.5 · {int(self._fraction * 100)}%")
        p.setClipping(False)
        p.setPen(QPen(QColor(255, 255, 255, 28), 1))
        p.setBrush(Qt.NoBrush)
        p.drawPath(path)
        p.end()

    def finish(self, window: QWidget) -> None:
        window.show()
        window.raise_()
        window.activateWindow()
        self.close()


class MainWindow(QMainWindow):
    def __init__(self, config: LoaderConfig, java_info: javalib.JavaInfo | None = None):
        super().__init__()
        self.config = config
        self.config_file = CONFIG_FILE
        self.server_store = ServerStore(config)
        self.pings: dict[int, PingResult] = {}
        self._java = java_info or javalib.find_best(config.java)
        self._ping_worker: PingWorker | None = None
        self.launch_worker: LaunchWorker | None = None
        self._bg_cache = QPixmap()
        self._particles = [
            (random.Random(17 + i).random(), random.Random(31 + i).random(),
             1.0 + random.Random(47 + i).random() * 1.8,
             random.Random(59 + i).random())
            for i in range(30)
        ]

        self.setWindowTitle("Goldishka Loader")
        self.setWindowIcon(QIcon(str(ASSETS / "icon.png")))
        self.setWindowFlags(Qt.FramelessWindowHint | Qt.Window)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setMinimumSize(1080, 700)
        width = max(1080, min(config.window_width, 2400))
        height = max(700, min(config.window_height, 1600))
        self.resize(width, height)
        screen = QApplication.primaryScreen()
        if screen is not None:
            geo = screen.availableGeometry()
            if self.width() > geo.width() or self.height() > geo.height():
                self.resize(min(self.width(), geo.width()), min(self.height(), geo.height()))
            self.move(geo.center() - self.rect().center())

        root = QWidget()
        root.setStyleSheet("background: transparent;")
        self.setCentralWidget(root)
        shell = QHBoxLayout(root)
        shell.setContentsMargins(0, 0, 0, 0)
        shell.setSpacing(0)
        self.sidebar = Sidebar(self)
        shell.addWidget(self.sidebar)

        main = QWidget()
        main.setStyleSheet("background: transparent;")
        ml = QVBoxLayout(main)
        ml.setContentsMargins(24, 10, 20, 18)
        ml.setSpacing(8)
        self.titlebar = TitleBar(self)
        ml.addWidget(self.titlebar)
        self.stack = QStackedWidget()
        self.stack.setStyleSheet("background: transparent;")
        ml.addWidget(self.stack, 1)
        shell.addWidget(main, 1)

        self.pages: dict[str, QWidget] = {}
        for key, cls in (
            ("home", HomePage),
            ("play", PlayPage),
            ("inject", InjectPage),
            ("launch", LaunchPage),
            ("mods", ModsPage),
            ("packs", PacksPage),
            ("settings", SettingsPage),
            ("appearance", AppearancePage),
        ):
            page = cls(self)
            self.pages[key] = page
            self.stack.addWidget(page)

        self.overlay = LaunchOverlay(self)
        self.overlay.cancelled.connect(self._cancel_launch)

        self._health_timer = QTimer(self)
        self._health_timer.setInterval(8000)
        self._health_timer.timeout.connect(self._refresh_health)
        self._health_timer.start()
        self._refresh_health()

        last = config.last_page if config.last_page in self.pages else "home"
        self.open_page(last)
        QTimer.singleShot(150, self.refresh_servers)

    # ------------------------------------------------------------- свойства
    def assets_dir(self) -> Path:
        return ASSETS

    # ---------------------------------------------------------------- страницы
    def open_page(self, key: str) -> None:
        page = self.pages.get(key)
        if page is None:
            return
        self.stack.setCurrentWidget(page)
        for name, button in self.sidebar.nav.items():
            button.setChecked(name == key)
        if hasattr(page, "on_show"):
            page.on_show()
        self.config.last_page = key
        self._refresh_health()

    def open_play(self, tag: str) -> None:
        self.open_page("play")
        page = self.pages["play"]
        if hasattr(page, "set_filter"):
            page.set_filter(tag)

    # ---------------------------------------------------------------- профиль
    def client_data(self) -> ClientData:
        return read_client_data(self.config)

    def on_profile_changed(self) -> None:
        self.sidebar.refresh()
        home = self.pages.get("home")
        if home is not None:
            home.refresh()

    # ------------------------------------------------------------------ серверы
    def refresh_servers(self) -> None:
        if self._ping_worker is not None and self._ping_worker.isRunning():
            self._ping_worker.stop()
            self._ping_worker.wait(600)
        self.pings.clear()
        self._update_server_chip()
        entries = [(index, server.address) for index, server in enumerate(self.server_store.entries)]
        if not entries:
            return
        worker = PingWorker(entries, self)
        worker.result.connect(self._on_ping)
        worker.finished.connect(worker.deleteLater)
        self._ping_worker = worker
        worker.start()

    def _on_ping(self, index: int, result: PingResult) -> None:
        self.pings[index] = result
        home = self.pages.get("home")
        if home is not None:
            home.update_ping(index, result)
        play = self.pages.get("play")
        if play is not None:
            play.update_ping(index, result)
        self._update_server_chip()

    def _update_server_chip(self) -> None:
        total = len(self.server_store.entries)
        online = sum(1 for result in self.pings.values() if result.online)
        if not self.pings:
            text = f"{total} серверов" if total else "Серверы: —"
        else:
            text = f"{online} из {total} онлайн"
        self.titlebar.server_chip.setText(text)

    def on_servers_changed(self) -> None:
        self.server_store.save()
        self.pings.clear()
        play = self.pages.get("play")
        if play is not None:
            play.refresh()
        home = self.pages.get("home")
        if home is not None:
            home.refresh()
        self._update_server_chip()
        self.refresh_servers()

    # ------------------------------------------------------------------ статус
    def launcher_components(self):
        try:
            return MinecraftLauncher(self.config).components()
        except Exception:  # noqa: BLE001
            return []

    def version_status(self) -> tuple[bool, str]:
        comps = self.launcher_components()
        if not comps:
            return False, "Не удалось проверить файлы клиента"
        missing = {comp.key: comp for comp in comps if not comp.ok}
        if not missing:
            return True, "Клиент готов к запуску"
        if "java" in missing:
            return False, "Java не найдена — укажите в параметрах"
        if "client" in missing:
            return False, "Minecraft будет скачан при запуске"
        if "assets" in missing:
            return False, "Ресурсы будут скачаны при запуске"
        if "fabric" in missing:
            return False, "Fabric будет установлен при запуске"
        return False, "Goldish и Fabric API установятся при запуске"

    def _refresh_health(self) -> None:
        ready, text = self.version_status()
        info = self._java
        if info is None:
            color, short = RED, "Java не найдена"
        elif ready:
            color, short = GREEN, text
        else:
            color, short = YELLOW, text
        self.titlebar.health_dot.set_color(color)
        self.titlebar.health.setText(short)
        self.sidebar.status_dot.set_color(color)

    def java_info(self) -> javalib.JavaInfo | None:
        return self._java

    def set_java(self, path: str) -> javalib.JavaInfo | None:
        info = javalib.probe(path)
        if info is None:
            return None
        self.config.java = str(path)
        self._java = info
        self.save_config()
        self._refresh_health()
        return info

    def max_memory_gb(self) -> int:
        return max(4, min(32, total_ram_gb() - 2))

    # ------------------------------------------------------------------ запуск
    def launch(self, server: ServerEntry | str | None = None) -> None:
        if self.launch_worker is not None and self.launch_worker.isRunning():
            self.toast("Клиент уже запускается", "warn")
            return
        info = self._java
        if info is None:
            self.toast("Java не найдена. Укажите java.exe в «Параметрах запуска».", "error")
            self.open_page("launch")
            return
        if not info.suitable:
            self.toast(f"Нужна Java 21 или новее, найдена Java {info.major}.", "error")
            self.open_page("launch")
            return
        address = server.address if isinstance(server, ServerEntry) else server
        self.overlay.reset()
        self.overlay.raise_()
        worker = LaunchWorker(self.config, address, self)
        worker.progress.connect(self.overlay.set_progress)
        worker.log.connect(self.overlay.append_log)
        worker.launched.connect(self._on_launched)
        worker.failed.connect(self._on_launch_failed)
        worker.crashed.connect(self._on_launch_crashed)
        worker.finished.connect(self._on_launch_finished)
        self.launch_worker = worker
        worker.start()

    def _on_launched(self) -> None:
        self.overlay.finish_ok()
        self.overlay.hide()
        self.toast("Клиент запущен. Хорошей игры!", "ok")
        self._refresh_health()
        if self.config.hide_on_launch:
            self.showMinimized()

    def _on_launch_failed(self, message: str) -> None:
        self.overlay.append_log("ОШИБКА: " + message)
        self.overlay.finish_error(message)
        self.toast(message, "error")

    def _on_launch_crashed(self, message: str) -> None:
        self.overlay.append_log("ИГРА ЗАВЕРШИЛАСЬ: " + message)
        self.overlay.finish_error(message)
        self.overlay.setGeometry(self.rect())
        self.overlay.show()
        self.overlay.raise_()
        self.showNormal()
        self.raise_()
        self.activateWindow()
        self.toast(message, "error")

    def _on_launch_finished(self) -> None:
        self.launch_worker = None

    def _cancel_launch(self) -> None:
        if self.launch_worker is not None:
            self.launch_worker.cancel()
            self.overlay.append_log("Отмена установки…")

    # ------------------------------------------------------------------ папки
    def open_folder(self, path: Path | str) -> None:
        target = Path(path)
        try:
            target.mkdir(parents=True, exist_ok=True)
            if sys.platform == "win32":
                os.startfile(str(target))  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(target)])
            else:
                subprocess.Popen(["xdg-open", str(target)])
        except OSError as err:
            self.toast(f"Не удалось открыть папку: {err}", "error")

    def open_game_folder(self) -> None:
        try:
            self.config.ensure_dirs()
        except OSError:
            pass
        self.open_folder(self.config.game_path)

    def open_logs_folder(self) -> None:
        self.open_folder(self.config.game_path / "logs")

    # ------------------------------------------------------------------ тема
    def apply_accent(self, key: str) -> None:
        THEME.set_accent(key)
        self.config.theme = key
        app = QApplication.instance()
        if app is not None:
            app.setStyleSheet(app_qss())
        self.sidebar.update()
        self.update()
        for page in self.pages.values():
            if hasattr(page, "refresh"):
                page.refresh()
        self.save_config()

    def apply_background(self, key: str) -> None:
        THEME.set_background(key)
        self.config.background = key
        self._bg_cache = QPixmap()
        self.update()
        self.save_config()

    def reset_settings(self) -> None:
        defaults = LoaderConfig()
        keep = {
            "nickname": self.config.nickname,
            "game_dir": self.config.game_dir,
            "servers": self.config.servers,
            "last_page": self.config.last_page,
            "window_width": self.config.window_width,
            "window_height": self.config.window_height,
        }
        for field in LoaderConfig.__dataclass_fields__:
            setattr(self.config, field, getattr(defaults, field))
        for field, value in keep.items():
            setattr(self.config, field, value)
        THEME.set_accent(self.config.theme)
        THEME.set_background(self.config.background)
        THEME.animations = self.config.animations
        app = QApplication.instance()
        if app is not None:
            app.setStyleSheet(app_qss())
        self.sidebar.refresh()
        for page in self.pages.values():
            if hasattr(page, "refresh"):
                page.refresh()
        self._bg_cache = QPixmap()
        self.update()
        self.save_config()

    def set_game_dir(self, path: Path | str) -> bool:
        try:
            target = Path(path)
            target.mkdir(parents=True, exist_ok=True)
            self.config.set_game_dir(target)
            self.config.ensure_dirs()
        except OSError:
            return False
        self.save_config()
        for page in self.pages.values():
            if hasattr(page, "refresh"):
                page.refresh()
        self._refresh_health()
        return True

    # ------------------------------------------------------------------ прочее
    def save_config(self) -> None:
        if not self.isMaximized() and not self.isMinimized():
            self.config.window_width = self.width()
            self.config.window_height = self.height()
        self.config.save(self.config_file)

    def toast(self, text: str, kind: str = "info") -> None:
        if not self.config.notifications and kind not in ("error", "warn"):
            return
        Toast(self, text, kind)

    def toggle_maximize(self) -> None:
        if self.isMaximized():
            self.showNormal()
        else:
            self.showMaximized()
        self.update()

    def resizeEvent(self, event) -> None:  # noqa: N802
        self._bg_cache = QPixmap()
        if hasattr(self, "overlay"):
            self.overlay.setGeometry(self.rect())
        super().resizeEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect())
        radius = 0 if self.isMaximized() else 18
        path = rounded(rect.adjusted(0.5, 0.5, -0.5, -0.5), radius)
        p.setClipPath(path)
        p.fillRect(rect, QColor(11, 9, 6))
        if self._bg_cache.isNull() or self._bg_cache.size() != self.size():
            self._bg_cache = scaled_cover(
                pixmap(THEME.background_path.name), max(1, self.width()), max(1, self.height()))
        if not self._bg_cache.isNull():
            p.drawPixmap(0, 0, self._bg_cache)
        overlay = QLinearGradient(rect.topLeft(), rect.bottomRight())
        overlay.setColorAt(0.0, QColor(8, 6, 4, 165))
        overlay.setColorAt(0.45, QColor(9, 7, 5, 95))
        overlay.setColorAt(1.0, QColor(7, 5, 4, 205))
        p.fillRect(rect, overlay)
        glow = QRadialGradient(QPointF(rect.width() * 0.78, rect.height() * 0.12),
                               max(320.0, rect.width() * 0.55))
        glow.setColorAt(0.0, THEME.accent_a(34))
        glow.setColorAt(1.0, QColor(0, 0, 0, 0))
        p.fillRect(rect, glow)
        if self.config.particles:
            p.setPen(Qt.NoPen)
            for xr, yr, size, phase in self._particles:
                color = THEME.accent
                color.setAlpha(int(26 + 45 * phase))
                p.setBrush(color)
                p.drawEllipse(QPointF(rect.width() * xr, rect.height() * yr), size, size)
        p.setClipping(False)
        p.setPen(QPen(QColor(255, 255, 255, 24), 1))
        p.setBrush(Qt.NoBrush)
        p.drawPath(path)
        p.end()

    def nativeEvent(self, event_type, message):  # noqa: N802
        if (sys.platform == "win32" and event_type == b"windows_generic_MSG"
                and not self.isMaximized()):
            try:
                msg = wintypes.MSG.from_address(int(message))
                if msg.message == 0x0084:  # WM_NCHITTEST
                    x = ctypes.c_short(msg.lParam & 0xFFFF).value
                    y = ctypes.c_short((msg.lParam >> 16) & 0xFFFF).value
                    pos = self.mapFromGlobal(QPoint(x, y))
                    border = 7
                    left = pos.x() < border
                    right = pos.x() >= self.width() - border
                    top = pos.y() < border
                    bottom = pos.y() >= self.height() - border
                    if top and left:
                        return True, 13
                    if top and right:
                        return True, 14
                    if bottom and left:
                        return True, 16
                    if bottom and right:
                        return True, 17
                    if left:
                        return True, 10
                    if right:
                        return True, 11
                    if top:
                        return True, 12
                    if bottom:
                        return True, 15
            except Exception:  # noqa: BLE001
                pass
        return super().nativeEvent(event_type, message)

    def closeEvent(self, event) -> None:  # noqa: N802
        if self.launch_worker is not None and self.launch_worker.isRunning():
            dialog = ConfirmDialog(
                self,
                "Закрыть лаунчер",
                "Идёт установка или запуск клиента. Отменить и закрыть лаунчер?",
                danger=True,
                ok_text="Закрыть",
            )
            if not dialog.show_centered():
                event.ignore()
                return
            self.launch_worker.cancel()
            self.launch_worker.wait(4000)
        if self._ping_worker is not None and self._ping_worker.isRunning():
            self._ping_worker.stop()
            self._ping_worker.wait(500)
        self.save_config()
        event.accept()


def main() -> None:
    global _APP
    if sys.platform == "win32":
        try:
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("Goldishka.Client.Loader")
        except Exception:  # noqa: BLE001
            pass
    QApplication.setAttribute(Qt.AA_EnableHighDpiScaling, True)
    QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps, True)
    app = QApplication(sys.argv)
    _APP = app
    app.setApplicationName("Goldishka Loader")
    app.setOrganizationName("Goldish")
    app.setStyle("Fusion")
    app.setWindowIcon(QIcon(str(ASSETS / "icon.png")))

    config = LoaderConfig.load()
    THEME.set_accent(config.theme)
    THEME.set_background(config.background)
    THEME.animations = config.animations
    app.setStyleSheet(app_qss())

    splash = Splash()
    splash.show()
    worker = PreflightWorker(config)
    worker.step.connect(splash.set_step)

    def done(info: Any) -> None:
        try:
            window = MainWindow(config, info)
            window.show()
            splash.finish(window)
            app._main_window = window  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001
            splash.close()
            QMessageBox.critical(None, "Goldishka Loader",
                                 "Не удалось открыть лаунчер:\n\n" + traceback.format_exc())

    worker.done.connect(done)
    worker.finished.connect(worker.deleteLater)
    worker.start()
    app._preflight = worker  # type: ignore[attr-defined]
    sys.exit(app.exec_())


if __name__ == "__main__":
    main()
