import sqlite3
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS topics (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    min_rating REAL,
    min_reviews INTEGER,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
-- Ссылки поиска Авито с локацией/радиусом/фильтрами; пусто — регион по умолчанию.
CREATE TABLE IF NOT EXISTS topic_sources (
    id INTEGER PRIMARY KEY,
    topic_id INTEGER NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
    url TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS topic_queries (
    id INTEGER PRIMARY KEY,
    topic_id INTEGER NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
    text TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    UNIQUE (topic_id, text)
);
CREATE TABLE IF NOT EXISTS sellers (
    key TEXT PRIMARY KEY,
    name TEXT,
    url TEXT,
    rating REAL,
    reviews INTEGER,
    badges TEXT,
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS listings (
    item_id TEXT PRIMARY KEY,
    seller_key TEXT NOT NULL REFERENCES sellers(key),
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    price TEXT,
    seen_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS topic_listings (
    topic_id INTEGER NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
    item_id TEXT NOT NULL REFERENCES listings(item_id),
    query TEXT,
    PRIMARY KEY (topic_id, item_id)
);
-- Исполнитель в теме: одна строка таблицы. status: new | favorite | rejected.
CREATE TABLE IF NOT EXISTS topic_sellers (
    topic_id INTEGER NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
    seller_key TEXT NOT NULL REFERENCES sellers(key),
    status TEXT NOT NULL DEFAULT 'new',
    note TEXT NOT NULL DEFAULT '',
    first_seen TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (topic_id, seller_key)
);
CREATE TABLE IF NOT EXISTS runs (
    id INTEGER PRIMARY KEY,
    topic_id INTEGER NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
    started_at TEXT NOT NULL DEFAULT (datetime('now')),
    finished_at TEXT,
    status TEXT NOT NULL DEFAULT 'running',
    message TEXT NOT NULL DEFAULT '',
    pages INTEGER NOT NULL DEFAULT 0,
    listings INTEGER NOT NULL DEFAULT 0,
    new_sellers INTEGER NOT NULL DEFAULT 0
);
"""


def connect(path: Path | str) -> sqlite3.Connection:
    conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.executescript(SCHEMA)
    return conn
