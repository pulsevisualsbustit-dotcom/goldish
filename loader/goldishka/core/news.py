"""Новости лаунчера (локальные карточки)."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class NewsItem:
    title: str
    text: str
    date: str
    tag: str
    image: str


NEWS: list[NewsItem] = [
    NewsItem(
        "Клиент под 1.21.11",
        "Goldish собран под Minecraft 1.21.11 и Fabric Loader 0.19.5. Лаунчер сам скачает "
        "игру, ресурсы и Fabric API — от вас нужен только ник.",
        "31.10.2026",
        "Обновление",
        "news_2.jpg",
    ),
    NewsItem(
        "Новый дизайн лаунчера",
        "Стеклянные панели, золотой акцент, живой список серверов и управление модами "
        "прямо из лаунчера.",
        "24.10.2026",
        "Лаунчер",
        "news_1.jpg",
    ),
    NewsItem(
        "AutoSwap и MaceHelper",
        "В клиенте появились AutoSwap для быстрой смены предметов и MaceHelper с подсказками "
        "по булаве. Настройки — в ClickGUI.",
        "12.10.2026",
        "Моды",
        "news_3.jpg",
    ),
]
