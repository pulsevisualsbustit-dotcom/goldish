"""Базовые «стеклянные» виджеты лаунчера."""

from __future__ import annotations

import hashlib
from typing import Callable

from PyQt5.QtCore import (
    QEasingCurve,
    QEvent,
    QPoint,
    QPointF,
    QRectF,
    QSize,
    Qt,
    QTimer,
    QVariantAnimation,
    pyqtSignal,
)
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
from PyQt5.QtWidgets import (
    QAbstractButton,
    QComboBox,
    QDialog,
    QFrame,
    QGraphicsDropShadowEffect,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QSlider,
    QVBoxLayout,
    QWidget,
)

from ..theme import (
    BORDER,
    CARD_BOTTOM,
    CARD_TOP,
    DIM,
    GREEN,
    MUTED,
    RED,
    TEXT,
    THEME,
    YELLOW,
    font,
    heading,
    qc,
)
from . import icons


# ---------------------------------------------------------------------------
# геометрия и отрисовка
# ---------------------------------------------------------------------------
def rounded(rect: QRectF, radius: float) -> QPainterPath:
    path = QPainterPath()
    path.addRoundedRect(rect, radius, radius)
    return path


def paint_glass(
    p: QPainter,
    rect: QRectF,
    radius: float = 16,
    top: QColor | None = None,
    bottom: QColor | None = None,
    border: QColor | None = None,
    hover: float = 0.0,
    shadow: bool = False,
) -> QPainterPath:
    path = rounded(rect, radius)
    if shadow:
        for i, alpha in ((4, 10), (8, 8), (14, 5)):
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(0, 0, 0, alpha))
            p.drawPath(rounded(rect.adjusted(-i, -i + 2, i, i + 2), radius + i))
    grad = QLinearGradient(rect.topLeft(), rect.bottomLeft())
    grad.setColorAt(0.0, top or CARD_TOP)
    grad.setColorAt(1.0, bottom or CARD_BOTTOM)
    p.setPen(Qt.NoPen)
    p.fillPath(path, grad)
    if hover > 0:
        p.fillPath(path, QColor(255, 255, 255, int(9 * hover)))
    p.setBrush(Qt.NoBrush)
    p.setPen(QPen(border or BORDER, 1))
    p.drawPath(path)
    p.save()
    p.setClipPath(path)
    p.setPen(QPen(QColor(255, 255, 255, 18), 1))
    p.drawLine(QPointF(rect.left() + radius, rect.top() + 0.6),
               QPointF(rect.right() - radius, rect.top() + 0.6))
    p.restore()
    return path


def _animate(widget: QWidget, attr: str, start: float, end: float, duration: int = 140) -> QVariantAnimation:
    anim = QVariantAnimation(widget)
    anim.setDuration(duration if THEME.animations else 0)
    anim.setStartValue(start)
    anim.setEndValue(end)
    anim.setEasingCurve(QEasingCurve.OutCubic)
    anim.valueChanged.connect(lambda v: (setattr(widget, attr, float(v)), widget.update()))
    anim.start(QVariantAnimation.DeleteWhenStopped)
    return anim


def apply_shadow(widget: QWidget, blur: int = 34, alpha: int = 150, dy: int = 10) -> None:
    effect = QGraphicsDropShadowEffect(widget)
    effect.setBlurRadius(blur)
    effect.setOffset(0, dy)
    effect.setColor(QColor(0, 0, 0, alpha))
    widget.setGraphicsEffect(effect)


# ---------------------------------------------------------------------------
# текстовые помощники
# ---------------------------------------------------------------------------
def label(text: str, size: int = 13, color: str = TEXT, bold: bool = False) -> QLabel:
    w = QLabel(text)
    weight = QFont.DemiBold if bold else QFont.Normal
    w.setFont(font(size, weight))
    w.setStyleSheet(f"color: {color}; background: transparent;")
    return w


def h1(text: str, size: int = 24) -> QLabel:
    w = QLabel(text)
    w.setFont(heading(size))
    w.setStyleSheet(f"color: {TEXT}; background: transparent;")
    return w


def h2(text: str, size: int = 16) -> QLabel:
    w = QLabel(text)
    w.setFont(heading(size))
    w.setStyleSheet(f"color: {TEXT}; background: transparent;")
    return w


def muted(text: str, size: int = 12) -> QLabel:
    w = QLabel(text)
    w.setFont(font(size))
    w.setStyleSheet(f"color: {MUTED}; background: transparent;")
    w.setWordWrap(True)
    return w


class AccentLabel(QLabel):
    """Текст в акцентном цвете, сам перекрашивается при смене темы."""

    def __init__(self, text: str = "", size: int = 12, bold: bool = True, parent: QWidget | None = None):
        super().__init__(text, parent)
        self.setFont(font(size, QFont.DemiBold if bold else QFont.Normal))
        self.setAttribute(Qt.WA_TranslucentBackground)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.TextAntialiasing)
        p.setFont(self.font())
        p.setPen(THEME.accent)
        p.drawText(self.rect(), int(self.alignment()) | Qt.AlignVCenter, self.text())
        p.end()


class Chip(QWidget):
    """Пилюля с версией / статусом."""

    def __init__(
        self,
        text: str,
        color: QColor | None = None,
        dot: bool = False,
        parent: QWidget | None = None,
        size: int = 11,
    ):
        super().__init__(parent)
        self._text = text
        self._color = color
        self._dot = dot
        self._size = size
        self.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Fixed)

    def setText(self, text: str) -> None:  # noqa: N802
        self._text = text
        self.updateGeometry()
        self.update()

    def set_color(self, color: QColor | None) -> None:
        self._color = color
        self.update()

    def _color_now(self) -> QColor:
        return self._color or THEME.accent

    def sizeHint(self) -> QSize:  # noqa: N802
        fm = QFontMetrics(font(self._size, QFont.DemiBold))
        width = fm.horizontalAdvance(self._text) + 20 + (12 if self._dot else 0)
        return QSize(max(38, width), 24)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        color = self._color_now()
        fill = QColor(color)
        fill.setAlpha(26)
        border = QColor(color)
        border.setAlpha(70)
        p.setPen(QPen(border, 1))
        p.setBrush(fill)
        p.drawRoundedRect(rect, 8, 8)
        x = 10
        if self._dot:
            p.setPen(Qt.NoPen)
            p.setBrush(color)
            p.drawEllipse(QPointF(x + 3, rect.center().y()), 3, 3)
            x += 12
        p.setPen(color)
        p.setFont(font(self._size, QFont.DemiBold))
        p.drawText(QRectF(x, 0, rect.width() - x - 6, rect.height()),
                   Qt.AlignVCenter | Qt.AlignLeft, self._text)
        p.end()


class Divider(QFrame):
    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.setFixedHeight(1)
        self.setStyleSheet("background: rgba(255,255,255,14); border: none;")


# ---------------------------------------------------------------------------
# карточки
# ---------------------------------------------------------------------------
class GlassCard(QWidget):
    def __init__(
        self,
        parent: QWidget | None = None,
        radius: int = 16,
        padding: int = 0,
        hoverable: bool = False,
        border: QColor | None = None,
    ):
        super().__init__(parent)
        self.radius = radius
        self.hoverable = hoverable
        self._hover = 0.0
        self._border = border
        self._body_layout: QVBoxLayout | None = None
        self.setAttribute(Qt.WA_StyledBackground, False)
        if padding:
            outer = QVBoxLayout(self)
            outer.setContentsMargins(padding, padding, padding, padding)
            outer.setSpacing(0)
            self._body_layout = QVBoxLayout()
            self._body_layout.setContentsMargins(0, 0, 0, 0)
            outer.addLayout(self._body_layout)

    @property
    def body(self) -> QVBoxLayout:
        """Внутренний layout. Создаётся только по запросу."""
        if self._body_layout is None:
            existing = self.layout()
            if existing is None:
                existing = QVBoxLayout(self)
                existing.setContentsMargins(0, 0, 0, 0)
            self._body_layout = existing  # type: ignore[assignment]
        return self._body_layout

    def enterEvent(self, event) -> None:  # noqa: N802
        if self.hoverable:
            _animate(self, "_hover", self._hover, 1.0)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        if self.hoverable:
            _animate(self, "_hover", self._hover, 0.0)
        super().leaveEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        paint_glass(p, QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5), self.radius,
                    border=self._border, hover=self._hover)
        p.end()


class HoverRow(QWidget):
    """Строка списка с мягкой подсветкой при наведении."""

    clicked = pyqtSignal()

    def __init__(self, parent: QWidget | None = None, radius: int = 12, selected: bool = False):
        super().__init__(parent)
        self.radius = radius
        self._hover = 0.0
        self._selected = selected
        self.setAttribute(Qt.WA_StyledBackground, False)

    def set_selected(self, value: bool) -> None:
        self._selected = value
        self.update()

    def enterEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 1.0, 120)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 0.0, 160)
        super().leaveEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if event.button() == Qt.LeftButton and self.rect().contains(event.pos()):
            self.clicked.emit()
        super().mouseReleaseEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        if self._hover > 0.01 or self._selected:
            fill = QColor(255, 255, 255, int(9 * self._hover))
            if self._selected:
                fill = THEME.accent_a(20)
            p.setPen(Qt.NoPen)
            p.setBrush(fill)
            p.drawRoundedRect(rect, self.radius, self.radius)
        p.end()


# ---------------------------------------------------------------------------
# кнопки
# ---------------------------------------------------------------------------
class GoldButton(QPushButton):
    def __init__(
        self,
        text: str = "",
        kind: str = "primary",
        icon: str | None = None,
        parent: QWidget | None = None,
        height: int = 38,
        radius: int = 10,
    ):
        super().__init__(text, parent)
        self.kind = kind
        self.icon_name = icon
        self.radius = radius
        self._hover = 0.0
        self.setCursor(Qt.PointingHandCursor)
        self.setMinimumHeight(height)
        self.setFont(font(13, QFont.DemiBold))
        self.setAttribute(Qt.WA_StyledBackground, False)
        self.setFocusPolicy(Qt.NoFocus)

    def sizeHint(self) -> QSize:  # noqa: N802
        fm = QFontMetrics(self.font())
        width = fm.horizontalAdvance(self.text()) + (30 if self.icon_name else 0) + 34
        return QSize(width, self.minimumHeight())

    def enterEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 1.0)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 0.0)
        super().leaveEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.TextAntialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        enabled = self.isEnabled()
        hover = self._hover if enabled else 0.0
        accent = THEME.accent
        accent2 = THEME.accent2
        text_color = QColor(TEXT)
        if self.kind == "primary":
            g = QLinearGradient(rect.topLeft(), rect.bottomRight())
            a1 = accent.lighter(int(100 + 12 * hover))
            a2 = accent2.lighter(int(100 + 10 * hover))
            g.setColorAt(0, a1)
            g.setColorAt(1, a2)
            p.setPen(Qt.NoPen)
            p.setBrush(g)
            p.drawRoundedRect(rect, self.radius, self.radius)
            p.setPen(QPen(QColor(255, 255, 255, 55), 1))
            p.drawRoundedRect(rect.adjusted(0.5, 0.5, -0.5, -0.5), self.radius - 0.5, self.radius - 0.5)
            text_color = QColor("#1E1505")
        elif self.kind == "danger":
            fill = QColor(RED)
            fill.setAlpha(28 + int(14 * hover))
            border = QColor(RED)
            border.setAlpha(90 + int(40 * hover))
            p.setPen(QPen(border, 1))
            p.setBrush(fill)
            p.drawRoundedRect(rect, self.radius, self.radius)
            text_color = QColor(RED)
        else:
            fill = QColor(255, 255, 255, 12 + int(10 * hover))
            border = QColor(255, 255, 255, 26 + int(22 * hover))
            if self.kind == "secondary":
                border = THEME.accent_a(55 + int(40 * hover))
                fill = THEME.accent_a(12 + int(8 * hover))
            p.setPen(QPen(border, 1))
            p.setBrush(fill)
            p.drawRoundedRect(rect, self.radius, self.radius)
        if not enabled:
            text_color = QColor(DIM)
            p.setOpacity(0.55)
        fm = QFontMetrics(self.font())
        text_w = fm.horizontalAdvance(self.text())
        icon_w = 18 if self.icon_name else 0
        gap = 8 if self.icon_name else 0
        total = text_w + icon_w + gap
        x = rect.center().x() - total / 2
        if self.icon_name:
            icons.paint_icon(p, self.icon_name, QRectF(x, rect.center().y() - 9, 18, 18), text_color, 1.7)
            x += icon_w + gap
        p.setPen(text_color)
        p.setFont(self.font())
        p.drawText(QRectF(x, rect.top(), text_w + 2, rect.height()),
                   Qt.AlignVCenter | Qt.AlignLeft, self.text())
        p.end()


class IconButton(QAbstractButton):
    def __init__(
        self,
        icon_name: str,
        parent: QWidget | None = None,
        size: int = 32,
        icon_size: int = 17,
        color: QColor | None = None,
        tooltip: str = "",
    ):
        super().__init__(parent)
        self.icon_name = icon_name
        self.icon_size = icon_size
        self._color = color
        self._hover = 0.0
        self.setFixedSize(size, size)
        self.setCursor(Qt.PointingHandCursor)
        self.setFocusPolicy(Qt.NoFocus)
        if tooltip:
            self.setToolTip(tooltip)

    def enterEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 1.0, 120)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 0.0, 160)
        super().leaveEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        if self._hover > 0.01:
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(255, 255, 255, int(20 * self._hover)))
            p.drawRoundedRect(rect, 8, 8)
        color = self._color or QColor(MUTED)
        if self._hover > 0.5:
            color = THEME.accent if self._color is None else self._color
        off = (rect.width() - self.icon_size) / 2
        icons.paint_icon(p, self.icon_name,
                         QRectF(off, off, self.icon_size, self.icon_size), color, 1.6)
        p.end()


class NavButton(QAbstractButton):
    def __init__(self, text: str, icon_name: str, parent: QWidget | None = None):
        super().__init__(parent)
        self.setText(text)
        self.icon_name = icon_name
        self._hover = 0.0
        self.setCheckable(True)
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedHeight(44)
        self.setFocusPolicy(Qt.NoFocus)

    def enterEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 1.0, 120)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 0.0, 160)
        super().leaveEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.TextAntialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        active = self.isChecked()
        if active:
            g = QLinearGradient(rect.topLeft(), rect.bottomRight())
            g.setColorAt(0, THEME.accent)
            g.setColorAt(1, THEME.accent2)
            p.setPen(Qt.NoPen)
            p.setBrush(g)
            p.drawRoundedRect(rect, 10, 10)
            text_color = QColor("#211704")
            icon_color = QColor("#211704")
        else:
            if self._hover > 0.01:
                p.setPen(Qt.NoPen)
                p.setBrush(QColor(255, 255, 255, int(12 * self._hover)))
                p.drawRoundedRect(rect, 10, 10)
            text_color = QColor(TEXT if self._hover > 0.4 else MUTED)
            icon_color = QColor(TEXT if self._hover > 0.4 else MUTED)
        icons.paint_icon(p, self.icon_name, QRectF(14, rect.center().y() - 9, 18, 18), icon_color, 1.6)
        p.setPen(text_color)
        p.setFont(font(12, QFont.DemiBold if active else QFont.Medium))
        p.drawText(QRectF(42, rect.top(), rect.width() - 52, rect.height()),
                   Qt.AlignVCenter | Qt.AlignLeft, self.text())
        if active:
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(255, 255, 255, 60))
            p.drawEllipse(QPointF(rect.right() - 16, rect.center().y()), 2.2, 2.2)
        p.end()


# ---------------------------------------------------------------------------
# переключатели и слайдеры
# ---------------------------------------------------------------------------
class ToggleSwitch(QAbstractButton):
    def __init__(self, checked: bool = False, parent: QWidget | None = None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setChecked(checked)
        self._pos = 1.0 if checked else 0.0
        self._hover = 0.0
        self.setFixedSize(42, 24)
        self.setCursor(Qt.PointingHandCursor)
        self.setFocusPolicy(Qt.NoFocus)
        self.toggled.connect(self._slide)

    def _slide(self, checked: bool) -> None:
        anim = QVariantAnimation(self)
        anim.setDuration(160 if THEME.animations else 0)
        anim.setStartValue(self._pos)
        anim.setEndValue(1.0 if checked else 0.0)
        anim.setEasingCurve(QEasingCurve.OutCubic)
        anim.valueChanged.connect(lambda v: (setattr(self, "_pos", float(v)), self.update()))
        anim.start(QVariantAnimation.DeleteWhenStopped)

    def enterEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 1.0, 120)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 0.0, 160)
        super().leaveEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        track = QRectF(rect.left(), rect.center().y() - 11, 42, 22)
        if self._pos > 0.01:
            g = QLinearGradient(track.topLeft(), track.bottomRight())
            g.setColorAt(0, THEME.accent)
            g.setColorAt(1, THEME.accent2)
            p.setPen(Qt.NoPen)
            p.setBrush(g)
            p.drawRoundedRect(track, 11, 11)
        else:
            p.setPen(QPen(QColor(255, 255, 255, 26 + int(18 * self._hover)), 1))
            p.setBrush(QColor(255, 255, 255, 12))
            p.drawRoundedRect(track, 11, 11)
        knob_x = track.left() + 3 + self._pos * (track.width() - 24)
        knob = QRectF(knob_x, track.top() + 3, 18, 18)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(0, 0, 0, 55))
        p.drawEllipse(knob.adjusted(0, 1.5, 0, 1.5))
        p.setBrush(QColor("#F7F1E4") if self._pos > 0.5 else QColor("#BDB4A4"))
        p.drawEllipse(knob)
        p.end()


class GoldSlider(QSlider):
    def __init__(self, parent: QWidget | None = None):
        super().__init__(Qt.Horizontal, parent)
        self._hover = 0.0
        self.setFixedHeight(26)
        self.setCursor(Qt.PointingHandCursor)
        self.setFocusPolicy(Qt.NoFocus)

    def enterEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 1.0, 120)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 0.0, 160)
        super().leaveEvent(event)

    def _value_at(self, x: float) -> int:
        margin = 9
        width = max(1.0, self.width() - margin * 2)
        ratio = min(1.0, max(0.0, (x - margin) / width))
        return round(self.minimum() + ratio * (self.maximum() - self.minimum()))

    def mousePressEvent(self, event) -> None:  # noqa: N802
        if event.button() == Qt.LeftButton:
            self.setSliderDown(True)
            self.setValue(self._value_at(event.pos().x()))
            event.accept()
            return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        if self.isSliderDown():
            self.setValue(self._value_at(event.pos().x()))
            event.accept()
            return
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if event.button() == Qt.LeftButton:
            self.setSliderDown(False)
            event.accept()
            return
        super().mouseReleaseEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        margin = 9
        width = self.width() - margin * 2
        track = QRectF(margin, self.height() / 2 - 3, width, 6)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(255, 255, 255, 18))
        p.drawRoundedRect(track, 3, 3)
        span = max(1, self.maximum() - self.minimum())
        ratio = (self.value() - self.minimum()) / span
        fill = QRectF(track.left(), track.top(), max(6.0, track.width() * ratio), track.height())
        g = QLinearGradient(track.topLeft(), track.topRight())
        g.setColorAt(0, THEME.accent)
        g.setColorAt(1, THEME.accent2)
        p.setBrush(g)
        p.drawRoundedRect(fill, 3, 3)
        x = track.left() + track.width() * ratio
        if self._hover > 0.01:
            p.setBrush(THEME.accent_a(int(40 * self._hover)))
            p.drawEllipse(QPointF(x, track.center().y()), 11, 11)
        p.setBrush(QColor("#F7F1E4"))
        p.setPen(QPen(THEME.accent, 2))
        p.drawEllipse(QPointF(x, track.center().y()), 7, 7)
        p.end()


class GoldCombo(QComboBox):
    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.setCursor(Qt.PointingHandCursor)
        self.setMinimumHeight(36)
        self.setFont(font(12))
        self.setFocusPolicy(Qt.StrongFocus)

    def paintEvent(self, event) -> None:  # noqa: N802
        super().paintEvent(event)
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        color = QColor(MUTED)
        icons.paint_icon(p, "chevron-down",
                         QRectF(self.width() - 27, self.height() / 2 - 7, 14, 14), color, 1.8)
        p.end()


class SearchBox(QWidget):
    textChanged = pyqtSignal(str)

    def __init__(self, placeholder: str = "Поиск…", parent: QWidget | None = None):
        super().__init__(parent)
        self.setFixedHeight(36)
        self.setMinimumWidth(200)
        self._focus = False
        self.edit = QLineEdit(self)
        self.edit.setPlaceholderText(placeholder)
        self.edit.setStyleSheet(
            "QLineEdit { background: transparent; border: none; padding-left: 34px;"
            f" color: {TEXT}; font-size: 12px; }}"
            "QLineEdit:focus { border: none; background: transparent; }"
        )
        self.edit.textChanged.connect(self.textChanged)
        self.edit.installEventFilter(self)

    def eventFilter(self, obj, event) -> bool:  # noqa: N802
        if obj is self.edit:
            if event.type() == QEvent.FocusIn:
                self._focus = True
                self.update()
            elif event.type() == QEvent.FocusOut:
                self._focus = False
                self.update()
        return super().eventFilter(obj, event)

    def resizeEvent(self, event) -> None:  # noqa: N802
        self.edit.setGeometry(0, 0, self.width(), self.height())
        super().resizeEvent(event)

    def text(self) -> str:
        return self.edit.text()

    def clear(self) -> None:
        self.edit.clear()

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        p.setPen(QPen(THEME.accent_a(150) if self._focus else QColor(255, 255, 255, 24), 1))
        p.setBrush(QColor(255, 255, 255, 14 if self._focus else 9))
        p.drawRoundedRect(rect, 9, 9)
        icons.paint_icon(p, "search", QRectF(11, rect.center().y() - 8, 16, 16), QColor(MUTED), 1.6)
        p.end()


# ---------------------------------------------------------------------------
# статусы, аватары, прогресс
# ---------------------------------------------------------------------------
class StatusDot(QWidget):
    def __init__(self, color: str = GREEN, size: int = 8, parent: QWidget | None = None):
        super().__init__(parent)
        self._color = QColor(color)
        self.setFixedSize(size + 8, size + 8)

    def set_color(self, color: str | QColor) -> None:
        self._color = QColor(color)
        self.update()

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        c = QPointF(self.width() / 2, self.height() / 2)
        glow = QColor(self._color)
        glow.setAlpha(55)
        p.setPen(Qt.NoPen)
        p.setBrush(glow)
        p.drawEllipse(c, 6, 6)
        p.setBrush(self._color)
        p.drawEllipse(c, 3.2, 3.2)
        p.end()


class PingBars(QWidget):
    def __init__(self, latency: int | None = None, parent: QWidget | None = None):
        super().__init__(parent)
        self.latency = latency
        self.setFixedSize(26, 20)

    def set_latency(self, latency: int | None) -> None:
        self.latency = latency
        self.update()

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        if self.latency is None:
            level, color = 0, QColor(DIM)
        elif self.latency < 80:
            level, color = 4, QColor(GREEN)
        elif self.latency < 160:
            level, color = 3, QColor(YELLOW)
        elif self.latency < 320:
            level, color = 2, QColor("#FFA95C")
        else:
            level, color = 1, QColor(RED)
        for i in range(4):
            h = 4 + i * 4
            x = 2 + i * 6
            bar = QRectF(x, 18 - h, 4, h)
            p.setPen(Qt.NoPen)
            p.setBrush(color if i < level else QColor(255, 255, 255, 22))
            p.drawRoundedRect(bar, 1.5, 1.5)
        p.end()


class Avatar(QWidget):
    def __init__(self, name: str = "G", size: int = 38, parent: QWidget | None = None, ring: bool = True):
        super().__init__(parent)
        self._name = name or "?"
        self._size = size
        self._ring = ring
        self.online: bool | None = None
        self.setFixedSize(size, size)

    def set_name(self, name: str) -> None:
        self._name = name or "?"
        self.update()

    def _gradient(self) -> tuple[QColor, QColor]:
        digest = hashlib.md5(self._name.lower().encode("utf-8")).digest()
        hue = digest[0] / 255.0
        c1 = QColor.fromHsvF(hue, 0.55, 0.85)
        c2 = QColor.fromHsvF((hue + 0.09) % 1.0, 0.72, 0.62)
        return c1, c2

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect()).adjusted(1.5, 1.5, -1.5, -1.5)
        c1, c2 = self._gradient()
        g = QLinearGradient(rect.topLeft(), rect.bottomRight())
        g.setColorAt(0, c1)
        g.setColorAt(1, c2)
        p.setPen(QPen(THEME.accent_a(120) if self._ring else Qt.NoPen, 1.4))
        p.setBrush(g)
        p.drawEllipse(rect)
        p.setPen(QColor(20, 15, 6, 210))
        p.setFont(heading(max(10, int(self._size * 0.36))))
        letter = self._name[:1].upper()
        p.drawText(rect, Qt.AlignCenter, letter)
        if self.online is not None:
            dot = QRectF(rect.right() - 11, rect.bottom() - 11, 10, 10)
            p.setPen(QPen(QColor(16, 13, 9), 2))
            p.setBrush(QColor(GREEN) if self.online else QColor(DIM))
            p.drawEllipse(dot)
        p.end()


class ProgressBar(QWidget):
    def __init__(self, parent: QWidget | None = None, height: int = 8):
        super().__init__(parent)
        self._value = 0.0
        self._shown = 0.0
        self._anim: QVariantAnimation | None = None
        self.setFixedHeight(height)

    def value(self) -> float:
        return self._value

    def set_value(self, value: float, animate: bool = True) -> None:
        value = max(0.0, min(1.0, value))
        self._value = value
        if self._anim is not None:
            self._anim.stop()
            self._anim.deleteLater()
            self._anim = None
        if not animate or not THEME.animations:
            self._shown = value
            self.update()
            return
        anim = QVariantAnimation(self)
        self._anim = anim
        anim.setDuration(220)
        anim.setStartValue(self._shown)
        anim.setEndValue(value)
        anim.setEasingCurve(QEasingCurve.OutCubic)
        anim.valueChanged.connect(lambda v: (setattr(self, "_shown", float(v)), self.update()))
        anim.finished.connect(lambda: setattr(self, "_anim", None))
        anim.start()

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect())
        track = QRectF(0, rect.center().y() - 3, rect.width(), 6)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(255, 255, 255, 20))
        p.drawRoundedRect(track, 3, 3)
        if self._shown > 0:
            fill = QRectF(0, track.top(), max(6.0, track.width() * self._shown), track.height())
            g = QLinearGradient(fill.topLeft(), fill.topRight())
            g.setColorAt(0, THEME.accent2)
            g.setColorAt(0.55, THEME.accent)
            g.setColorAt(1, THEME.accent.lighter(115))
            p.setBrush(g)
            p.drawRoundedRect(fill, 3, 3)
            p.setBrush(QColor(255, 255, 255, 120))
            p.drawEllipse(QPointF(fill.right() - 3, fill.center().y()), 2.0, 2.0)
        p.end()


# ---------------------------------------------------------------------------
# прокрутка
# ---------------------------------------------------------------------------
class PageScroll(QScrollArea):
    def __init__(self, parent: QWidget | None = None, margins: tuple[int, int, int, int] = (0, 0, 8, 0)):
        super().__init__(parent)
        self.setWidgetResizable(True)
        self.setFrameShape(QFrame.NoFrame)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.setStyleSheet("background: transparent; border: none;")
        self.content = QWidget()
        self.content.setStyleSheet("background: transparent;")
        self.body = QVBoxLayout(self.content)
        self.body.setContentsMargins(*margins)
        self.body.setSpacing(16)
        self.setWidget(self.content)
        self.viewport().setStyleSheet("background: transparent;")

    def add(self, widget: QWidget, stretch: int = 0) -> None:
        self.body.addWidget(widget, stretch)

    def add_layout(self, layout, stretch: int = 0) -> None:
        self.body.addLayout(layout, stretch)

    def scroll_to_top(self) -> None:
        self.verticalScrollBar().setValue(0)


# ---------------------------------------------------------------------------
# строка настройки
# ---------------------------------------------------------------------------
class SettingRow(QWidget):
    def __init__(
        self,
        title: str,
        subtitle: str = "",
        control: QWidget | None = None,
        parent: QWidget | None = None,
    ):
        super().__init__(parent)
        self._hover = 0.0
        self.setAttribute(Qt.WA_StyledBackground, False)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(14, 11, 14, 11)
        lay.setSpacing(14)
        text = QVBoxLayout()
        text.setSpacing(2)
        text.addWidget(label(title, 13, TEXT, bold=True))
        if subtitle:
            text.addWidget(muted(subtitle, 11))
        lay.addLayout(text, 1)
        if control is not None:
            control.setSizePolicy(QSizePolicy.Fixed, QSizePolicy.Fixed)
            lay.addWidget(control, 0, Qt.AlignVCenter)
        self.setMinimumHeight(58)

    def enterEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 1.0, 120)
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        _animate(self, "_hover", self._hover, 0.0, 160)
        super().leaveEvent(event)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        if self._hover > 0.01:
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(255, 255, 255, int(7 * self._hover)))
            p.drawRoundedRect(QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5), 10, 10)
        p.end()


# ---------------------------------------------------------------------------
# тосты
# ---------------------------------------------------------------------------
class Toast(QWidget):
    def __init__(self, parent: QWidget, text: str, kind: str = "info", duration: int = 3200):
        super().__init__(parent)
        self.setAttribute(Qt.WA_TransparentForMouseEvents)
        self.kind = kind
        self._text = text
        self.setFixedHeight(46)
        fm = QFontMetrics(font(12, QFont.DemiBold))
        self.setFixedWidth(min(420, max(220, fm.horizontalAdvance(text) + 76)))
        self._opacity = 0.0
        self._timer = QTimer(self)
        self._timer.setSingleShot(True)
        self._timer.timeout.connect(self._hide)
        self._timer.start(duration)
        self._fade(1.0)
        self.show()
        self.raise_()
        self._reposition()

    def _reposition(self) -> None:
        parent = self.parentWidget()
        if parent:
            self.move(parent.width() - self.width() - 28, parent.height() - self.height() - 26)

    def _fade(self, target: float) -> None:
        anim = QVariantAnimation(self)
        anim.setDuration(180)
        anim.setStartValue(self._opacity)
        anim.setEndValue(target)
        anim.valueChanged.connect(lambda v: (setattr(self, "_opacity", float(v)), self.update()))
        anim.start(QVariantAnimation.DeleteWhenStopped)

    def _hide(self) -> None:
        self._fade(0.0)
        QTimer.singleShot(200, self.deleteLater)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setOpacity(self._opacity)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        paint_glass(p, rect, 12, top=QColor(30, 25, 17, 246), bottom=QColor(18, 15, 10, 246),
                    border=THEME.accent_a(80), shadow=True)
        color = {"ok": GREEN, "error": RED, "warn": YELLOW}.get(self.kind, THEME.accent.name())
        icon = {"ok": "check", "error": "warning", "warn": "warning"}.get(self.kind, "info")
        icons.paint_icon(p, icon, QRectF(15, rect.center().y() - 9, 18, 18), QColor(color), 1.8)
        p.setPen(QColor(TEXT))
        p.setFont(font(12, QFont.DemiBold))
        p.drawText(QRectF(43, 0, rect.width() - 54, rect.height()), Qt.AlignVCenter | Qt.AlignLeft, self._text)
        p.end()


# ---------------------------------------------------------------------------
# диалоги
# ---------------------------------------------------------------------------
class Dialog(QDialog):
    def __init__(self, parent: QWidget | None, title: str, width: int = 460, height: int | None = None):
        super().__init__(parent, Qt.FramelessWindowHint | Qt.Dialog)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setModal(True)
        self._drag = None
        outer = QVBoxLayout(self)
        outer.setContentsMargins(22, 22, 22, 22)
        self.card = GlassCard(radius=18, padding=0)
        self.card.setFixedWidth(width)
        if height:
            self.card.setFixedHeight(height)
        apply_shadow(self.card, 40, 170, 12)
        outer.addWidget(self.card)
        body = QVBoxLayout()
        body.setContentsMargins(22, 18, 22, 20)
        body.setSpacing(14)
        self.card.body.addLayout(body)
        head = QHBoxLayout()
        head.setSpacing(8)
        head.addWidget(h2(title, 17), 1)
        close = IconButton("close", size=30, icon_size=15, tooltip="Закрыть")
        close.clicked.connect(self.reject)
        head.addWidget(close)
        body.addLayout(head)
        self.content = QVBoxLayout()
        self.content.setSpacing(12)
        body.addLayout(self.content, 1)
        self.footer = QHBoxLayout()
        self.footer.setSpacing(8)
        self.footer.addStretch(1)
        body.addLayout(self.footer)

    def add(self, widget: QWidget) -> None:
        self.content.addWidget(widget)

    def add_layout(self, layout) -> None:
        self.content.addLayout(layout)

    def add_button(self, text: str, kind: str = "primary", icon: str | None = None) -> GoldButton:
        button = GoldButton(text, kind, icon)
        self.footer.addWidget(button)
        return button

    def mousePressEvent(self, event) -> None:  # noqa: N802
        if event.button() == Qt.LeftButton:
            self._drag = event.globalPos() - self.frameGeometry().topLeft()
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        if self._drag is not None and event.buttons() & Qt.LeftButton:
            self.move(event.globalPos() - self._drag)
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        self._drag = None
        super().mouseReleaseEvent(event)

    def show_centered(self) -> int:
        if self.parentWidget():
            geo = self.parentWidget().geometry()
            self.adjustSize()
            self.move(self.parentWidget().mapToGlobal(geo.center()) - self.rect().center())
        return self.exec_()


class ConfirmDialog(Dialog):
    def __init__(self, parent: QWidget | None, title: str, text: str, danger: bool = False,
                 ok_text: str = "Подтвердить"):
        super().__init__(parent, title, 430)
        self.add(muted(text, 12))
        cancel = self.add_button("Отмена", "ghost")
        cancel.clicked.connect(self.reject)
        ok = self.add_button(ok_text, "danger" if danger else "primary")
        ok.clicked.connect(self.accept)


def toast(parent: QWidget, text: str, kind: str = "info") -> None:
    Toast(parent.window(), text, kind)
