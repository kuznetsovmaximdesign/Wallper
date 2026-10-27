"""Браузер-робот: открывает страницы Авито настоящим Chromium.

Авито режет curl и зарубежные IP, а настоящий Chromium с домашнего IP пропускает
(разведка 27.10.2026). Поэтому прокси (на мини-ПК он ведёт в Амстердам) у браузера
снимается, а профиль браузера хранится между запусками, как у обычного человека.
"""

import os
import random
import time
from dataclasses import dataclass
from pathlib import Path

_PROXY_VARS = {"http_proxy", "https_proxy", "all_proxy", "no_proxy"}


def browser_env() -> dict[str, str]:
    return {k: v for k, v in os.environ.items() if k.lower() not in _PROXY_VARS}


@dataclass
class Page:
    url: str
    status: int
    html: str


class Browser:
    def __init__(self, profile_dir: Path, headless: bool = False, delay: tuple[float, float] = (6.0, 14.0)):
        self.profile_dir = profile_dir
        self.headless = headless
        self.delay = delay
        self._pw = None
        self._ctx = None
        self._last = 0.0

    def __enter__(self) -> "Browser":
        from playwright.sync_api import sync_playwright

        self._pw = sync_playwright().start()
        self._ctx = self._pw.chromium.launch_persistent_context(
            str(self.profile_dir),
            headless=self.headless,
            env=browser_env(),
            locale="ru-RU",
            timezone_id="Europe/Moscow",
            viewport={"width": 1366, "height": 768},
            args=["--no-proxy-server"],
        )
        return self

    def __exit__(self, *exc) -> None:
        if self._ctx:
            self._ctx.close()
        if self._pw:
            self._pw.stop()

    def _pause(self) -> None:
        wait = random.uniform(*self.delay) - (time.monotonic() - self._last)
        if wait > 0:
            time.sleep(wait)

    def open(self, url: str) -> Page:
        self._pause()
        page = self._ctx.pages[0] if self._ctx.pages else self._ctx.new_page()
        resp = page.goto(url, wait_until="domcontentloaded", timeout=60_000)
        page.wait_for_timeout(random.randint(1500, 3500))
        # Немного прокрутки, как у человека, — заодно догружаются карточки.
        for _ in range(random.randint(2, 4)):
            page.mouse.wheel(0, random.randint(500, 1100))
            page.wait_for_timeout(random.randint(400, 1200))
        self._last = time.monotonic()
        return Page(url=page.url, status=resp.status if resp else 0, html=page.content())
