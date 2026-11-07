"""Составные карточки: серверы, новости, режимы, моды, паки."""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import TYPE_CHECKING

from PyQt5.QtCore import QPointF, QRectF, QSize, Qt, pyqtSignal
from PyQt5.QtGui import (
    QColor,
    QFont,
    QFontMetrics,
    QLinearGradient,
    QPainter,
    QPainterPath,
    QPen,
    QPixmap,
    QRadialGradient,
)
from PyQt5.QtWidgets import QHBoxLayout, QLabel, QMenu, QSizePolicy, QVBoxLayout, QWidget

from ..core.mods import ModInfo, PackInfo
from ..core.news import NewsItem
from ..core.servers import PingResult, ServerEntry
from ..theme import DIM, GREEN, MUTED, RED, TEXT, THEME, YELLOW, font, heading, qc
from . import icons
from .assets import paint_cover, pixmap
from .widgets import (
    Avatar,
    Chip,
    Divider,
    GlassCard,
    GoldButton,
    HoverRow,
    IconButton,
    PingBars,
    StatusDot,
    _animate,
    h1,
    h2,
    label,
    muted,
    rounded,
)

if TYPE_CHECKING:
    from ..app import MainWindow


def _fmt_number(value: int) -> str:
    return f"{value:,}".replace(",", " ")


def _pixmap_from_bytes(data: bytes | None) -> QPixmap:
    pm = QPixmap()
    if data:
        pm.loadFromData(data)
    return pm


class ServerBadge(QWidget):
    def __init__(self, entry: ServerEntry, size: int = 44, parent: QWidget | None = None):
        super().__init__(parent)
        self.entry = entry
        self._size = size
        self.setFixedSize(size, size)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        base = QColor(self.entry.color)
        dark = base.darker(230)
        g = QLinearGradient(rect.topLeft(), rect.bottomRight())
        g.setColorAt(0, base)
        g.setColorAt(1, dark)
        p.setPen(QPen(QColor(255, 255, 255, 40), 1))
        p.setBrush(g)
        p.drawRoundedRect(rect, self._size * 0.28, self._size * 0.28)
        p.setPen(QColor(20, 15, 6, 220))
        p.setFont(heading(max(12, int(self._size * 0.38))))
        p.drawText(rect, Qt.AlignCenter, self.entry.name[:1].upper())
        p.end()


class ServerRow(HoverRow):
    menu_requested = pyqtSignal(int, object)

    def __init__(self, app: "MainWindow", entry: ServerEntry, index: int,
                 compact: bool = False, parent: QWidget | None = None):
        super().__init__(parent, radius=12)
        self.app = app
        self.entry = entry
        self.index = index
        self.compact = compact
        self.setContextMenuPolicy(Qt.CustomContextMenu)
        self.customContextMenuRequested.connect(self._menu)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(10 if compact else 12, 7, 10 if compact else 12, 7)
        lay.setSpacing(8 if compact else 12)
        badge = ServerBadge(entry, 34 if compact else 44)
        lay.addWidget(badge)

        text = QVBoxLayout()
        text.setSpacing(1 if compact else 2)
        top = QHBoxLayout()
        top.setSpacing(7)
        name = label(entry.name, 12 if compact else 14, TEXT, bold=True)
        top.addWidget(name)
        if not compact:
            top.addWidget(Chip(entry.version, THEME.accent, size=10))
            top.addWidget(Chip(entry.tag, QColor(entry.color), size=10))
        top.addStretch(1)
        text.addLayout(top)
        if not compact:
            description = muted(entry.description or entry.address, 11)
            description.setWordWrap(False)
            text.addWidget(description)
        else:
            address = muted(entry.address, 9)
            address.setWordWrap(False)
            text.addWidget(address)
        lay.addLayout(text, 1)

        status = QVBoxLayout()
        status.setSpacing(1)
        line = QHBoxLayout()
        line.setSpacing(2)
        self.dot = StatusDot(GREEN if compact else MUTED, 6)
        line.addWidget(self.dot)
        self.players_label = label("…", 10 if compact else 11, MUTED)
        line.addWidget(self.players_label)
        status.addLayout(line)
        lay.addLayout(status)

        self.ping_bars = PingBars(None)
        lay.addWidget(self.ping_bars)
        self.ping_label = label("", 10, MUTED)
        self.ping_label.setFixedWidth(46)
        self.ping_label.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        if not compact:
            lay.addWidget(self.ping_label)
        else:
            self.ping_label.hide()

        if compact:
            play = IconButton("play", size=28, icon_size=12, color=THEME.accent, tooltip="Играть")
            play.clicked.connect(lambda: self.app.launch(self.entry))
            lay.addWidget(play)
        else:
            play = GoldButton("Играть", "secondary", "play", height=32)
            play.setMinimumWidth(98)
            play.clicked.connect(lambda: self.app.launch(self.entry))
            lay.addWidget(play)
        self.setMinimumHeight(58 if compact else 72)

    def _menu(self, pos) -> None:
        self.menu_requested.emit(self.index, self.mapToGlobal(pos))

    def set_ping(self, result: PingResult) -> None:
        self.ping_bars.set_latency(result.latency if result.online else None)
        if result.online:
            self.dot.set_color(GREEN)
            self.players_label.setText(f"{_fmt_number(result.players)}")
            self.players_label.setStyleSheet(f"color: {TEXT}; background: transparent;")
            self.ping_label.setText(f"{result.latency} мс" if result.latency is not None else "—")
            self.ping_label.setStyleSheet(f"color: {MUTED}; background: transparent;")
        else:
            self.dot.set_color(DIM)
            self.players_label.setText("оффлайн")
            self.players_label.setStyleSheet(f"color: {DIM}; background: transparent;")
            self.ping_label.setText("—")


class FriendRow(HoverRow):
    def __init__(self, name: str, parent: QWidget | None = None):
        super().__init__(parent, radius=10)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(8, 6, 8, 6)
        lay.setSpacing(10)
        lay.addWidget(Avatar(name, 32, ring=False))
        text = QVBoxLayout()
        text.setSpacing(0)
        text.addWidget(label(name, 12, TEXT, bold=True))
        text.addWidget(muted("в списке друзей", 10))
        lay.addLayout(text, 1)
        self.setFixedHeight(50)


class NewsCard(QWidget):
    clicked = pyqtSignal(object)

    def __init__(self, item: NewsItem, parent: QWidget | None = None):
        super().__init__(parent)
        self.item = item
        self._hover = 0.0
        self.setMinimumHeight(218)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.setCursor(Qt.PointingHandCursor)

    def enterEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 1.0, 150)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 0.0, 180)
        super().leaveEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if event.button() == Qt.LeftButton:
            self.clicked.emit(self.item)
        super().mouseReleaseEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = rounded(rect, 14)
        p.setClipPath(path)
        pm = pixmap(self.item.image)
        image_rect = QRectF(rect.left(), rect.top(), rect.width(), rect.height() * 0.62)
        p.fillRect(rect, QColor(18, 15, 10))
        paint_cover(p, image_rect, pm)
        overlay = QLinearGradient(rect.topLeft(), rect.bottomLeft())
        overlay.setColorAt(0.0, QColor(10, 8, 5, 30))
        overlay.setColorAt(0.55, QColor(13, 11, 8, 150))
        overlay.setColorAt(1.0, QColor(15, 13, 9, 248))
        p.fillRect(rect, overlay)
        # тег
        chip_rect = QRectF(rect.left() + 12, rect.top() + 12, 86, 22)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(20, 16, 9, 190))
        p.drawRoundedRect(chip_rect, 7, 7)
        p.setPen(THEME.accent)
        p.setFont(font(9, QFont.DemiBold))
        p.drawText(chip_rect, Qt.AlignCenter, self.item.tag.upper())
        # дата в правом верхнем углу
        date_rect = QRectF(rect.right() - 98, rect.top() + 12, 86, 22)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(20, 16, 9, 190))
        p.drawRoundedRect(date_rect, 7, 7)
        p.setPen(QColor(TEXT))
        p.setFont(font(9))
        p.drawText(date_rect, Qt.AlignCenter, self.item.date)
        # заголовок и описание
        p.setPen(QColor(TEXT))
        p.setFont(heading(15))
        title = QFontMetrics(heading(15)).elidedText(
            self.item.title, Qt.ElideRight, int(rect.width() - 28))
        p.drawText(QRectF(rect.left() + 14, rect.bottom() - 64, rect.width() - 28, 24),
                   Qt.AlignLeft | Qt.AlignVCenter, title)
        p.setPen(QColor(MUTED))
        p.setFont(font(10))
        p.drawText(QRectF(rect.left() + 14, rect.bottom() - 40, rect.width() - 28, 16),
                   Qt.AlignLeft | Qt.AlignVCenter,
                   QFontMetrics(font(10)).elidedText(self.item.text, Qt.ElideRight, int(rect.width() - 28)))
        p.setClipping(False)
        p.setPen(QPen(THEME.accent_a(int(45 + 60 * self._hover)), 1))
        p.setBrush(Qt.NoBrush)
        p.drawPath(path)
        p.end()


class ModeCard(QWidget):
    clicked = pyqtSignal(str)

    def __init__(self, title: str, subtitle: str, image: str, tag: str, parent: QWidget | None = None):
        super().__init__(parent)
        self.title = title
        self.subtitle = subtitle
        self.image = image
        self.tag = tag
        self._hover = 0.0
        self.setMinimumSize(150, 104)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.setCursor(Qt.PointingHandCursor)

    def enterEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 1.0, 150)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 0.0, 180)
        super().leaveEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if event.button() == Qt.LeftButton:
            self.clicked.emit(self.tag)
        super().mouseReleaseEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = rounded(rect, 13)
        p.setClipPath(path)
        p.fillRect(rect, QColor(18, 15, 10))
        paint_cover(p, rect, pixmap(self.image))
        g = QLinearGradient(rect.topLeft(), rect.bottomLeft())
        g.setColorAt(0.0, QColor(8, 6, 4, 30))
        g.setColorAt(0.55, QColor(10, 8, 5, 120))
        g.setColorAt(1.0, QColor(10, 8, 5, 235))
        p.fillRect(rect, g)
        p.setPen(QColor(TEXT))
        p.setFont(heading(14))
        p.drawText(QRectF(rect.left() + 12, rect.bottom() - 42, rect.width() - 24, 20),
                   Qt.AlignLeft | Qt.AlignVCenter, self.title)
        p.setPen(QColor(MUTED))
        p.setFont(font(9))
        p.drawText(QRectF(rect.left() + 12, rect.bottom() - 22, rect.width() - 24, 14),
                   Qt.AlignLeft | Qt.AlignVCenter, self.subtitle)
        # стрелка
        arrow_color = THEME.accent if self._hover > 0.4 else QColor(255, 255, 255, 90)
        icons.paint_icon(p, "arrow-right",
                         QRectF(rect.right() - 26, rect.top() + 10, 15, 15), arrow_color, 1.8)
        p.setClipping(False)
        p.setPen(QPen(THEME.accent_a(int(35 + 90 * self._hover)), 1))
        p.setBrush(Qt.NoBrush)
        p.drawPath(path)
        p.end()


class ThemeCard(QWidget):
    clicked = pyqtSignal(str)

    def __init__(self, key: str, name: str, image: str, accent: QColor,
                 selected: bool = False, parent: QWidget | None = None):
        super().__init__(parent)
        self.key = key
        self.name = name
        self.image = image
        self.accent = accent
        self.selected = selected
        self._hover = 0.0
        self.setMinimumSize(150, 96)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Fixed)
        self.setCursor(Qt.PointingHandCursor)

    def set_selected(self, value: bool) -> None:
        self.selected = value
        self.update()

    def enterEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 1.0, 150)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 0.0, 180)
        super().leaveEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if event.button() == Qt.LeftButton:
            self.clicked.emit(self.key)
        super().mouseReleaseEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = rounded(rect, 12)
        p.setClipPath(path)
        p.fillRect(rect, QColor(18, 15, 10))
        paint_cover(p, rect, pixmap(self.image))
        overlay = QLinearGradient(rect.topLeft(), rect.bottomLeft())
        overlay.setColorAt(0.0, QColor(8, 6, 4, 30))
        overlay.setColorAt(1.0, QColor(10, 8, 5, 240))
        p.fillRect(rect, overlay)
        # мазок акцента
        p.setPen(Qt.NoPen)
        p.setBrush(self.accent)
        p.drawRoundedRect(QRectF(rect.left() + 12, rect.top() + 12, 26, 4), 2, 2)
        p.setPen(QColor(TEXT))
        p.setFont(heading(12))
        p.drawText(QRectF(rect.left() + 12, rect.bottom() - 28, rect.width() - 40, 18),
                   Qt.AlignLeft | Qt.AlignVCenter, self.name)
        if self.selected:
            circle = QRectF(rect.right() - 30, rect.bottom() - 30, 18, 18)
            p.setPen(Qt.NoPen)
            p.setBrush(self.accent)
            p.drawEllipse(circle)
            icons.paint_icon(p, "check", circle.adjusted(3, 3, -3, -3), QColor("#1C1304"), 2.2)
        p.setClipping(False)
        p.setPen(QPen(self.accent if self.selected else THEME.accent_a(int(25 + 60 * self._hover)),
                      1.5 if self.selected else 1))
        p.setBrush(Qt.NoBrush)
        p.drawPath(path)
        p.end()


class IconTile(QWidget):
    """Квадратная иконка 40×40: картинка из архива или векторный символ."""

    def __init__(self, icon_name: str = "mods", data: bytes | None = None,
                 size: int = 40, circle: bool = False, parent: QWidget | None = None):
        super().__init__(parent)
        self.icon_name = icon_name
        self.data = data
        self.circle = circle
        self.setFixedSize(size, size)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        pm = _pixmap_from_bytes(self.data)
        if not pm.isNull():
            path = QPainterPath()
            if self.circle:
                path.addEllipse(rect)
            else:
                path.addRoundedRect(rect, 10, 10)
            p.setClipPath(path)
            p.drawPixmap(rect, pm, QRectF(0, 0, pm.width(), pm.height()))
            p.setClipping(False)
            p.setPen(QPen(QColor(255, 255, 255, 40), 1))
            p.setBrush(Qt.NoBrush)
            p.drawPath(path)
        elif self.circle:
            p.setPen(QPen(THEME.accent_a(55), 1))
            p.setBrush(THEME.accent_a(16))
            p.drawEllipse(rect)
            icons.paint_icon(p, self.icon_name, rect.adjusted(15, 15, -15, -15), THEME.accent, 1.6)
        else:
            p.setPen(QPen(QColor(255, 255, 255, 28), 1))
            p.setBrush(QColor(255, 255, 255, 12))
            p.drawRoundedRect(rect, 10, 10)
            icons.paint_icon(p, self.icon_name, rect.adjusted(9, 9, -9, -9), THEME.accent, 1.5)
        p.end()


class ModRow(HoverRow):
    toggled = pyqtSignal(object, bool)
    removed = pyqtSignal(object)

    def __init__(self, info: ModInfo, parent: QWidget | None = None):
        super().__init__(parent, radius=12)
        self.info = info
        lay = QHBoxLayout(self)
        lay.setContentsMargins(12, 9, 10, 9)
        lay.setSpacing(12)
        lay.addWidget(IconTile("mods", info.icon, 40))
        text = QVBoxLayout()
        text.setSpacing(1)
        top = QHBoxLayout()
        top.setSpacing(7)
        top.addWidget(label(info.title, 13, TEXT, bold=True))
        if info.version:
            top.addWidget(Chip(info.version, THEME.accent, size=9))
        if info.builtin:
            top.addWidget(Chip("встроен", QColor(GREEN), size=9))
        if not info.enabled:
            top.addWidget(Chip("выключен", QColor(DIM), size=9))
        top.addStretch(1)
        text.addLayout(top)
        desc = info.description or info.filename
        text.addWidget(muted(desc[:110], 10))
        lay.addLayout(text, 1)
        size = f"{info.size / 1024:.0f} КБ" if info.size < 1024 * 1024 else f"{info.size / 1048576:.1f} МБ"
        size_label = label(size, 10, DIM)
        size_label.setFixedWidth(58)
        size_label.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        lay.addWidget(size_label)
        from .widgets import ToggleSwitch

        toggle = ToggleSwitch(info.enabled)
        toggle.toggled.connect(lambda checked: self.toggled.emit(self.info, checked))
        lay.addWidget(toggle)
        remove = IconButton("trash", size=30, icon_size=14, tooltip="Удалить")
        remove.clicked.connect(lambda: self.removed.emit(self.info))
        lay.addWidget(remove)
        self.setMinimumHeight(62)

class PackRow(HoverRow):
    toggled = pyqtSignal(object, bool)
    removed = pyqtSignal(object)

    def __init__(self, info: PackInfo, parent: QWidget | None = None):
        super().__init__(parent, radius=12)
        self.info = info
        lay = QHBoxLayout(self)
        lay.setContentsMargins(12, 9, 10, 9)
        lay.setSpacing(12)
        lay.addWidget(IconTile("packs", info.icon, 40))
        text = QVBoxLayout()
        text.setSpacing(1)
        top = QHBoxLayout()
        top.setSpacing(7)
        top.addWidget(label(info.name, 13, TEXT, bold=True))
        if info.enabled:
            top.addWidget(Chip("включён", QColor(GREEN), size=9))
        top.addStretch(1)
        text.addLayout(top)
        text.addWidget(muted((info.description or info.filename)[:100], 10))
        lay.addLayout(text, 1)
        size = f"{info.size / 1048576:.1f} МБ" if info.size >= 1024 * 1024 else f"{info.size / 1024:.0f} КБ"
        size_label = label(size, 10, DIM)
        size_label.setFixedWidth(58)
        size_label.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        lay.addWidget(size_label)
        from .widgets import ToggleSwitch

        toggle = ToggleSwitch(info.enabled)
        toggle.toggled.connect(lambda checked: self.toggled.emit(self.info, checked))
        lay.addWidget(toggle)
        remove = IconButton("trash", size=30, icon_size=14, tooltip="Удалить")
        remove.clicked.connect(lambda: self.removed.emit(self.info))
        lay.addWidget(remove)
        self.setMinimumHeight(62)

class EmptyState(QWidget):
    def __init__(self, icon: str, title: str, text: str, parent: QWidget | None = None):
        super().__init__(parent)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(20, 34, 20, 34)
        lay.setSpacing(10)
        lay.setAlignment(Qt.AlignCenter)
        lay.addWidget(IconTile(icon, None, 54, circle=True), 0, Qt.AlignHCenter)
        title_label = label(title, 14, TEXT, bold=True)
        title_label.setAlignment(Qt.AlignCenter)
        lay.addWidget(title_label)
        text_label = muted(text, 11)
        text_label.setAlignment(Qt.AlignCenter)
        lay.addWidget(text_label)

class VersionPanel(GlassCard):
    """Карточка версии клиента с живым статусом готовности."""

    def __init__(self, app: "MainWindow", parent: QWidget | None = None):
        super().__init__(parent, radius=16)
        self.app = app
        body = QVBoxLayout(self)
        body.setContentsMargins(16, 14, 16, 14)
        body.setSpacing(7)
        top = QHBoxLayout()
        top.addWidget(muted("Версия клиента", 11))
        top.addStretch(1)
        self.chip = Chip("актуальная", QColor(GREEN), size=9)
        top.addWidget(self.chip)
        body.addLayout(top)
        row = QHBoxLayout()
        row.setSpacing(8)
        self.version = h1("1.21.11", 26)
        row.addWidget(self.version)
        self.loader = Chip("Fabric 0.19.5", THEME.accent, size=10)
        row.addWidget(self.loader, 0, Qt.AlignBottom)
        row.addStretch(1)
        body.addLayout(row)
        status = QHBoxLayout()
        status.setSpacing(5)
        self.dot = StatusDot(GREEN, 6)
        status.addWidget(self.dot)
        self.status = label("Проверяем…", 11, MUTED)
        status.addWidget(self.status, 1)
        body.addLayout(status)
        button = GoldButton("Сменить версию", "secondary", "refresh", height=34)
        button.clicked.connect(lambda: app.open_page("launch"))
        body.addWidget(button)
        self.refresh()

    def refresh(self) -> None:
        ready, text = self.app.version_status()
        self.dot.set_color(GREEN if ready else YELLOW)
        self.status.setText(text)
        self.chip.setText("готова" if ready else "не установлена")
        self.chip.set_color(QColor(GREEN) if ready else QColor(YELLOW))
        self.version.setText(self.app.config.mc_version)
        self.loader.setText(f"Fabric {self.app.config.loader_version}")
        self.loader.set_color(THEME.accent)


class FriendsPanel(GlassCard):
    def __init__(self, app: "MainWindow", limit: int = 5, parent: QWidget | None = None):
        super().__init__(parent, radius=16)
        self.app = app
        self.limit = limit
        body = QVBoxLayout(self)
        body.setContentsMargins(14, 12, 14, 12)
        body.setSpacing(6)
        head = QHBoxLayout()
        head.addWidget(h2("Друзья", 14))
        head.addStretch(1)
        self.count = Chip("0", THEME.accent, size=9)
        head.addWidget(self.count)
        body.addLayout(head)
        self.rows = QVBoxLayout()
        self.rows.setSpacing(2)
        body.addLayout(self.rows)
        self.refresh()

    def _clear(self) -> None:
        while self.rows.count():
            item = self.rows.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

    def refresh(self) -> None:
        self._clear()
        data = self.app.client_data()
        self.count.setText(str(len(data.friends)))
        if not data.friends:
            hint = muted("Друзей пока нет. Добавьте через /goldish friend add <ник>.", 10)
            self.rows.addWidget(hint)
            return
        for name in data.friends[: self.limit]:
            self.rows.addWidget(FriendRow(name))
