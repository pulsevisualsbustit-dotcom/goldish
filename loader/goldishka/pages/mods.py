"""Страница «Моды»: список, включение, установка и удаление."""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from PyQt5.QtCore import Qt
from PyQt5.QtWidgets import QFileDialog, QHBoxLayout, QVBoxLayout, QWidget

from ..core.mods import (
    ModInfo,
    delete_mod,
    install_mod,
    scan_mods,
    set_mod_enabled,
)
from ..theme import DIM, GREEN, MUTED, RED, TEXT, THEME, YELLOW
from ..ui.components import EmptyState, ModRow
from ..ui.widgets import (
    Chip,
    ConfirmDialog,
    GlassCard,
    GoldButton,
    GoldCombo,
    IconButton,
    SearchBox,
    StatusDot,
    h2,
    label,
    muted,
)
from .base import BasePage

if TYPE_CHECKING:
    from ..app import MainWindow


class ModsPage(BasePage):
    def __init__(self, app: "MainWindow"):
        super().__init__(app, "Моды", "Клиент Goldish, Fabric API и ваши моды", "mods")
        self.search = SearchBox("Поиск мода…")
        self.search.setFixedWidth(220)
        self.search.textChanged.connect(lambda _: self.refresh())
        self.add_action(self.search)
        self.filter = GoldCombo()
        self.filter.addItems(["Все моды", "Включённые", "Выключенные"])
        self.filter.setFixedWidth(148)
        self.filter.currentIndexChanged.connect(lambda _: self.refresh())
        self.add_action(self.filter)
        refresh = IconButton("refresh", size=36, icon_size=16, tooltip="Обновить список")
        refresh.clicked.connect(self.refresh)
        self.add_action(refresh)
        install = GoldButton("Установить мод", "secondary", "plus", height=36)
        install.clicked.connect(self._install)
        self.add_action(install)

        content = QHBoxLayout()
        content.setSpacing(16)
        list_card = GlassCard(radius=16)
        self.list_box = QVBoxLayout(list_card)
        self.list_box.setContentsMargins(8, 8, 8, 8)
        self.list_box.setSpacing(2)
        self.rows: list[ModRow] = []
        self.empty = EmptyState(
            "mods",
            "Модов пока нет",
            "Нажмите «Установить мод» или запустите клиент — Fabric API скачается сам.",
        )
        self.empty.setVisible(False)
        self.list_box.addWidget(self.empty)
        self.list_box.addStretch(1)
        content.addWidget(list_card, 1)

        right = QVBoxLayout()
        right.setSpacing(12)
        stats_card = GlassCard(radius=16)
        sb = QVBoxLayout(stats_card)
        sb.setContentsMargins(16, 14, 16, 14)
        sb.setSpacing(8)
        sb.addWidget(h2("Состояние", 14))
        self.total_label = label("0 модов", 12, TEXT, bold=True)
        sb.addWidget(self.total_label)
        self.enabled_label = muted("0 включено", 11)
        sb.addWidget(self.enabled_label)
        self.disabled_label = muted("0 выключено", 11)
        sb.addWidget(self.disabled_label)
        self.api_row = QHBoxLayout()
        self.api_row.setSpacing(5)
        self.api_dot = StatusDot(YELLOW, 6)
        self.api_row.addWidget(self.api_dot)
        self.api_label = label("Fabric API не найден", 11, MUTED)
        self.api_row.addWidget(self.api_label, 1)
        sb.addLayout(self.api_row)
        right.addWidget(stats_card)

        info = GlassCard(radius=16)
        ib = QVBoxLayout(info)
        ib.setContentsMargins(16, 14, 16, 14)
        ib.setSpacing(6)
        ib.addWidget(h2("Как это работает", 14))
        ib.addWidget(muted(
            "Выключение переименовывает .jar в .jar.disabled — файл остаётся на месте. "
            "Изменения вступят в силу после перезапуска игры.", 10))
        open_folder = GoldButton("Открыть папку модов", "ghost", "folder", height=34)
        open_folder.clicked.connect(lambda: app.open_folder(app.config.mods_path))
        ib.addWidget(open_folder)
        right.addWidget(info)

        api_note = GlassCard(radius=16)
        ab = QVBoxLayout(api_note)
        ab.setContentsMargins(16, 14, 16, 14)
        ab.setSpacing(6)
        ab.addWidget(h2("Fabric API", 14))
        ab.addWidget(muted(
            "Нужен для работы Goldish. Лаунчер сам скачает актуальную версию "
            "Fabric API для 1.21.11 при запуске.", 10))
        right.addWidget(api_note)
        right.addStretch(1)
        right_wrap = QWidget()
        right_wrap.setFixedWidth(300)
        right_wrap.setLayout(right)
        content.addWidget(right_wrap, 0)

        self.scroll.add_layout(content)
        self.scroll.body.addStretch(1)
        self._mods: list[ModInfo] = []

    # ------------------------------------------------------------------
    def _clear(self) -> None:
        for row in self.rows:
            self.list_box.removeWidget(row)
            row.deleteLater()
        self.rows.clear()

    def refresh(self) -> None:
        self._clear()
        mods = scan_mods(self.app.config.mods_path)
        self._mods = mods
        enabled = sum(1 for mod in mods if mod.enabled)
        self.total_label.setText(f"{len(mods)} модов")
        self.enabled_label.setText(f"{enabled} включено")
        self.disabled_label.setText(f"{len(mods) - enabled} выключено")
        has_api = any("fabric-api" in mod.filename.lower() for mod in mods)
        self.api_dot.set_color(GREEN if has_api else YELLOW)
        self.api_label.setText("Fabric API установлен" if has_api else "Fabric API скачается при запуске")
        self.api_label.setStyleSheet(
            f"color: {GREEN if has_api else YELLOW}; background: transparent;")

        query = self.search.text().strip().lower()
        mode = self.filter.currentIndex()
        shown = 0
        for mod in mods:
            if query and query not in mod.title.lower() and query not in mod.filename.lower():
                continue
            if mode == 1 and not mod.enabled:
                continue
            if mode == 2 and mod.enabled:
                continue
            row = ModRow(mod)
            row.toggled.connect(self._toggle)
            row.removed.connect(self._remove)
            self.list_box.insertWidget(self.list_box.count() - 1, row)
            self.rows.append(row)
            shown += 1
        self.empty.setVisible(shown == 0)

    def _toggle(self, info: ModInfo, checked: bool) -> None:
        try:
            set_mod_enabled(info.path, checked)
        except OSError as err:
            self.app.toast(str(err), "error")
        else:
            self.app.toast(
                f"«{info.title}» {'включён' if checked else 'выключен'}. "
                "Перезапустите игру.", "ok")
        self.refresh()

    def _remove(self, info: ModInfo) -> None:
        dialog = ConfirmDialog(
            self.app,
            "Удалить мод",
            f"Удалить «{info.title}»? Файл будет удалён с диска без возможности восстановления.",
            danger=True,
            ok_text="Удалить",
        )
        if not dialog.show_centered():
            return
        try:
            delete_mod(info.path)
        except OSError as err:
            self.app.toast(str(err), "error")
        else:
            self.app.toast(f"«{info.title}» удалён", "ok")
        self.refresh()

    def _install(self) -> None:
        paths, _ = QFileDialog.getOpenFileNames(
            self, "Выберите моды", str(Path.home() / "Downloads"),
            "Fabric моды (*.jar);;Все файлы (*)")
        if not paths:
            return
        installed = 0
        errors: list[str] = []
        for raw in paths:
            try:
                install_mod(Path(raw), self.app.config.mods_path)
                installed += 1
            except (OSError, FileExistsError) as err:
                errors.append(str(err))
        if installed:
            self.app.toast(f"Установлено модов: {installed}. Перезапустите игру.", "ok")
        if errors:
            self.app.toast(errors[0], "error")
        self.refresh()

    def on_show(self) -> None:
        self.refresh()
