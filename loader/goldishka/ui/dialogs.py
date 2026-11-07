"""Диалоги: сервер, профиль, новости."""

from __future__ import annotations

from typing import TYPE_CHECKING

from PyQt5.QtCore import QRectF, Qt
from PyQt5.QtGui import QColor, QPainter, QPixmap
from PyQt5.QtWidgets import (
    QComboBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QVBoxLayout,
    QWidget,
)

from ..core.launcher import offline_uuid
from ..core.news import NewsItem
from ..core.servers import ServerEntry
from ..theme import ASSETS, MUTED, TEXT, THEME
from .assets import paint_cover, pixmap
from .components import Avatar
from .widgets import (
    Chip,
    Dialog,
    GlassCard,
    GoldButton,
    GoldCombo,
    IconButton,
    PageScroll,
    h2,
    label,
    muted,
)

if TYPE_CHECKING:
    from ..app import MainWindow


class CoverImage(QWidget):
    def __init__(self, image: str, height: int = 170, radius: int = 12, parent: QWidget | None = None):
        super().__init__(parent)
        self.image = image
        self.radius = radius
        self.setFixedHeight(height)

    def paintEvent(self, event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        rect = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = QRectF(rect)
        from .widgets import rounded

        clip = rounded(path, self.radius)
        p.setClipPath(clip)
        p.fillRect(rect, QColor(18, 15, 10))
        paint_cover(p, rect, pixmap(self.image))
        p.setClipping(False)
        p.end()


class NewsListDialog(Dialog):
    def __init__(self, app: "MainWindow", items: list[NewsItem]):
        title = "Новости" if len(items) != 1 else items[0].title
        super().__init__(app, title, 620, 640)
        scroll = PageScroll(margins=(0, 0, 4, 0))
        scroll.setFixedHeight(500)
        for item in items:
            card = QVBoxLayout()
            card.setSpacing(8)
            card.addWidget(CoverImage(item.image, 160))
            meta = QHBoxLayout()
            meta.addWidget(Chip(item.tag, THEME.accent, size=9))
            meta.addStretch(1)
            meta.addWidget(muted(item.date, 10))
            card.addLayout(meta)
            card.addWidget(h2(item.title, 17))
            text = muted(item.text, 12)
            text.setWordWrap(True)
            card.addWidget(text)
            holder = QWidget()
            holder.setLayout(card)
            scroll.body.addWidget(holder)
        scroll.body.addStretch(1)
        self.add(scroll)
        close = self.add_button("Закрыть", "secondary")
        close.clicked.connect(self.accept)


class ServerDialog(Dialog):
    def __init__(self, app: "MainWindow", entry: ServerEntry | None = None, index: int | None = None):
        super().__init__(app, "Изменить сервер" if entry else "Добавить сервер", 500)
        self.app = app
        self.entry = entry
        self.index = index
        self.name = QLineEdit(entry.name if entry else "")
        self.name.setPlaceholderText("Название, например HolyWorld")
        self.address = QLineEdit(entry.address if entry else "")
        self.address.setPlaceholderText("mc.example.ru или mc.example.ru:25565")
        self.description = QLineEdit(entry.description if entry else "")
        self.description.setPlaceholderText("Короткое описание")
        self.tag = GoldCombo()
        self.tag.setEditable(True)
        self.tag.addItems(["Выживание", "Анархия", "Мини-игры", "PvP", "Другое"])
        if entry and entry.tag:
            self.tag.setCurrentText(entry.tag)
        for title, field in (
            ("Название", self.name),
            ("Адрес", self.address),
            ("Описание", self.description),
        ):
            self.add(label(title, 11, MUTED))
            self.add(field)
        self.add(label("Режим", 11, MUTED))
        self.add(self.tag)
        cancel = self.add_button("Отмена", "ghost")
        cancel.clicked.connect(self.reject)
        save = self.add_button("Сохранить", "primary")
        save.clicked.connect(self._save)

    def _save(self) -> None:
        name = self.name.text().strip()
        address = self.address.text().strip()
        if not name or not address:
            self.name.setFocus()
            return
        entry = ServerEntry(
            name=name,
            address=address,
            version=self.app.config.mc_version,
            description=self.description.text().strip(),
            tag=self.tag.currentText().strip() or "Другое",
            color=self.entry.color if self.entry else "#F6C453",
            builtin=False if self.entry is None else self.entry.builtin,
        )
        if self.index is None:
            self.app.server_store.add(entry)
        else:
            self.app.server_store.update(self.index, entry)
        self.app.on_servers_changed()
        self.accept()


class ProfileDialog(Dialog):
    def __init__(self, app: "MainWindow"):
        super().__init__(app, "Профиль", 470)
        self.app = app
        data = app.client_data()
        head = QHBoxLayout()
        head.setSpacing(14)
        head.addWidget(Avatar(app.config.nickname, 64, ring=True), 0, Qt.AlignTop)
        info = QVBoxLayout()
        info.setSpacing(4)
        self.nickname = QLineEdit(app.config.nickname)
        self.nickname.setPlaceholderText("Ник в игре")
        info.addWidget(self.nickname)
        info.addWidget(muted("Оффлайн-аккаунт: UUID считается локально", 10))
        head.addLayout(info, 1)
        self.add_layout(head)

        stats = QHBoxLayout()
        stats.setSpacing(8)
        for title, value in (
            ("Друзья", str(len(data.friends))),
            ("Альты", str(len(data.alts))),
            ("Модули", f"{data.modules_enabled} / {data.modules_total}"),
        ):
            card = GlassCard(radius=10)
            card.setFixedHeight(62)
            box = QVBoxLayout(card)
            box.setContentsMargins(12, 8, 12, 8)
            box.setSpacing(0)
            box.addWidget(muted(title, 10))
            box.addWidget(label(value, 17, TEXT, bold=True))
            stats.addWidget(card, 1)
        self.add_layout(stats)

        uuid_value = offline_uuid(app.config.nickname or "Player")
        uuid_row = QHBoxLayout()
        uuid_row.addWidget(muted("UUID", 10))
        uuid_row.addStretch(1)
        uuid_label = label(uuid_value, 10, MUTED)
        uuid_label.setTextInteractionFlags(Qt.TextSelectableByMouse)
        uuid_row.addWidget(uuid_label)
        self.add_layout(uuid_row)
        self.add(muted("Ник применяется при следующем запуске клиента. Аккаунты и друзья "
                       "хранятся в конфиге Goldish и в лаунчер не передаются.", 10))

        play = self.add_button("Играть", "primary", "play")
        play.clicked.connect(self._play)
        cancel = self.add_button("Закрыть", "ghost")
        cancel.clicked.connect(self.reject)
        save = self.add_button("Сохранить", "secondary", "save")
        save.clicked.connect(self._save)

    def _save(self) -> None:
        nick = self.nickname.text().strip()
        if len(nick) < 3:
            self.nickname.setFocus()
            return
        self.app.config.nickname = nick
        self.app.save_config()
        self.app.on_profile_changed()
        self.accept()

    def _play(self) -> None:
        self._save()
        self.app.launch()


class AboutDialog(Dialog):
    def __init__(self, app: "MainWindow"):
        super().__init__(app, "О клиенте", 460)
        row = QHBoxLayout()
        logo = QLabel()
        pm = QPixmap(str(ASSETS / "logo_big.png"))
        logo.setPixmap(pm.scaled(84, 84, Qt.KeepAspectRatio, Qt.SmoothTransformation))
        logo.setFixedSize(84, 84)
        row.addWidget(logo)
        info = QVBoxLayout()
        info.setSpacing(2)
        info.addWidget(h2("Goldish Client", 20))
        version_line = muted("Minecraft 1.21.11 · Fabric 0.19.5", 11)
        version_line.setWordWrap(False)
        info.addWidget(version_line)
        info.addWidget(Chip("loader 1.0.0", THEME.accent, size=9))
        row.addLayout(info, 1)
        self.add_layout(row)
        self.add(muted("Лёгкий «легитный» клиент: AimAssist, TriggerBot, ESP, NameTags, "
                       "HUD с редактором, ClickGUI и Alt Manager. Лаунчер сам скачивает "
                       "Minecraft, ресурсы и Fabric API, ставит Goldish и запускает игру.", 11))
        close = self.add_button("Закрыть", "secondary")
        close.clicked.connect(self.accept)
