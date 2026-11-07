"""Запуск Goldishka Loader без консольного окна."""

from __future__ import annotations

import os
import sys
import traceback
from pathlib import Path

if getattr(sys, "frozen", False):
    ROOT = Path(sys.executable).resolve().parent
else:
    ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

try:
    from goldishka.app import main

    main()
except Exception:  # noqa: BLE001
    logs = ROOT / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    (logs / "launcher-error.log").write_text(traceback.format_exc(), encoding="utf-8")
    try:
        import ctypes

        ctypes.windll.user32.MessageBoxW(
            None,
            "Лаунчер не смог запуститься.\n\nПодробности: logs\\launcher-error.log",
            "Goldishka Loader",
            0x10,
        )
    except Exception:  # noqa: BLE001
        raise
