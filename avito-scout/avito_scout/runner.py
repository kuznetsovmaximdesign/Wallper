"""Поиск по теме: все запросы × все ссылки поиска × N страниц."""

import logging
import sqlite3
import threading
from collections.abc import Callable
from typing import TYPE_CHECKING

from . import parser
from .collect import save_listings
from .search_urls import default_base, page_url

if TYPE_CHECKING:
    from .fetcher import Page

log = logging.getLogger(__name__)

# Одновременно работает только один поиск: один браузер, один IP.
_lock = threading.Lock()


def plan(conn: sqlite3.Connection, topic_id: int, region: str) -> list[tuple[str, str | None]]:
    """Пары (ссылка-основа, запрос) для темы."""
    bases = [r["url"] for r in conn.execute("SELECT url FROM topic_sources WHERE topic_id = ?", (topic_id,))]
    queries = [r["text"] for r in conn.execute(
        "SELECT text FROM topic_queries WHERE topic_id = ? AND enabled = 1 ORDER BY id", (topic_id,))]
    if not queries and bases and all("q=" in b for b in bases):
        # Только ссылки с запросом внутри — ищем по ним как есть.
        return [(b, None) for b in bases]
    if not queries:
        # Без запросов не прочёсываем весь раздел услуг — ищем по названию темы.
        name = conn.execute("SELECT name FROM topics WHERE id = ?", (topic_id,)).fetchone()["name"]
        queries = [name]
    return [(b, q) for b in (bases or [default_base(region)]) for q in queries]


def run(conn: sqlite3.Connection, topic_id: int, open_page: Callable[[str], "Page"], *,
        region: str, pages_per_query: int) -> int:
    """Выполняет поиск, пишет прогресс в runs; возвращает id запуска."""
    run_id = conn.execute("INSERT INTO runs (topic_id) VALUES (?)", (topic_id,)).lastrowid
    pages = listings = new = 0
    status, message = "done", ""
    try:
        for base, query in plan(conn, topic_id, region):
            for n in range(1, pages_per_query + 1):
                page = open_page(page_url(base, query, n))
                pages += 1
                if parser.is_blocked(page.status, page.html):
                    status, message = "blocked", f"Авито ограничил доступ (HTTP {page.status}) на {page.url}"
                    return run_id
                if page.status == 439:
                    log.warning("Мягкая метка антибота (439) на %s", page.url)
                found = parser.parse_search(page.html)
                listings += len(found)
                new += save_listings(conn, topic_id, query, found)
                conn.execute("UPDATE runs SET pages = ?, listings = ?, new_sellers = ? WHERE id = ?",
                             (pages, listings, new, run_id))
                if len(found) < 20:  # последняя страница выдачи
                    break
    except Exception as e:  # noqa: BLE001 — любая ошибка браузера должна попасть в статус запуска
        log.exception("Поиск по теме %s упал", topic_id)
        status, message = "error", str(e)
    finally:
        conn.execute(
            """UPDATE runs SET finished_at = datetime('now'), status = ?, message = ?,
                   pages = ?, listings = ?, new_sellers = ? WHERE id = ?""",
            (status, message, pages, listings, new, run_id),
        )
    return run_id


def start_in_background(conn: sqlite3.Connection, topic_id: int, cfg) -> bool:
    """Запускает поиск в отдельном потоке; False — если другой поиск ещё идёт."""
    if not _lock.acquire(blocking=False):
        return False

    def work() -> None:
        from .fetcher import Browser

        try:
            with Browser(cfg.browser_dir, headless=cfg.headless, delay=cfg.delay) as browser:
                run(conn, topic_id, browser.open, region=cfg.region, pages_per_query=cfg.pages_per_query)
        except Exception:  # noqa: BLE001
            log.exception("Браузер не запустился")
            conn.execute(
                "INSERT INTO runs (topic_id, finished_at, status, message) VALUES (?, datetime('now'), 'error', ?)",
                (topic_id, "Браузер не запустился — см. журнал службы"),
            )
        finally:
            _lock.release()

    threading.Thread(target=work, daemon=True).start()
    return True


def is_running() -> bool:
    return _lock.locked()
