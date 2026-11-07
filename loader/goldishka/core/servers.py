"""Список серверов и настоящий Minecraft server-list ping."""

from __future__ import annotations

import json
import re
import socket
import struct
import time
from dataclasses import asdict, dataclass, field
from typing import Any

from .config import LoaderConfig

COLOR_RE = re.compile(r"§.")


@dataclass
class ServerEntry:
    name: str
    address: str
    version: str = "1.21.11"
    description: str = ""
    tag: str = "Выживание"
    color: str = "#F6C453"
    builtin: bool = True

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "ServerEntry":
        known = {f for f in cls.__dataclass_fields__}
        return cls(**{k: v for k, v in raw.items() if k in known})

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def host_port(self) -> tuple[str, int]:
        return split_address(self.address)


def split_address(address: str) -> tuple[str, int]:
    address = address.strip()
    if address.count(":") == 1:
        host, port = address.rsplit(":", 1)
        if port.isdigit():
            return host, int(port)
    return address, 25565


DEFAULT_SERVERS: list[ServerEntry] = [
    ServerEntry("HolyWorld", "mc.holyworld.ru", "1.21.11", "Выживание и мини-игры", "Выживание", "#F6C453"),
    ServerEntry("MineBlaze", "mc.mineblaze.net", "1.21.11", "Мини-игры и PvP", "Мини-игры", "#63B3FF"),
    ServerEntry("FunTime", "mc.funtime.su", "1.21.11", "Анархия и гриф", "Анархия", "#FF8A5C"),
    ServerEntry("RealyWorld", "mc.realyworld.net", "1.21.11", "Мини-игры для компании", "Мини-игры", "#7BD0FF"),
    ServerEntry("BlackHawk", "mc.blackhawk.ru", "1.21.11", "Анархия, гриф, кланы", "Анархия", "#C79BFF"),
    ServerEntry("AresMine", "mc.aresmine.ru", "1.21.11", "Выживание и экономика", "Выживание", "#7BE8AC"),
    ServerEntry("Phoenix", "mc.phoenixmc.ru", "1.21.11", "Анархия и PvP", "Анархия", "#FF7A6B"),
    ServerEntry("LoliLand", "mc.loliland.ru", "1.21.11", "Мини-игры и скайблок", "Мини-игры", "#FFA9D9"),
    ServerEntry("Hypixel", "mc.hypixel.net", "1.21.11", "Крупнейший мини-игровой сервер", "Мини-игры", "#F6C453"),
    ServerEntry("2B2T", "2b2t.org", "1.21.11", "Анархия без правил", "Анархия", "#B8B8B8"),
    ServerEntry("MST Network", "mc.mstnetwork.ru", "1.21.11", "Выживание с друзьями", "Выживание", "#63D6C6"),
]


@dataclass
class PingResult:
    online: bool = False
    players: int = 0
    max_players: int = 0
    latency: int | None = None
    version: str = ""
    motd: str = ""
    error: str = ""


def _varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def _read_varint(sock: socket.socket) -> int:
    value = 0
    shift = 0
    while True:
        chunk = sock.recv(1)
        if not chunk:
            raise ConnectionError("соединение закрыто")
        byte = chunk[0]
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value
        shift += 7
        if shift > 35:
            raise ConnectionError("слишком длинный varint")


def _recv_exact(sock: socket.socket, length: int) -> bytes:
    data = bytearray()
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("соединение закрыто")
        data.extend(chunk)
    return bytes(data)


def ping(address: str, timeout: float = 2.6) -> PingResult:
    host, port = split_address(address)
    started = time.perf_counter()
    try:
        with socket.create_connection((host, port), timeout=timeout) as sock:
            sock.settimeout(timeout)
            host_bytes = host.encode("utf-8")
            payload = (
                _varint(0)
                + _varint(769)
                + _varint(len(host_bytes)) + host_bytes
                + struct.pack(">H", port)
                + _varint(1)
            )
            sock.sendall(_varint(len(payload)) + payload)
            sock.sendall(_varint(1) + _varint(0))
            length = _read_varint(sock)
            packet = _recv_exact(sock, length)
            # packet id
            index = 0
            packet_id, index = _read_varint_buf(packet, index)
            if packet_id != 0:
                raise ConnectionError("неожиданный ответ")
            str_len, index = _read_varint_buf(packet, index)
            raw = packet[index:index + str_len].decode("utf-8", "replace")
            data = json.loads(raw)
            latency = int((time.perf_counter() - started) * 1000)
            players = data.get("players") or {}
            version = (data.get("version") or {}).get("name", "")
            description = data.get("description")
            if isinstance(description, dict):
                motd = description.get("text", "")
                for extra in description.get("extra", []):
                    if isinstance(extra, dict):
                        motd += extra.get("text", "")
            elif isinstance(description, str):
                motd = description
            else:
                motd = ""
            motd = COLOR_RE.sub("", str(motd or "")).strip()
            return PingResult(
                online=True,
                players=int(players.get("online") or 0),
                max_players=int(players.get("max") or 0),
                latency=latency,
                version=version,
                motd=motd,
            )
    except (OSError, ValueError, ConnectionError, json.JSONDecodeError, struct.error) as err:
        return PingResult(online=False, error=str(err))


def _read_varint_buf(data: bytes, index: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while True:
        if index >= len(data):
            raise ConnectionError("обрезанный пакет")
        byte = data[index]
        index += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, index
        shift += 7
        if shift > 35:
            raise ConnectionError("слишком длинный varint")


class ServerStore:
    def __init__(self, config: LoaderConfig):
        self.config = config
        if not config.servers:
            config.servers = [s.to_dict() for s in DEFAULT_SERVERS]
        self.entries: list[ServerEntry] = [ServerEntry.from_dict(s) for s in config.servers]

    def save(self) -> None:
        self.config.servers = [s.to_dict() for s in self.entries]
        self.config.save()

    def add(self, server: ServerEntry) -> None:
        self.entries.append(server)
        self.save()

    def remove(self, index: int) -> None:
        if 0 <= index < len(self.entries):
            self.entries.pop(index)
            self.save()

    def update(self, index: int, server: ServerEntry) -> None:
        if 0 <= index < len(self.entries):
            self.entries[index] = server
            self.save()

    def by_name(self, name: str) -> ServerEntry | None:
        return next((s for s in self.entries if s.name.lower() == name.lower()), None)

    def popular(self, limit: int = 5) -> list[ServerEntry]:
        return self.entries[:limit]
