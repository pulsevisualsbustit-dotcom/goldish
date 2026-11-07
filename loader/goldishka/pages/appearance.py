"""Страница «Внешний вид»: акцентная тема и фон лаунчера."""

from __future__ import annotations

from typing import TYPE_CHECKING

from PyQt5.QtCore import QRectF, Qt
from PyQt5.QtGui import QColor, QLinearGradient, QPainter, QPen
from PyQt5.QtWidgets import QHBoxLayout, QVBoxLayout, QWidget

from ..theme import ACCENTS, BACKGROUNDS, MUTED, TEXT, THEME, heading
from ..ui.assets import paint_cover, pixmap
from ..ui.components import ThemeCard
from ..ui.widgets import (
    Chip,
    GlassCard,
    SettingRow,
    ToggleSwitch,
    h2,
    label,
    muted,
    rounded,
)
from .base import BasePage

if TYPE_CHECKING:
    from ..app import MainWindow


class PreviewCard(QWidget):
    """Мини-превью лаунчера в выбранной теме."""

    def __init__(self, app: "MainWindow", parent: QWidget | None = None):
        super().__init__(parent)
        self.app = app
        self.setFixedHeight(206)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.TextAntialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = rounded(rect, 16)
        p.setClipPath(path)
        p.fillRect(rect, QColor(14, 12, 8))
        pm = pixmap(THEME.background_path.name)
        if not pm.isNull():
            paint_cover(p, rect, pm)
        overlay = QLinearGradient(rect.topLeft(), rect.topRight())
        overlay.setColorAt(0.0, QColor(11, 9, 6, 210))
        overlay.setColorAt(0.55, QColor(11, 9, 6, 120))
        overlay.setColorAt(1.0, QColor(11, 9, 6, 25))
        p.fillRect(rect, overlay)

        # боковая панель
        side = QRectF(rect.left() + 12, rect.top() + 12, 132, rect.height() - 24)
        p.setPen(QPen(QColor(255, 255, 255, 24), 1))
        p.setBrush(QColor(18, 15, 10, 200))
        p.drawRoundedRect(side, 10, 10)
        p.setPen(THEME.accent)
        p.setFont(heading(11))
        p.drawText(QRectF(side.left() + 12, side.top() + 10, 108, 16),
                   Qt.AlignLeft | Qt.AlignVCenter, "GOLDISH")
        for i in range(5):
            item = QRectF(side.left() + 8, side.top() + 36 + i * 24, side.width() - 16, 20)
            if i == 0:
                p.setPen(Qt.NoPen)
                p.setBrush(THEME.accent_a(200))
                p.drawRoundedRect(item, 6, 6)
                p.setPen(QColor("#211704"))
            else:
                p.setPen(QColor(MUTED))
            p.setFont(heading(8))
            p.drawText(item.adjusted(8, 0, 0, 0), Qt.AlignVCenter | Qt.AlignLeft,
                       ("Главная", "Играть", "Параметры", "Моды", "Настройки")[i])

        # контент
        content = QRectF(side.right() + 14, rect.top() + 12, rect.right() - side.right() - 26,
                         rect.height() - 24)
        p.setPen(QColor(TEXT))
        p.setFont(heading(17))
        p.drawText(QRectF(content.left(), content.top(), content.width(), 26),
                   Qt.AlignLeft | Qt.AlignVCenter, "Привет, Goldishka!")
        p.setPen(QColor(MUTED))
        p.setFont(heading(8))
        p.drawText(QRectF(content.left(), content.top() + 27, content.width(), 15),
                   Qt.AlignLeft | Qt.AlignVCenter, "Запускай Minecraft и играй с Goldish")

        hero = QRectF(content.left(), content.top() + 48, content.width(), 78)
        p.setPen(QPen(THEME.accent_a(50), 1))
        p.setBrush(QColor(20, 17, 11, 220))
        p.drawRoundedRect(hero, 10, 10)
        g = QLinearGradient(hero.topLeft(), hero.topRight())
        g.setColorAt(0, THEME.accent)
        g.setColorAt(1, THEME.accent2)
        button = QRectF(hero.left() + 14, hero.bottom() - 34, 92, 24)
        p.setPen(Qt.NoPen)
        p.setBrush(g)
        p.drawRoundedRect(button, 7, 7)
        p.setPen(QColor("#211704"))
        p.setFont(heading(9))
        p.drawText(button, Qt.AlignCenter, "Играть")

        card_w = (content.width() - 16) / 3
        for i in range(3):
            card = QRectF(content.left() + i * (card_w + 8), hero.bottom() + 8, card_w, 30)
            p.setPen(QPen(QColor(255, 255, 255, 20), 1))
            p.setBrush(QColor(255, 255, 255, 8))
            p.drawRoundedRect(card, 8, 8)

        p.setClipping(False)
        p.setPen(QPen(THEME.accent_a(70), 1))
        p.setBrush(Qt.NoBrush)
        p.drawPath(path)
        p.end()


class AppearancePage(BasePage):
    def __init__(self, app: "MainWindow"):
        super().__init__(app, "Внешний вид", "Акцент, фон и анимации лаунчера", "appearance")
        self.app = app
        self.accent_cards: dict[str, ThemeCard] = {}
        self.bg_cards: dict[str, ThemeCard] = {}

        content = QHBoxLayout()
        content.setSpacing(16)
        left = QVBoxLayout()
        left.setSpacing(14)
        self.preview = PreviewCard(app)
        left.addWidget(self.preview)

        accents_head = QHBoxLayout()
        accents_head.addWidget(h2("Акцентная тема", 16))
        accents_head.addStretch(1)
        self.accent_chip = Chip(THEME.accent_name, THEME.accent, size=9)
        accents_head.addWidget(self.accent_chip)
        left.addLayout(accents_head)
        accents = QHBoxLayout()
        accents.setSpacing(10)
        for key, data in ACCENTS.items():
            card = ThemeCard(
                key,
                data["name"],
                BACKGROUNDS[key]["preview"],
                QColor(data["a"]),
                selected=key == THEME.key,
            )
            card.clicked.connect(self._pick_accent)
            accents.addWidget(card, 1)
            self.accent_cards[key] = card
        left.addLayout(accents)

        bg_head = QHBoxLayout()
        bg_head.addWidget(h2("Фон лаунчера", 16))
        bg_head.addStretch(1)
        self.bg_chip = Chip(BACKGROUNDS[THEME.bg_key]["name"], THEME.accent, size=9)
        bg_head.addWidget(self.bg_chip)
        left.addLayout(bg_head)
        backgrounds = QHBoxLayout()
        backgrounds.setSpacing(10)
        for key, data in BACKGROUNDS.items():
            card = ThemeCard(
                key,
                data["name"],
                data["preview"],
                QColor(ACCENTS[key]["a"]),
                selected=key == THEME.bg_key,
            )
            card.clicked.connect(self._pick_background)
            backgrounds.addWidget(card, 1)
            self.bg_cards[key] = card
        left.addLayout(backgrounds)
        left.addStretch(1)
        content.addLayout(left, 1)

        right = QVBoxLayout()
        right.setSpacing(12)
        live = GlassCard(radius=16)
        lb = QVBoxLayout(live)
        lb.setContentsMargins(16, 14, 16, 14)
        lb.setSpacing(7)
        lb.addWidget(h2("Как это работает", 14))
        lb.addWidget(muted(
            "Тема меняет акцент кнопок, ползунков и подсветки. Фон — художественная "
            "картинка за стеклянными панелями. Изменения применяются сразу.", 10))
        right.addWidget(live)

        behavior = GlassCard(radius=16)
        bb = QVBoxLayout(behavior)
        bb.setContentsMargins(8, 10, 8, 10)
        bb.setSpacing(0)
        bb.addWidget(self._section("Эффекты"))
        self.anim_switch = ToggleSwitch(app.config.animations)
        self.anim_switch.toggled.connect(self._toggle_animations)
        bb.addWidget(SettingRow("Анимации", "Плавные переходы", self.anim_switch))
        self.particles_switch = ToggleSwitch(app.config.particles)
        self.particles_switch.toggled.connect(self._toggle_particles)
        bb.addWidget(SettingRow("Частицы", "Искры на фоне", self.particles_switch))
        right.addWidget(behavior)

        presets = GlassCard(radius=16)
        pb = QVBoxLayout(presets)
        pb.setContentsMargins(16, 14, 16, 14)
        pb.setSpacing(7)
        pb.addWidget(h2("Пресеты", 14))
        pb.addWidget(muted("Быстрый выбор сочетания акцента и фона.", 10))
        from ..ui.widgets import GoldButton

        for name, accent, bg in (
            ("Золотой закат", "gold", "gold"),
            ("Синий час", "azure", "azure"),
            ("Аметист", "violet", "violet"),
            ("Изумруд", "emerald", "emerald"),
        ):
            button = GoldButton(name, "ghost", "sparkle", height=32)
            button.clicked.connect(lambda _=False, a=accent, b=bg: self._preset(a, b))
            pb.addWidget(button)
        right.addWidget(presets)
        right.addStretch(1)
        right_wrap = QWidget()
        right_wrap.setFixedWidth(290)
        right_wrap.setLayout(right)
        content.addWidget(right_wrap, 0)

        self.scroll.add_layout(content)
        self.scroll.body.addStretch(1)

    def _section(self, text: str) -> QWidget:
        holder = QWidget()
        lay = QHBoxLayout(holder)
        lay.setContentsMargins(14, 4, 14, 4)
        lay.addWidget(h2(text, 15))
        lay.addStretch(1)
        return holder

    def _pick_accent(self, key: str) -> None:
        self.app.apply_accent(key)
        for name, card in self.accent_cards.items():
            card.set_selected(name == key)
        self.accent_chip.setText(ACCENTS[key]["name"])
        self.accent_chip.set_color(QColor(ACCENTS[key]["a"]))
        self.preview.update()

    def _pick_background(self, key: str) -> None:
        self.app.apply_background(key)
        for name, card in self.bg_cards.items():
            card.set_selected(name == key)
        self.bg_chip.setText(BACKGROUNDS[key]["name"])
        self.preview.update()

    def _preset(self, accent: str, background: str) -> None:
        self._pick_accent(accent)
        self._pick_background(background)
        self.app.toast("Пресет применён", "ok")

    def _toggle_animations(self, checked: bool) -> None:
        self.app.config.animations = checked
        THEME.animations = checked
        self.app.save_config()

    def _toggle_particles(self, checked: bool) -> None:
        self.app.config.particles = checked
        self.app.save_config()

    def refresh(self) -> None:
        for key, card in self.accent_cards.items():
            card.set_selected(key == THEME.key)
        for key, card in self.bg_cards.items():
            card.set_selected(key == THEME.bg_key)
        self.accent_chip.setText(THEME.accent_name)
        self.accent_chip.set_color(THEME.accent)
        self.bg_chip.setText(BACKGROUNDS[THEME.bg_key]["name"])
        for switch, value in (
            (self.anim_switch, self.app.config.animations),
            (self.particles_switch, self.app.config.particles),
        ):
            switch.blockSignals(True)
            switch.setChecked(value)
            switch._pos = 1.0 if value else 0.0
            switch.blockSignals(False)
            switch.update()
        self.preview.update()

    def on_show(self) -> None:
        self.refresh()
