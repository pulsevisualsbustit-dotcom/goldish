"""Параметры запуска: ник, память, Java, аргументы и состояние файлов."""

from __future__ import annotations

from typing import TYPE_CHECKING

from PyQt5.QtCore import QRegularExpression, QTimer, Qt
from PyQt5.QtGui import QColor, QRegularExpressionValidator
from PyQt5.QtWidgets import QFileDialog, QHBoxLayout, QLineEdit, QVBoxLayout, QWidget

from ..core.config import total_ram_gb
from ..theme import DIM, GREEN, MUTED, RED, TEXT, THEME, YELLOW
from ..ui.widgets import (
    Chip,
    GlassCard,
    GoldButton,
    GoldSlider,
    IconButton,
    SettingRow,
    StatusDot,
    ToggleSwitch,
    h2,
    label,
    muted,
)
from .base import BasePage

if TYPE_CHECKING:
    from ..app import MainWindow


class LaunchPage(BasePage):
    def __init__(self, app: "MainWindow"):
        super().__init__(app, "Параметры запуска", "Память, Java и аргументы клиента", "sliders")
        play = GoldButton("Играть", "primary", "play", height=38)
        play.setMinimumWidth(126)
        play.clicked.connect(lambda: app.launch())
        self.add_action(play)

        content = QHBoxLayout()
        content.setSpacing(16)

        # ---------------------------------------------------------- слева
        left = QVBoxLayout()
        left.setSpacing(16)
        game_card = GlassCard(radius=16)
        game_box = QVBoxLayout(game_card)
        game_box.setContentsMargins(8, 10, 8, 10)
        game_box.setSpacing(0)
        game_box.addWidget(self._section("Игра"))

        self.nickname = QLineEdit(app.config.nickname)
        self.nickname.setMaxLength(16)
        self.nickname.setFixedWidth(230)
        self.nickname.setValidator(QRegularExpressionValidator(
            QRegularExpression(r"[A-Za-z0-9_]{0,16}"), self.nickname))
        self.nickname.editingFinished.connect(self._save_nickname)
        game_box.addWidget(SettingRow("Ник в игре", "3–16 символов, оффлайн-аккаунт", self.nickname))

        version_control = QWidget()
        vc = QHBoxLayout(version_control)
        vc.setContentsMargins(0, 0, 0, 0)
        vc.setSpacing(6)
        vc.addWidget(Chip(f"Minecraft {app.config.mc_version}", THEME.accent, size=9))
        vc.addWidget(Chip(f"Fabric {app.config.loader_version}", THEME.accent2, size=9))
        open_versions = IconButton("folder", size=30, icon_size=14, tooltip="Открыть папку версий")
        open_versions.clicked.connect(lambda: app.open_folder(app.config.game_path / "versions"))
        vc.addWidget(open_versions)
        game_box.addWidget(SettingRow("Версия", "Клиент собран под 1.21.11", version_control))

        memory_control = QWidget()
        mc = QHBoxLayout(memory_control)
        mc.setContentsMargins(0, 0, 0, 0)
        mc.setSpacing(10)
        self.memory = GoldSlider()
        self.memory.setFixedWidth(210)
        maximum = app.max_memory_gb()
        self.memory.setRange(2, maximum)
        self.memory.setValue(min(max(2, app.config.memory_gb), maximum))
        self.memory.valueChanged.connect(self._memory_changed)
        mc.addWidget(self.memory)
        self.memory_label = label(f"{self.memory.value()} ГБ", 12, TEXT, bold=True)
        self.memory_label.setFixedWidth(46)
        mc.addWidget(self.memory_label)
        game_box.addWidget(SettingRow("Оперативная память", f"до {maximum} ГБ на этой машине", memory_control))

        java_control = QWidget()
        jc = QHBoxLayout(java_control)
        jc.setContentsMargins(0, 0, 0, 0)
        jc.setSpacing(8)
        self.java_label = label("—", 11, MUTED)
        self.java_label.setMaximumWidth(210)
        jc.addWidget(self.java_label)
        browse = GoldButton("Обзор", "ghost", height=30)
        browse.setMinimumWidth(74)
        browse.clicked.connect(self._pick_java)
        jc.addWidget(browse)
        game_box.addWidget(SettingRow("Java", "Нужна Java 21 или новее", java_control))

        self.jvm_args = QLineEdit(app.config.jvm_args)
        self.jvm_args.setPlaceholderText("-XX:+UseG1GC …")
        self.jvm_args.setFixedWidth(320)
        self.jvm_args.editingFinished.connect(self._save_args)
        game_box.addWidget(SettingRow("Аргументы JVM", "Дополнительные параметры Java", self.jvm_args))

        self.game_args = QLineEdit(app.config.game_args)
        self.game_args.setPlaceholderText("--fullscreen …")
        self.game_args.setFixedWidth(320)
        self.game_args.editingFinished.connect(self._save_args)
        game_box.addWidget(SettingRow("Аргументы игры", "Передаются после аргументов клиента", self.game_args))
        left.addWidget(game_card)
        left.addStretch(1)
        content.addLayout(left, 1)

        # --------------------------------------------------------- справа
        right = QVBoxLayout()
        right.setSpacing(12)

        behavior = GlassCard(radius=16)
        bb = QVBoxLayout(behavior)
        bb.setContentsMargins(8, 10, 8, 10)
        bb.setSpacing(0)
        bb.addWidget(self._section("Поведение"))
        self.hide_switch = ToggleSwitch(app.config.hide_on_launch)
        self.hide_switch.toggled.connect(self._toggle_hide)
        bb.addWidget(SettingRow("Скрывать лаунчер", "Свернуть окно после запуска игры", self.hide_switch))
        self.console_switch = ToggleSwitch(app.config.show_console)
        self.console_switch.toggled.connect(self._toggle_console)
        bb.addWidget(SettingRow("Показывать консоль", "Отдельное окно с логом игры", self.console_switch))
        self.notify_switch = ToggleSwitch(app.config.notifications)
        self.notify_switch.toggled.connect(self._toggle_notify)
        bb.addWidget(SettingRow("Уведомления", "Показывать всплывающие подсказки", self.notify_switch))
        right.addWidget(behavior)

        comp_card = GlassCard(radius=16)
        self.comp_box = QVBoxLayout(comp_card)
        self.comp_box.setContentsMargins(14, 12, 14, 12)
        self.comp_box.setSpacing(7)
        comp_head = QHBoxLayout()
        comp_head.addWidget(h2("Компоненты", 14))
        comp_head.addStretch(1)
        refresh = IconButton("refresh", size=28, icon_size=13, tooltip="Проверить снова")
        refresh.clicked.connect(self.refresh_components)
        comp_head.addWidget(refresh)
        self.comp_box.addLayout(comp_head)
        self.comp_rows = QVBoxLayout()
        self.comp_rows.setSpacing(5)
        self.comp_box.addLayout(self.comp_rows)
        right.addWidget(comp_card)

        data_card = GlassCard(radius=16)
        db = QVBoxLayout(data_card)
        db.setContentsMargins(14, 12, 14, 12)
        db.setSpacing(6)
        db.addWidget(h2("Папка игры", 14))
        self.game_path = muted(str(app.config.game_path), 10)
        self.game_path.setWordWrap(True)
        db.addWidget(self.game_path)
        buttons = QHBoxLayout()
        buttons.setSpacing(6)
        open_game = GoldButton("Открыть", "secondary", "folder", height=32)
        open_game.clicked.connect(app.open_game_folder)
        buttons.addWidget(open_game)
        open_logs = GoldButton("Логи", "ghost", "external", height=32)
        open_logs.clicked.connect(app.open_logs_folder)
        buttons.addWidget(open_logs)
        buttons.addStretch(1)
        db.addLayout(buttons)
        right.addWidget(data_card)

        play2 = GoldButton("Запустить клиент", "primary", "play", height=42)
        play2.clicked.connect(lambda: app.launch())
        right.addWidget(play2)
        right.addStretch(1)
        right_wrap = QWidget()
        right_wrap.setFixedWidth(330)
        right_wrap.setLayout(right)
        content.addWidget(right_wrap, 0)

        self.scroll.add_layout(content)
        self.scroll.body.addStretch(1)
        self.refresh()

    # ------------------------------------------------------------------
    def _section(self, text: str) -> QWidget:
        holder = QWidget()
        lay = QHBoxLayout(holder)
        lay.setContentsMargins(14, 4, 14, 4)
        lay.addWidget(h2(text, 15))
        lay.addStretch(1)
        return holder

    def _save_later(self) -> None:
        QTimer.singleShot(250, self.app.save_config)

    def _save_nickname(self) -> None:
        nick = self.nickname.text().strip()
        if len(nick) >= 3:
            self.app.config.nickname = nick
            self.app.save_config()
            self.app.on_profile_changed()

    def _memory_changed(self, value: int) -> None:
        self.memory_label.setText(f"{value} ГБ")
        self.app.config.memory_gb = value
        self._save_later()

    def _save_args(self) -> None:
        self.app.config.jvm_args = self.jvm_args.text().strip()
        self.app.config.game_args = self.game_args.text().strip()
        self.app.save_config()

    def _toggle_hide(self, checked: bool) -> None:
        self.app.config.hide_on_launch = checked
        self.app.save_config()

    def _toggle_console(self, checked: bool) -> None:
        self.app.config.show_console = checked
        self.app.save_config()

    def _toggle_notify(self, checked: bool) -> None:
        self.app.config.notifications = checked
        self.app.save_config()

    def _pick_java(self) -> None:
        start = self.app.config.java or "C:\\Program Files\\Java"
        path, _ = QFileDialog.getOpenFileName(self, "Выберите java.exe", start,
                                              "java.exe (java.exe);;Все файлы (*)")
        if not path:
            return
        info = self.app.set_java(path)
        if info is None:
            self.app.toast("Это не похоже на Java. Выберите java.exe из JDK 21.", "error")
        else:
            self.refresh()
            self.app.toast(f"Java {info.major} подключена", "ok")

    def refresh_components(self) -> None:
        while self.comp_rows.count():
            item = self.comp_rows.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        for comp in self.app.launcher_components():
            row = QWidget()
            lay = QHBoxLayout(row)
            lay.setContentsMargins(0, 2, 0, 2)
            lay.setSpacing(6)
            lay.addWidget(StatusDot(GREEN if comp.ok else (RED if comp.key == "java" else YELLOW), 6))
            lay.addWidget(label(comp.title, 11, TEXT))
            lay.addStretch(1)
            detail = label(comp.detail, 10, MUTED)
            detail.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
            lay.addWidget(detail)
            self.comp_rows.addWidget(row)

    def refresh(self) -> None:
        self.game_path.setText(str(self.app.config.game_path))
        info = self.app.java_info()
        if info is None:
            self.java_label.setText("не найдена")
            self.java_label.setStyleSheet(f"color: {RED}; background: transparent;")
        else:
            self.java_label.setText(f"Java {info.major} · {info.vendor or 'JDK'}")
            self.java_label.setStyleSheet(
                f"color: {GREEN if info.suitable else YELLOW}; background: transparent;")
        self.memory.setRange(2, self.app.max_memory_gb())
        self.memory.setValue(min(max(2, self.app.config.memory_gb), self.memory.maximum()))
        self.memory_label.setText(f"{self.memory.value()} ГБ")
        if not self.nickname.hasFocus():
            self.nickname.setText(self.app.config.nickname)
        self.refresh_components()

    def on_show(self) -> None:
        self.refresh()
