"""Главная страница: приветствие, версия, серверы, новости и режимы."""

from __future__ import annotations

from typing import TYPE_CHECKING

from PyQt5.QtCore import QRectF, Qt
from PyQt5.QtGui import QColor, QLinearGradient, QPainter, QPen
from PyQt5.QtWidgets import QHBoxLayout, QVBoxLayout, QWidget

from ..core.news import NEWS, NewsItem
from ..core.servers import PingResult
from ..theme import MUTED, TEXT, THEME
from ..ui.assets import paint_cover, pixmap
from ..ui.components import (
    FriendsPanel,
    ModeCard,
    NewsCard,
    ServerRow,
    VersionPanel,
)
from ..ui.dialogs import NewsListDialog
from ..ui.widgets import Chip, GlassCard, GoldButton, PageScroll, h1, h2, muted, rounded

if TYPE_CHECKING:
    from ..app import MainWindow


class HeroArt(QWidget):
    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.setMinimumWidth(330)
        self.setFixedHeight(186)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = rounded(rect, 14)
        p.setClipPath(path)
        p.fillRect(rect, QColor(18, 15, 10))
        paint_cover(p, rect, pixmap("hero.jpg"))
        fade = QLinearGradient(rect.topLeft(), rect.topRight())
        fade.setColorAt(0.0, QColor(23, 19, 13, 245))
        fade.setColorAt(0.45, QColor(23, 19, 13, 90))
        fade.setColorAt(1.0, QColor(23, 19, 13, 0))
        p.fillRect(rect, fade)
        bottom = QLinearGradient(rect.topLeft(), rect.bottomLeft())
        bottom.setColorAt(0.55, QColor(10, 8, 5, 0))
        bottom.setColorAt(1.0, QColor(10, 8, 5, 130))
        p.fillRect(rect, bottom)
        p.setClipping(False)
        p.setPen(QPen(THEME.accent_a(45), 1))
        p.setBrush(Qt.NoBrush)
        p.drawPath(path)
        p.end()


class HeroCard(GlassCard):
    def __init__(self, app: "MainWindow", parent: QWidget | None = None):
        super().__init__(parent, radius=18)
        self.app = app
        lay = QHBoxLayout(self)
        lay.setContentsMargins(24, 20, 20, 20)
        lay.setSpacing(18)
        left = QVBoxLayout()
        left.setSpacing(8)
        badge = QHBoxLayout()
        badge.addWidget(Chip("GOLDISH CLIENT", THEME.accent, dot=True), 0, Qt.AlignLeft)
        badge.addStretch(1)
        left.addLayout(badge)
        self.greeting = h1("Привет, Goldishka!", 29)
        left.addWidget(self.greeting)
        self.subtitle = muted(
            "Запускай Minecraft и погружайся в мир Goldish: чистый интерфейс, "
            "умный HUD и настройки, которые не мешают играть.", 12)
        self.subtitle.setMaximumWidth(420)
        left.addWidget(self.subtitle)
        left.addStretch(1)
        buttons = QHBoxLayout()
        buttons.setSpacing(8)
        play = GoldButton("Играть", "primary", "play", height=40)
        play.setMinimumWidth(132)
        play.clicked.connect(lambda: app.launch())
        buttons.addWidget(play)
        servers = GoldButton("Сервера", "ghost", "globe", height=40)
        servers.clicked.connect(lambda: app.open_page("play"))
        buttons.addWidget(servers)
        buttons.addStretch(1)
        left.addLayout(buttons)
        lay.addLayout(left, 1)
        lay.addWidget(HeroArt(), 0)

    def refresh(self) -> None:
        self.greeting.setText(f"Привет, {self.app.config.nickname or 'игрок'}!")


class PopularServersPanel(GlassCard):
    def __init__(self, app: "MainWindow", parent: QWidget | None = None):
        super().__init__(parent, radius=16)
        self.app = app
        self.rows: list[ServerRow] = []
        body = QVBoxLayout(self)
        body.setContentsMargins(12, 12, 12, 12)
        body.setSpacing(4)
        head = QHBoxLayout()
        head.addWidget(h2("Сервера", 14))
        head.addStretch(1)
        all_btn = GoldButton("Все", "ghost", height=26)
        all_btn.setMinimumWidth(58)
        all_btn.clicked.connect(lambda: app.open_page("play"))
        head.addWidget(all_btn)
        body.addLayout(head)
        self.list = QVBoxLayout()
        self.list.setSpacing(1)
        body.addLayout(self.list)
        self.refresh()

    def _clear(self) -> None:
        while self.list.count():
            item = self.list.takeAt(0)
            if item.widget():
                item.widget().deleteLater()
        self.rows.clear()

    def refresh(self) -> None:
        self._clear()
        for index, entry in enumerate(self.app.server_store.popular(5)):
            row = ServerRow(self.app, entry, index, compact=True)
            result = self.app.pings.get(index)
            if result is not None:
                row.set_ping(result)
            self.list.addWidget(row)
            self.rows.append(row)

    def update_ping(self, index: int, result: PingResult) -> None:
        for row in self.rows:
            if row.index == index:
                row.set_ping(result)


class HomePage(QWidget):
    def __init__(self, app: "MainWindow"):
        super().__init__()
        self.app = app
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)
        self.scroll = PageScroll(margins=(0, 0, 6, 0))
        outer.addWidget(self.scroll)

        # --- верх: герой + правая колонка --------------------------------
        top = QHBoxLayout()
        top.setSpacing(16)
        left = QVBoxLayout()
        left.setSpacing(16)
        self.hero = HeroCard(app)
        left.addWidget(self.hero)
        self.news_title = h2("Последние новости", 17)
        news_head = QHBoxLayout()
        news_head.addWidget(self.news_title)
        news_head.addStretch(1)
        more = GoldButton("Все новости", "ghost", height=28)
        more.setMinimumWidth(112)
        more.clicked.connect(lambda: NewsListDialog(self.app, NEWS).show_centered())
        news_head.addWidget(more)
        left.addLayout(news_head)
        news_row = QHBoxLayout()
        news_row.setSpacing(12)
        for item in NEWS:
            card = NewsCard(item)
            card.clicked.connect(self._open_news)
            news_row.addWidget(card, 1)
        left.addLayout(news_row)

        modes_head = QHBoxLayout()
        modes_head.addWidget(h2("Выбор игроков", 17))
        modes_head.addStretch(1)
        modes_head.addWidget(muted("Подборка режимов", 10))
        left.addLayout(modes_head)
        modes_row = QHBoxLayout()
        modes_row.setSpacing(12)
        for title, subtitle, image, tag in (
            ("Skyblock", "острова и экономика", "mode_skyblock.jpg", "Выживание"),
            ("Anarchy", "без правил", "mode_anarchy.jpg", "Анархия"),
            ("Technologic", "моды и автоматизация", "mode_technologic.jpg", "Мини-игры"),
            ("Survival", "выживание с друзьями", "mode_survival.jpg", "Выживание"),
        ):
            card = ModeCard(title, subtitle, image, tag)
            card.clicked.connect(self._open_mode)
            modes_row.addWidget(card, 1)
        left.addLayout(modes_row)
        left.addStretch(1)

        top.addLayout(left, 1)

        right = QVBoxLayout()
        right.setSpacing(12)
        right.setContentsMargins(0, 0, 0, 0)
        self.version = VersionPanel(app)
        right.addWidget(self.version)
        self.servers = PopularServersPanel(app)
        right.addWidget(self.servers)
        self.friends = FriendsPanel(app)
        right.addWidget(self.friends)
        right.addStretch(1)
        right_wrap = QWidget()
        right_wrap.setFixedWidth(304)
        right_wrap.setLayout(right)
        top.addWidget(right_wrap, 0)

        self.scroll.body.addLayout(top)
        self.scroll.body.addStretch(1)

    def _open_news(self, item: NewsItem) -> None:
        NewsListDialog(self.app, [item]).show_centered()

    def _open_mode(self, tag: str) -> None:
        self.app.open_play(tag)

    def refresh(self) -> None:
        self.hero.refresh()
        self.version.refresh()
        self.servers.refresh()
        self.friends.refresh()

    def update_ping(self, index: int, result: PingResult) -> None:
        self.servers.update_ping(index, result)

    def on_show(self) -> None:
        self.refresh()
