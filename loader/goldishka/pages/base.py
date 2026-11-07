"""Общий каркас страницы: заголовок, действия и прокрутка."""

from __future__ import annotations

from typing import TYPE_CHECKING

from PyQt5.QtCore import Qt
from PyQt5.QtWidgets import QHBoxLayout, QVBoxLayout, QWidget

from ..ui.components import IconTile
from ..ui.widgets import PageScroll, h1, muted

if TYPE_CHECKING:
    from ..app import MainWindow


class BasePage(QWidget):
    def __init__(self, app: "MainWindow", title: str, subtitle: str = "", icon: str | None = None):
        super().__init__()
        self.app = app
        root = QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(16)

        head = QHBoxLayout()
        head.setSpacing(12)
        if icon:
            head.addWidget(IconTile(icon, size=42))
        titles = QVBoxLayout()
        titles.setSpacing(2)
        titles.addWidget(h1(title, 25))
        if subtitle:
            titles.addWidget(muted(subtitle, 11))
        head.addLayout(titles, 1)
        self.actions = QHBoxLayout()
        self.actions.setSpacing(8)
        self.actions.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        head.addLayout(self.actions)
        root.addLayout(head)

        self.scroll = PageScroll(margins=(0, 0, 6, 0))
        root.addWidget(self.scroll, 1)

    def add_action(self, widget: QWidget) -> QWidget:
        self.actions.addWidget(widget)
        return widget

    def on_show(self) -> None:
        pass

    def refresh(self) -> None:
        pass
