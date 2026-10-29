from dataclasses import replace
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from avito_scout import config, db, parser, runner
from avito_scout.collect import executors, save_listings
from avito_scout.fetcher import Page, browser_env
from avito_scout.web import create_app

HTML = (Path(__file__).parent / "fixtures" / "search.html").read_text()


@pytest.fixture
def conn(tmp_path):
    return db.connect(tmp_path / "t.sqlite3")


def topic(conn, **kw) -> int:
    tid = conn.execute("INSERT INTO topics (name) VALUES ('Септик')").lastrowid
    for k, v in kw.items():
        conn.execute(f"UPDATE topics SET {k} = ? WHERE id = ?", (v, tid))
    return tid


def test_dedup_and_criteria(conn):
    tid = topic(conn)
    items = parser.parse_search(HTML)
    assert save_listings(conn, tid, "септик", items) == 6  # 7 объявлений, 6 учёток
    assert save_listings(conn, tid, "септик", items) == 0  # повторный поиск — новых нет
    rows = executors(conn, tid)
    assert len(rows) == 6
    bas = next(r for r in rows if r["key"] == "brands:bas500")
    assert len(bas["listings"]) == 2
    assert bas["badges"] == "Реквизиты проверены, Надёжный исполнитель"

    conn.execute("UPDATE topics SET min_rating = 4.7, min_reviews = 20 WHERE id = ?", (tid,))
    assert [r["key"] for r in executors(conn, tid)] == ["brands:bas500"]


def test_rejected_hidden_and_other_topics(conn):
    t1, t2 = topic(conn), topic(conn)
    items = parser.parse_search(HTML)
    save_listings(conn, t1, None, items)
    save_listings(conn, t2, None, items[:1])
    conn.execute("UPDATE topic_sellers SET status = 'rejected' WHERE topic_id = ? AND seller_key LIKE 'user:%'", (t1,))
    assert len(executors(conn, t1)) == 5
    assert len(executors(conn, t1, all_statuses=True)) == 6
    assert next(r for r in executors(conn, t2))["other_topics"] == str(t1)


def test_run_stops_on_block(conn):
    tid = topic(conn)
    conn.execute("INSERT INTO topic_queries (topic_id, text) VALUES (?, 'септик'), (?, 'канализация')", (tid, tid))
    opened = []

    def fake_open(url):
        opened.append(url)
        return Page(url, 200, HTML) if len(opened) == 1 else Page(url, 429, "Доступ ограничен")

    run_id = runner.run(conn, tid, fake_open, region="istra", pages_per_query=3)
    r = conn.execute("SELECT * FROM runs WHERE id = ?", (run_id,)).fetchone()
    # Первая страница короче 20 объявлений → следующий запрос, на нём блок → стоп.
    assert len(opened) == 2 and "istra" in opened[0]
    assert (r["status"], r["pages"], r["listings"], r["new_sellers"]) == ("blocked", 2, 7, 6)


def test_plan_never_scans_whole_category(conn):
    tid = topic(conn)
    assert runner.plan(conn, tid, "istra") == [("https://www.avito.ru/istra/predlozheniya_uslug", "Септик")]
    conn.execute("INSERT INTO topic_sources (topic_id, url) VALUES (?, 'https://www.avito.ru/istra/predlozheniya_uslug?q=septik&radius=30')", (tid,))
    assert runner.plan(conn, tid, "istra") == [("https://www.avito.ru/istra/predlozheniya_uslug?q=septik&radius=30", None)]
    conn.execute("INSERT INTO topic_sources (topic_id, url) VALUES (?, 'https://www.avito.ru/istra/predlozheniya_uslug?radius=30')", (tid,))
    assert [q for _, q in runner.plan(conn, tid, "istra")] == ["Септик", "Септик"]


def test_browser_env_drops_proxy(monkeypatch):
    monkeypatch.setenv("HTTPS_PROXY", "http://127.0.0.1:1081")
    monkeypatch.setenv("all_proxy", "socks5://x")
    env = browser_env()
    assert "HTTPS_PROXY" not in env and "all_proxy" not in env and "PATH" in env


def client(tmp_path, password=""):
    cfg = replace(config.Config(), data_dir=tmp_path, password=password)
    return TestClient(create_app(cfg, db.connect(tmp_path / "w.sqlite3")))


def test_web_flow(tmp_path):
    c = client(tmp_path)
    r = c.post("/topics", data={"name": "Забор", "description": "Забор 40 м"})
    assert r.status_code == 200 and "Забор" in r.text
    tid = int(str(r.url).rsplit("/", 1)[1])
    r = c.post(f"/topics/{tid}/settings", data={
        "name": "Забор", "description": "", "min_rating": "4,5", "min_reviews": "10",
        "sources": "https://www.avito.ru/istra/predlozheniya_uslug?radius=30\n",
        "queries": "забор из профлиста\nзабор из профлиста\nустановка забора",
    })
    assert "установка забора" in r.text and r.text.count("забор из профлиста") == 1
    assert 'value="4.5"' in r.text
    assert c.get(f"/topics/{tid}/export.csv").text.startswith("﻿Исполнитель;")
    assert c.get("/topics/999").status_code == 404
    r = c.post(f"/topics/{tid}/settings", data={"name": "Забор", "sources": "http://192.168.3.1/"})
    assert r.status_code == 400
    assert "radius=30" in c.get(f"/topics/{tid}").text


def test_password(tmp_path):
    c = client(tmp_path, password="s3cret")
    assert c.get("/").status_code == 401
    assert c.get("/", auth=("max", "wrong")).status_code == 401
    assert c.get("/", auth=("max", "s3cret")).status_code == 200
