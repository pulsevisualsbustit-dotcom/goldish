"""Кэш картинок и вспомогательная отрисовка."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from PyQt5.QtCore import QRectF, Qt
from PyQt5.QtGui import QPainter, QPixmap

from ..theme import ASSETS


@lru_cache(maxsize=64)
def pixmap(name: str) -> QPixmap:
    path = ASSETS / name if not Path(name).is_absolute() else Path(name)
    pm = QPixmap(str(path))
    return pm


def scaled_cover(pm: QPixmap, width: int, height: int) -> QPixmap:
    if pm.isNull():
        return pm
    return pm.scaled(width, height, Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)


def paint_cover(painter: QPainter, rect: QRectF, pm: QPixmap, source_rect: QRectF | None = None) -> None:
    """Рисует картинку как object-fit: cover, при необходимости из части источника."""
    if pm.isNull() or rect.isEmpty():
        return
    if source_rect is None:
        source_rect = QRectF(0, 0, pm.width(), pm.height())
    src_ratio = source_rect.width() / source_rect.height()
    dst_ratio = rect.width() / rect.height()
    if src_ratio > dst_ratio:
        new_w = source_rect.height() * dst_ratio
        sx = source_rect.x() + (source_rect.width() - new_w) / 2
        source_rect = QRectF(sx, source_rect.y(), new_w, source_rect.height())
    else:
        new_h = source_rect.width() / dst_ratio
        sy = source_rect.y() + (source_rect.height() - new_h) / 2
        source_rect = QRectF(source_rect.x(), sy, source_rect.width(), new_h)
    painter.drawPixmap(rect, pm, source_rect)
