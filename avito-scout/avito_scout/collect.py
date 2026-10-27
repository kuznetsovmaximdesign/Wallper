"""Сбор найденных объявлений в тему: дедупликация по учётке и критерии отбора."""

import sqlite3
from collections.abc import Iterable

from .models import Listing


def save_listings(conn: sqlite3.Connection, topic_id: int, query: str | None, listings: Iterable[Listing]) -> int:
    """Сохраняет объявления; возвращает, сколько учёток впервые появилось в теме."""
    new_sellers = 0
    for it in listings:
        conn.execute(
            """INSERT INTO sellers (key, name, url, rating, reviews, badges) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT(key) DO UPDATE SET
                   name = coalesce(excluded.name, name),
                   url = coalesce(excluded.url, url),
                   rating = coalesce(excluded.rating, rating),
                   reviews = coalesce(excluded.reviews, reviews),
                   badges = coalesce(excluded.badges, badges),
                   updated_at = datetime('now')""",
            (it.seller_key, it.seller_name, it.seller_url, it.rating, it.reviews, it.badges),
        )
        conn.execute(
            """INSERT INTO listings (item_id, seller_key, title, url, price) VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(item_id) DO UPDATE SET
                   seller_key = excluded.seller_key, title = excluded.title,
                   price = excluded.price, seen_at = datetime('now')""",
            (it.item_id, it.seller_key, it.title, it.url, it.price),
        )
        conn.execute(
            "INSERT OR IGNORE INTO topic_listings (topic_id, item_id, query) VALUES (?, ?, ?)",
            (topic_id, it.item_id, query),
        )
        cur = conn.execute(
            "INSERT OR IGNORE INTO topic_sellers (topic_id, seller_key) VALUES (?, ?)",
            (topic_id, it.seller_key),
        )
        new_sellers += cur.rowcount
    return new_sellers


def executors(conn: sqlite3.Connection, topic_id: int, *, all_statuses: bool = False) -> list[dict]:
    """Строки таблицы темы: одна учётка — одна строка, с её объявлениями.

    Критерии темы (рейтинг/отзывы) применяются здесь: учётка без рейтинга не проходит
    порог рейтинга, без числа отзывов — порог отзывов.
    """
    topic = conn.execute("SELECT min_rating, min_reviews FROM topics WHERE id = ?", (topic_id,)).fetchone()
    where = ["ts.topic_id = ?"]
    args: list = [topic_id]
    if topic["min_rating"] is not None:
        where.append("s.rating >= ?")
        args.append(topic["min_rating"])
    if topic["min_reviews"] is not None:
        where.append("s.reviews >= ?")
        args.append(topic["min_reviews"])
    if not all_statuses:
        where.append("ts.status != 'rejected'")
    rows = conn.execute(
        f"""SELECT s.key, s.name, s.url, s.rating, s.reviews, s.badges, ts.status, ts.note, ts.first_seen,
                   (SELECT group_concat(t.topic_id) FROM topic_sellers t
                     WHERE t.seller_key = s.key AND t.topic_id != ts.topic_id) AS other_topics
            FROM topic_sellers ts JOIN sellers s ON s.key = ts.seller_key
            WHERE {' AND '.join(where)}
            ORDER BY ts.status = 'favorite' DESC, s.rating DESC, s.reviews DESC""",
        args,
    ).fetchall()
    result = []
    for r in rows:
        items = conn.execute(
            """SELECT l.item_id, l.title, l.url, l.price FROM topic_listings tl
               JOIN listings l ON l.item_id = tl.item_id
               WHERE tl.topic_id = ? AND l.seller_key = ? ORDER BY l.seen_at DESC""",
            (topic_id, r["key"]),
        ).fetchall()
        result.append({**dict(r), "listings": [dict(i) for i in items]})
    return result
