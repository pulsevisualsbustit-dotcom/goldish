"""Палитра, шрифты и глобальный стиль лаунчера."""

from __future__ import annotations

import sys
from pathlib import Path

from PyQt5.QtGui import QColor, QFont

# В onefile-сборке PyInstaller картинки лежат в _MEIPASS, иначе — рядом с пакетом.
ASSETS = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent)) / "assets"

# --- базовые цвета ---------------------------------------------------------
TEXT = "#F6F0E3"
TEXT_DIM = "#CFC5B2"
MUTED = "#A79C89"
DIM = "#736A5B"
GREEN = "#6FE3A1"
YELLOW = "#F4C95B"
RED = "#FF7A6B"
BLUE = "#6FB8FF"

CARD_TOP = QColor(27, 23, 16, 232)
CARD_BOTTOM = QColor(15, 13, 9, 238)
CARD_HOVER = QColor(255, 255, 255, 7)
BORDER = QColor(255, 220, 150, 28)
BORDER_STRONG = QColor(255, 220, 150, 58)

ACCENTS: dict[str, dict[str, str]] = {
    "gold": {"name": "Золотая", "a": "#FFD56A", "b": "#EE9B25", "glow": "#FFC24B"},
    "azure": {"name": "Лазурная", "a": "#7BD0FF", "b": "#2E7DFF", "glow": "#5AB8FF"},
    "violet": {"name": "Аметист", "a": "#C79BFF", "b": "#7B5CFF", "glow": "#A97BFF"},
    "emerald": {"name": "Изумруд", "a": "#7BE8AC", "b": "#1FAE6B", "glow": "#4FD58C"},
}

BACKGROUNDS: dict[str, dict[str, str]] = {
    "gold": {"name": "Золотой закат", "file": "bg_gold.jpg", "preview": "theme_gold.jpg"},
    "azure": {"name": "Синий час", "file": "bg_azure.jpg", "preview": "theme_azure.jpg"},
    "violet": {"name": "Аметист", "file": "bg_violet.jpg", "preview": "theme_violet.jpg"},
    "emerald": {"name": "Изумруд", "file": "bg_emerald.jpg", "preview": "theme_emerald.jpg"},
}


def qc(value: str | QColor, alpha: int | None = None) -> QColor:
    color = QColor(value) if isinstance(value, str) else QColor(value)
    if alpha is not None:
        color.setAlpha(alpha)
    return color


class Theme:
    """Текущая тема. Виджеты читают цвета в момент отрисовки."""

    def __init__(self) -> None:
        self.key = "gold"
        self.bg_key = "gold"
        self.animations = True

    # -------------------------------------------------------------- акцент
    @property
    def accent(self) -> QColor:
        return QColor(ACCENTS[self.key]["a"])

    @property
    def accent2(self) -> QColor:
        return QColor(ACCENTS[self.key]["b"])

    @property
    def glow(self) -> QColor:
        return QColor(ACCENTS[self.key]["glow"])

    def accent_a(self, alpha: int) -> QColor:
        color = self.accent
        color.setAlpha(alpha)
        return color

    @property
    def accent_name(self) -> str:
        return ACCENTS[self.key]["name"]

    # ------------------------------------------------------------ фоновый арт
    @property
    def background_path(self) -> Path:
        return ASSETS / BACKGROUNDS[self.bg_key]["file"]

    def set_accent(self, key: str) -> None:
        if key in ACCENTS:
            self.key = key

    def set_background(self, key: str) -> None:
        if key in BACKGROUNDS:
            self.bg_key = key


THEME = Theme()


# --- шрифты ----------------------------------------------------------------
def font(size: int = 13, weight: int = QFont.Normal, heading: bool = False) -> QFont:
    family = "Bahnschrift" if heading else "Segoe UI"
    f = QFont(family, size, weight)
    if heading:
        f.setLetterSpacing(QFont.AbsoluteSpacing, 0.2)
    return f


def heading(size: int = 20, weight: int = QFont.DemiBold) -> QFont:
    return font(size, weight, heading=True)


# --- QSS -------------------------------------------------------------------
def app_qss() -> str:
    a = THEME.accent.name()
    b = THEME.accent2.name()
    return f"""
    QWidget {{
        color: {TEXT};
        font-family: 'Segoe UI';
        font-size: 13px;
        selection-background-color: {a};
        selection-color: #1A1305;
    }}
    QToolTip {{
        background: #1A160F;
        color: {TEXT};
        border: 1px solid rgba(255, 220, 150, 40);
        border-radius: 7px;
        padding: 6px 9px;
    }}
    QLineEdit, QSpinBox, QDoubleSpinBox {{
        background: rgba(255, 255, 255, 18);
        border: 1px solid rgba(255, 255, 255, 38);
        border-radius: 9px;
        padding: 8px 12px;
        color: {TEXT};
    }}
    QLineEdit:focus, QSpinBox:focus {{
        border: 1px solid rgba({THEME.accent.red()}, {THEME.accent.green()}, {THEME.accent.blue()}, 185);
        background: rgba(255, 255, 255, 24);
    }}
    QLineEdit:disabled {{ color: {DIM}; }}
    QComboBox {{
        background: rgba(255, 255, 255, 16);
        border: 1px solid rgba(255, 255, 255, 34);
        border-radius: 9px;
        padding: 7px 12px;
        color: {TEXT};
    }}
    QComboBox:hover {{ border: 1px solid rgba(255, 255, 255, 52); }}
    QComboBox:focus {{ border: 1px solid rgba({THEME.accent.red()}, {THEME.accent.green()}, {THEME.accent.blue()}, 170); }}
    QComboBox::drop-down {{ border: none; width: 26px; }}
    QComboBox::down-arrow {{ image: none; width: 0px; height: 0px; }}
    QComboBox QAbstractItemView {{
        background: #17130D;
        border: 1px solid rgba(255, 220, 150, 40);
        border-radius: 8px;
        padding: 5px;
        outline: none;
        selection-background-color: rgba({THEME.accent.red()}, {THEME.accent.green()}, {THEME.accent.blue()}, 40);
        selection-color: {TEXT};
    }}
    QScrollArea {{ background: transparent; border: none; }}
    QScrollBar:vertical {{
        background: transparent;
        width: 9px;
        margin: 2px 1px;
    }}
    QScrollBar::handle:vertical {{
        background: rgba({THEME.accent.red()}, {THEME.accent.green()}, {THEME.accent.blue()}, 70);
        min-height: 34px;
        border-radius: 4px;
    }}
    QScrollBar::handle:vertical:hover {{
        background: rgba({THEME.accent.red()}, {THEME.accent.green()}, {THEME.accent.blue()}, 130);
    }}
    QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{ height: 0px; }}
    QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical {{ background: transparent; }}
    QScrollBar:horizontal {{ height: 0px; }}
    QMenu {{
        background: #17130D;
        border: 1px solid rgba(255, 220, 150, 40);
        border-radius: 8px;
        padding: 6px;
    }}
    QMenu::item {{ padding: 7px 20px 7px 14px; border-radius: 6px; color: {TEXT}; }}
    QMenu::item:selected {{ background: rgba({THEME.accent.red()}, {THEME.accent.green()}, {THEME.accent.blue()}, 38); }}
    QMenu::separator {{ height: 1px; background: rgba(255,255,255,18); margin: 5px 8px; }}
    QMessageBox {{ background: #14110B; }}
    """
