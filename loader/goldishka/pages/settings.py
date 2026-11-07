"""Страница «Настройки»: поведение лаунчера, папки и данные."""

from __future__ import annotations

from typing import TYPE_CHECKING

from PyQt5.QtWidgets import QFileDialog, QHBoxLayout, QVBoxLayout, QWidget

from ..theme import ASSETS, DIM, MUTED, TEXT, THEME
from ..ui.dialogs import AboutDialog
from ..ui.widgets import (
    Chip,
    ConfirmDialog,
    Divider,
    GlassCard,
    GoldButton,
    SettingRow,
    ToggleSwitch,
    h2,
    label,
    muted,
)
from .base import BasePage

if TYPE_CHECKING:
    from ..app import MainWindow


class SettingsPage(BasePage):
    def __init__(self, app: "MainWindow"):
        super().__init__(app, "Настройки", "Лаунчер, обновления и папка игры", "settings")
        about = GoldButton("О клиенте", "ghost", "info", height=36)
        about.clicked.connect(lambda: AboutDialog(app).show_centered())
        self.add_action(about)

        content = QHBoxLayout()
        content.setSpacing(16)
        left = QVBoxLayout()
        left.setSpacing(16)

        behavior = GlassCard(radius=16)
        bb = QVBoxLayout(behavior)
        bb.setContentsMargins(8, 10, 8, 10)
        bb.setSpacing(0)
        bb.addWidget(self._section("Лаунчер"))
        self.hide_switch = ToggleSwitch(app.config.hide_on_launch)
        self.hide_switch.toggled.connect(self._toggle_hide)
        bb.addWidget(SettingRow("Скрывать при запуске", "Сворачивать лаунчер после старта игры",
                                self.hide_switch))
        self.console_switch = ToggleSwitch(app.config.show_console)
        self.console_switch.toggled.connect(self._toggle_console)
        bb.addWidget(SettingRow("Окно консоли", "Показывать лог игры в отдельном окне",
                                self.console_switch))
        self.updates_switch = ToggleSwitch(app.config.check_updates)
        self.updates_switch.toggled.connect(self._toggle_updates)
        bb.addWidget(SettingRow("Проверять обновления", "Сверять версии Fabric и Goldish при запуске",
                                self.updates_switch))
        self.notify_switch = ToggleSwitch(app.config.notifications)
        self.notify_switch.toggled.connect(self._toggle_notify)
        bb.addWidget(SettingRow("Уведомления", "Всплывающие подсказки о событиях лаунчера",
                                self.notify_switch))
        left.addWidget(behavior)

        interface = GlassCard(radius=16)
        ib = QVBoxLayout(interface)
        ib.setContentsMargins(8, 10, 8, 10)
        ib.setSpacing(0)
        ib.addWidget(self._section("Интерфейс"))
        self.anim_switch = ToggleSwitch(app.config.animations)
        self.anim_switch.toggled.connect(self._toggle_animations)
        ib.addWidget(SettingRow("Анимации", "Плавные переходы и подсветка элементов",
                                self.anim_switch))
        self.particles_switch = ToggleSwitch(app.config.particles)
        self.particles_switch.toggled.connect(self._toggle_particles)
        ib.addWidget(SettingRow("Частицы", "Лёгкие искры на фоне лаунчера",
                                self.particles_switch))
        theme_row = QWidget()
        tr = QHBoxLayout(theme_row)
        tr.setContentsMargins(0, 0, 0, 0)
        tr.setSpacing(6)
        self.theme_chip = Chip(THEME.accent_name, THEME.accent, size=9)
        tr.addWidget(self.theme_chip)
        change = GoldButton("Сменить", "secondary", "appearance", height=30)
        change.clicked.connect(lambda: app.open_page("appearance"))
        tr.addWidget(change)
        ib.addWidget(SettingRow("Тема оформления", "Акцент и фон интерфейса", theme_row))
        left.addWidget(interface)
        left.addStretch(1)
        content.addLayout(left, 1)

        right = QVBoxLayout()
        right.setSpacing(12)
        game_card = GlassCard(radius=16)
        gb = QVBoxLayout(game_card)
        gb.setContentsMargins(16, 14, 16, 14)
        gb.setSpacing(8)
        gb.addWidget(h2("Папка игры", 14))
        self.game_path = muted(str(app.config.game_path), 10)
        self.game_path.setWordWrap(True)
        gb.addWidget(self.game_path)
        gb.addWidget(muted("Версии, библиотеки, ресурсы, моды и миры хранятся здесь.", 10))
        buttons = QHBoxLayout()
        buttons.setSpacing(6)
        open_folder = GoldButton("Открыть", "secondary", "folder", height=32)
        open_folder.clicked.connect(app.open_game_folder)
        buttons.addWidget(open_folder)
        move = GoldButton("Изменить", "ghost", "edit", height=32)
        move.clicked.connect(self._change_dir)
        buttons.addWidget(move)
        buttons.addStretch(1)
        gb.addLayout(buttons)
        right.addWidget(game_card)

        data_card = GlassCard(radius=16)
        db = QVBoxLayout(data_card)
        db.setContentsMargins(16, 14, 16, 14)
        db.setSpacing(8)
        db.addWidget(h2("Данные", 14))
        db.addWidget(muted("Настройки лаунчера лежат в config.json рядом с программой.", 10))
        config_row = QHBoxLayout()
        config_row.setSpacing(6)
        open_config = GoldButton("config.json", "ghost", "folder", height=32)
        open_config.clicked.connect(lambda: app.open_folder(app.config_file.parent))
        config_row.addWidget(open_config)
        open_logs = GoldButton("Логи", "ghost", "external", height=32)
        open_logs.clicked.connect(app.open_logs_folder)
        config_row.addWidget(open_logs)
        config_row.addStretch(1)
        db.addLayout(config_row)
        db.addWidget(Divider())
        db.addWidget(muted("Сброс вернёт оформление и поведение к заводским. "
                           "Ник, серверы и папка игры сохранятся.", 10))
        reset = GoldButton("Сбросить настройки", "danger", "refresh", height=34)
        reset.clicked.connect(self._reset)
        db.addWidget(reset)
        right.addWidget(data_card)

        about_card = GlassCard(radius=16)
        ab = QVBoxLayout(about_card)
        ab.setContentsMargins(16, 14, 16, 14)
        ab.setSpacing(5)
        ab.addWidget(h2("Goldish Client", 14))
        ab.addWidget(muted(f"Minecraft {app.config.mc_version} · Fabric {app.config.loader_version}", 10))
        ab.addWidget(muted("Лаунчер 1.0.0 · Python + PyQt5", 10))
        right.addWidget(about_card)
        right.addStretch(1)
        right_wrap = QWidget()
        right_wrap.setFixedWidth(310)
        right_wrap.setLayout(right)
        content.addWidget(right_wrap, 0)

        self.scroll.add_layout(content)
        self.scroll.body.addStretch(1)

    # ------------------------------------------------------------------
    def _section(self, text: str) -> QWidget:
        holder = QWidget()
        lay = QHBoxLayout(holder)
        lay.setContentsMargins(14, 4, 14, 4)
        lay.addWidget(h2(text, 15))
        lay.addStretch(1)
        return holder

    def _toggle_hide(self, checked: bool) -> None:
        self.app.config.hide_on_launch = checked
        self.app.save_config()

    def _toggle_console(self, checked: bool) -> None:
        self.app.config.show_console = checked
        self.app.save_config()

    def _toggle_updates(self, checked: bool) -> None:
        self.app.config.check_updates = checked
        self.app.save_config()

    def _toggle_notify(self, checked: bool) -> None:
        self.app.config.notifications = checked
        self.app.save_config()

    def _toggle_animations(self, checked: bool) -> None:
        self.app.config.animations = checked
        THEME.animations = checked
        self.app.save_config()

    def _toggle_particles(self, checked: bool) -> None:
        self.app.config.particles = checked
        self.app.save_config()

    def _change_dir(self) -> None:
        path = QFileDialog.getExistingDirectory(
            self, "Выберите папку игры", str(self.app.config.game_path))
        if not path:
            return
        if self.app.set_game_dir(path):
            self.app.toast("Папка игры изменена", "ok")
            self.refresh()
        else:
            self.app.toast("Не удалось изменить папку игры", "error")

    def _reset(self) -> None:
        dialog = ConfirmDialog(
            self.app,
            "Сбросить настройки",
            "Вернуть оформление и поведение лаунчера к заводским настройкам? "
            "Ник, серверы и папка игры останутся на месте.",
            danger=True,
            ok_text="Сбросить",
        )
        if not dialog.show_centered():
            return
        self.app.reset_settings()
        self.refresh()
        self.app.toast("Настройки сброшены", "ok")

    def refresh(self) -> None:
        self.game_path.setText(str(self.app.config.game_path))
        self.theme_chip.setText(THEME.accent_name)
        self.theme_chip.set_color(THEME.accent)
        for switch, value in (
            (self.hide_switch, self.app.config.hide_on_launch),
            (self.console_switch, self.app.config.show_console),
            (self.updates_switch, self.app.config.check_updates),
            (self.notify_switch, self.app.config.notifications),
            (self.anim_switch, self.app.config.animations),
            (self.particles_switch, self.app.config.particles),
        ):
            switch.blockSignals(True)
            switch.setChecked(value)
            switch._pos = 1.0 if value else 0.0
            switch.blockSignals(False)
            switch.update()

    def on_show(self) -> None:
        self.refresh()
