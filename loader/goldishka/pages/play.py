"""Страница «Играть»: список серверов с настоящим пингом."""

from __future__ import annotations

from typing import TYPE_CHECKING

from PyQt5.QtCore import Qt
from PyQt5.QtWidgets import QHBoxLayout, QMenu, QVBoxLayout, QWidget

from ..core.servers import PingResult
from ..theme import MUTED, THEME
from ..ui.components import EmptyState, FriendsPanel, ServerRow, VersionPanel
from ..ui.dialogs import ServerDialog
from ..ui.widgets import (
    Chip,
    ConfirmDialog,
    GlassCard,
    GoldButton,
    GoldCombo,
    IconButton,
    SearchBox,
    h2,
    label,
    muted,
)
from .base import BasePage

if TYPE_CHECKING:
    from ..app import MainWindow


class PlayPage(BasePage):
    def __init__(self, app: "MainWindow"):
        super().__init__(app, "Сервера", "Живой онлайн и пинг — без сторонних сервисов", "server")
        self.search = SearchBox("Поиск сервера…")
        self.search.setFixedWidth(230)
        self.search.textChanged.connect(lambda _: self._apply_filter())
        self.add_action(self.search)
        self.mode = GoldCombo()
        self.mode.addItems(["Все режимы", "Выживание", "Анархия", "Мини-игры", "PvP", "Другое"])
        self.mode.setFixedWidth(150)
        self.mode.currentIndexChanged.connect(lambda _: self._apply_filter())
        self.add_action(self.mode)
        refresh = IconButton("refresh", size=36, icon_size=16, tooltip="Обновить пинг")
        refresh.clicked.connect(self.app.refresh_servers)
        self.add_action(refresh)
        add = GoldButton("Добавить", "secondary", "plus", height=36)
        add.clicked.connect(self._add_server)
        self.add_action(add)

        content = QHBoxLayout()
        content.setSpacing(16)
        left = GlassCard(radius=16)
        self.list_box = QVBoxLayout(left)
        self.list_box.setContentsMargins(8, 8, 8, 8)
        self.list_box.setSpacing(2)
        self.rows: list[ServerRow] = []
        self.empty = EmptyState("server", "Серверов нет",
                                "Измените фильтр или добавьте свой сервер.")
        self.empty.setVisible(False)
        self.list_box.addWidget(self.empty)
        self.list_box.addStretch(1)
        content.addWidget(left, 1)

        right = QVBoxLayout()
        right.setSpacing(12)
        self.version = VersionPanel(app)
        right.addWidget(self.version)
        self.friends = FriendsPanel(app, limit=4)
        right.addWidget(self.friends)
        help_card = GlassCard(radius=16)
        help_box = QVBoxLayout(help_card)
        help_box.setContentsMargins(16, 14, 16, 14)
        help_box.setSpacing(6)
        help_box.addWidget(h2("Свой сервер", 14))
        help_box.addWidget(muted("Добавьте адрес вручную — пинг и запуск работают так же.", 10))
        add2 = GoldButton("Добавить сервер", "secondary", "plus", height=34)
        add2.clicked.connect(self._add_server)
        help_box.addWidget(add2)
        right.addWidget(help_card)
        right.addStretch(1)
        right_wrap = QWidget()
        right_wrap.setFixedWidth(304)
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

    def _matches(self, index: int) -> bool:
        entry = self.app.server_store.entries[index]
        query = self.search.text().strip().lower()
        mode = self.mode.currentText()
        if mode and mode != "Все режимы" and entry.tag.lower() != mode.lower():
            return False
        if query and query not in entry.name.lower() and query not in entry.address.lower() \
                and query not in entry.tag.lower():
            return False
        return True

    def _apply_filter(self) -> None:
        self._clear()
        shown = 0
        for index, entry in enumerate(self.app.server_store.entries):
            if not self._matches(index):
                continue
            row = ServerRow(self.app, entry, index)
            result = self.app.pings.get(index)
            if result is not None:
                row.set_ping(result)
            row.menu_requested.connect(self._menu)
            self.list_box.insertWidget(self.list_box.count() - 1, row)
            self.rows.append(row)
            shown += 1
        self.empty.setVisible(shown == 0)

    def _add_server(self) -> None:
        ServerDialog(self.app).show_centered()

    def _menu(self, index: int, position) -> None:
        menu = QMenu(self)
        play = menu.addAction("Играть")
        menu.addSeparator()
        edit = menu.addAction("Изменить")
        remove = menu.addAction("Удалить")
        action = menu.exec_(position)
        if action == play:
            self.app.launch(self.app.server_store.entries[index])
        elif action == edit:
            ServerDialog(self.app, self.app.server_store.entries[index], index).show_centered()
        elif action == remove:
            entry = self.app.server_store.entries[index]
            if ConfirmDialog(self.app, "Удалить сервер",
                             f"Удалить «{entry.name}» из списка?", danger=True,
                             ok_text="Удалить").show_centered():
                self.app.server_store.remove(index)
                self.app.on_servers_changed()

    def set_filter(self, tag: str) -> None:
        index = self.mode.findText(tag)
        self.mode.setCurrentIndex(index if index >= 0 else 0)
        self._apply_filter()

    def update_ping(self, index: int, result: PingResult) -> None:
        for row in self.rows:
            if row.index == index:
                row.set_ping(result)

    def refresh(self) -> None:
        self._apply_filter()
        self.version.refresh()
        self.friends.refresh()

    def on_show(self) -> None:
        self.refresh()
        if not self.app.pings:
            self.app.refresh_servers()
