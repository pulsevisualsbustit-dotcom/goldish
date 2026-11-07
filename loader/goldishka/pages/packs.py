"""Страница «Ресурспаки»: список, включение, установка и удаление."""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from PyQt5.QtWidgets import QFileDialog, QHBoxLayout, QVBoxLayout, QWidget

from ..core.mods import (
    PackInfo,
    delete_pack,
    install_pack,
    scan_packs,
    toggle_pack,
)
from ..theme import GREEN, MUTED, RED, TEXT, YELLOW
from ..ui.components import EmptyState, PackRow
from ..ui.widgets import (
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


class PacksPage(BasePage):
    def __init__(self, app: "MainWindow"):
        super().__init__(app, "Ресурспаки", "Текстуры и оформление мира", "packs")
        self.search = SearchBox("Поиск ресурспака…")
        self.search.setFixedWidth(220)
        self.search.textChanged.connect(lambda _: self.refresh())
        self.add_action(self.search)
        self.filter = GoldCombo()
        self.filter.addItems(["Все паки", "Включённые", "Выключенные"])
        self.filter.setFixedWidth(148)
        self.filter.currentIndexChanged.connect(lambda _: self.refresh())
        self.add_action(self.filter)
        refresh = IconButton("refresh", size=36, icon_size=16, tooltip="Обновить список")
        refresh.clicked.connect(self.refresh)
        self.add_action(refresh)
        install = GoldButton("Установить пак", "secondary", "plus", height=36)
        install.clicked.connect(self._install)
        self.add_action(install)

        content = QHBoxLayout()
        content.setSpacing(16)
        list_card = GlassCard(radius=16)
        self.list_box = QVBoxLayout(list_card)
        self.list_box.setContentsMargins(8, 8, 8, 8)
        self.list_box.setSpacing(2)
        self.rows: list[PackRow] = []
        self.empty = EmptyState(
            "packs",
            "Ресурспаков пока нет",
            "Закиньте .zip в папку resourcepacks или нажмите «Установить пак».",
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
        self.total_label = label("0 паков", 12, TEXT, bold=True)
        sb.addWidget(self.total_label)
        self.enabled_label = muted("0 включено", 11)
        sb.addWidget(self.enabled_label)
        status_row = QHBoxLayout()
        status_row.setSpacing(5)
        self.options_dot = StatusDot(YELLOW, 6)
        status_row.addWidget(self.options_dot)
        self.options_label = label("options.txt не найден", 11, YELLOW)
        status_row.addWidget(self.options_label, 1)
        sb.addLayout(status_row)
        right.addWidget(stats_card)

        info = GlassCard(radius=16)
        ib = QVBoxLayout(info)
        ib.setContentsMargins(16, 14, 16, 14)
        ib.setSpacing(6)
        ib.addWidget(h2("Порядок паков", 14))
        ib.addWidget(muted(
            "Включение меняет строку resourcePacks в options.txt. "
            "Запустите игру один раз, чтобы файл настроек появился.", 10))
        open_folder = GoldButton("Открыть папку", "ghost", "folder", height=34)
        open_folder.clicked.connect(lambda: app.open_folder(app.config.packs_path))
        ib.addWidget(open_folder)
        right.addWidget(info)

        server_note = GlassCard(radius=16)
        nb = QVBoxLayout(server_note)
        nb.setContentsMargins(16, 14, 16, 14)
        nb.setSpacing(6)
        nb.addWidget(h2("На серверах", 14))
        nb.addWidget(muted(
            "Некоторые серверы требуют ванильные текстуры и могут не принять "
            "сторонние паки. Отключайте их, если видите предупреждение.", 10))
        right.addWidget(server_note)
        right.addStretch(1)
        right_wrap = QWidget()
        right_wrap.setFixedWidth(300)
        right_wrap.setLayout(right)
        content.addWidget(right_wrap, 0)

        self.scroll.add_layout(content)
        self.scroll.body.addStretch(1)

    # ------------------------------------------------------------------
    def _clear(self) -> None:
        for row in self.rows:
            self.list_box.removeWidget(row)
            row.deleteLater()
        self.rows.clear()

    def refresh(self) -> None:
        self._clear()
        options = self.app.config.game_path / "options.txt"
        packs = scan_packs(self.app.config.packs_path, options)
        enabled = sum(1 for pack in packs if pack.enabled)
        self.total_label.setText(f"{len(packs)} паков")
        self.enabled_label.setText(f"{enabled} включено")
        has_options = options.is_file()
        self.options_dot.set_color(GREEN if has_options else YELLOW)
        self.options_label.setText("options.txt найден" if has_options else "options.txt не найден")
        self.options_label.setStyleSheet(
            f"color: {GREEN if has_options else YELLOW}; background: transparent;")

        query = self.search.text().strip().lower()
        mode = self.filter.currentIndex()
        shown = 0
        for pack in packs:
            if query and query not in pack.name.lower() and query not in pack.filename.lower():
                continue
            if mode == 1 and not pack.enabled:
                continue
            if mode == 2 and pack.enabled:
                continue
            row = PackRow(pack)
            row.toggled.connect(self._toggle)
            row.removed.connect(self._remove)
            self.list_box.insertWidget(self.list_box.count() - 1, row)
            self.rows.append(row)
            shown += 1
        self.empty.setVisible(shown == 0)

    def _toggle(self, info: PackInfo, checked: bool) -> None:
        ok, message = toggle_pack(self.app.config.game_path / "options.txt", info.filename)
        self.app.toast(message, "ok" if ok else "warn")
        self.refresh()

    def _remove(self, info: PackInfo) -> None:
        dialog = ConfirmDialog(
            self.app,
            "Удалить ресурспак",
            f"Удалить «{info.name}»? Файл будет удалён с диска без возможности восстановления.",
            danger=True,
            ok_text="Удалить",
        )
        if not dialog.show_centered():
            return
        try:
            delete_pack(info.path)
        except OSError as err:
            self.app.toast(str(err), "error")
        else:
            self.app.toast(f"«{info.name}» удалён", "ok")
        self.refresh()

    def _install(self) -> None:
        paths, _ = QFileDialog.getOpenFileNames(
            self, "Выберите ресурспаки", str(Path.home() / "Downloads"),
            "Ресурспаки (*.zip);;Все файлы (*)")
        if not paths:
            return
        installed = 0
        errors: list[str] = []
        for raw in paths:
            try:
                install_pack(Path(raw), self.app.config.packs_path)
                installed += 1
            except (OSError, FileExistsError) as err:
                errors.append(str(err))
        if installed:
            self.app.toast(f"Установлено паков: {installed}", "ok")
        if errors:
            self.app.toast(errors[0], "error")
        self.refresh()

    def on_show(self) -> None:
        self.refresh()
