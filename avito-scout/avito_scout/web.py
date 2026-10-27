import base64
import secrets
import sqlite3
from pathlib import Path

from fastapi import FastAPI, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from . import config, db, runner
from .collect import executors
from .export import to_csv

_HERE = Path(__file__).parent
STATUSES = {"new": "Новый", "favorite": "Избранное", "rejected": "Не подходит"}


def _lines(text: str) -> list[str]:
    seen: dict[str, None] = {}
    for line in text.splitlines():
        line = line.strip()
        if line:
            seen.setdefault(line, None)
    return list(seen)


def _num(text: str, kind: type):
    text = text.strip().replace(",", ".")
    return kind(text) if text else None


def create_app(cfg: config.Config | None = None, conn: sqlite3.Connection | None = None) -> FastAPI:
    cfg = cfg or config.load()
    conn = conn or db.connect(cfg.db_path)
    app = FastAPI(title="Avito Scout", docs_url=None, redoc_url=None)
    app.mount("/static", StaticFiles(directory=_HERE / "static"), name="static")
    tpl = Jinja2Templates(directory=_HERE / "templates")
    tpl.env.globals["STATUSES"] = STATUSES

    if cfg.password:
        expected = cfg.password.encode()

        @app.middleware("http")
        async def basic_auth(request: Request, call_next):
            header = request.headers.get("authorization", "")
            if header.startswith("Basic "):
                try:
                    _, _, pwd = base64.b64decode(header[6:]).partition(b":")
                except ValueError:
                    pwd = b""
                if secrets.compare_digest(pwd, expected):
                    return await call_next(request)
            return Response(status_code=401, headers={"WWW-Authenticate": 'Basic realm="Avito Scout"'})

    def topic_or_404(topic_id: int):
        t = conn.execute("SELECT * FROM topics WHERE id = ?", (topic_id,)).fetchone()
        if t is None:
            raise HTTPException(404, "Тема не найдена")
        return t

    @app.get("/", response_class=HTMLResponse)
    def index(request: Request):
        topics = conn.execute(
            """SELECT t.*, (SELECT count(*) FROM topic_sellers ts WHERE ts.topic_id = t.id) AS sellers
               FROM topics t ORDER BY t.id DESC"""
        ).fetchall()
        return tpl.TemplateResponse(request, "index.html", {"topics": topics})

    @app.post("/topics")
    def create_topic(name: str = Form(...), description: str = Form("")):
        tid = conn.execute("INSERT INTO topics (name, description) VALUES (?, ?)",
                           (name.strip(), description.strip())).lastrowid
        return RedirectResponse(f"/topics/{tid}", status_code=303)

    @app.get("/topics/{topic_id}", response_class=HTMLResponse)
    def topic_page(request: Request, topic_id: int, all: int = 0):
        t = topic_or_404(topic_id)
        ctx = {
            "topic": t,
            "sources": [r["url"] for r in conn.execute("SELECT url FROM topic_sources WHERE topic_id = ? ORDER BY id", (topic_id,))],
            "queries": [r["text"] for r in conn.execute("SELECT text FROM topic_queries WHERE topic_id = ? ORDER BY id", (topic_id,))],
            "rows": executors(conn, topic_id, all_statuses=bool(all)),
            "show_all": bool(all),
            "runs": conn.execute("SELECT * FROM runs WHERE topic_id = ? ORDER BY id DESC LIMIT 5", (topic_id,)).fetchall(),
            "running": runner.is_running(),
            "region": cfg.region,
        }
        return tpl.TemplateResponse(request, "topic.html", ctx)

    @app.post("/topics/{topic_id}/settings")
    def save_settings(topic_id: int, name: str = Form(...), description: str = Form(""),
                      min_rating: str = Form(""), min_reviews: str = Form(""),
                      sources: str = Form(""), queries: str = Form("")):
        topic_or_404(topic_id)
        conn.execute("UPDATE topics SET name = ?, description = ?, min_rating = ?, min_reviews = ? WHERE id = ?",
                     (name.strip(), description.strip(), _num(min_rating, float), _num(min_reviews, int), topic_id))
        conn.execute("DELETE FROM topic_sources WHERE topic_id = ?", (topic_id,))
        conn.executemany("INSERT INTO topic_sources (topic_id, url) VALUES (?, ?)",
                         [(topic_id, u) for u in _lines(sources)])
        conn.execute("DELETE FROM topic_queries WHERE topic_id = ?", (topic_id,))
        conn.executemany("INSERT INTO topic_queries (topic_id, text) VALUES (?, ?)",
                         [(topic_id, q) for q in _lines(queries)])
        return RedirectResponse(f"/topics/{topic_id}", status_code=303)

    @app.post("/topics/{topic_id}/run")
    def run_search(topic_id: int):
        topic_or_404(topic_id)
        runner.start_in_background(conn, topic_id, cfg)
        return RedirectResponse(f"/topics/{topic_id}", status_code=303)

    @app.post("/topics/{topic_id}/sellers/status")
    def set_status(topic_id: int, key: str = Form(...), status: str = Form(...), note: str = Form(None)):
        if status not in STATUSES:
            status = "new"
        conn.execute("UPDATE topic_sellers SET status = ? WHERE topic_id = ? AND seller_key = ?",
                     (status, topic_id, key))
        if note is not None:
            conn.execute("UPDATE topic_sellers SET note = ? WHERE topic_id = ? AND seller_key = ?",
                         (note.strip(), topic_id, key))
        return RedirectResponse(f"/topics/{topic_id}#s-{key}", status_code=303)

    @app.get("/topics/{topic_id}/export.csv")
    def export(topic_id: int):
        topic_or_404(topic_id)
        body = to_csv(executors(conn, topic_id))
        filename = f"avito-scout-{topic_id}.csv"
        return Response(body, media_type="text/csv; charset=utf-8",
                        headers={"Content-Disposition": f'attachment; filename="{filename}"'})

    return app
