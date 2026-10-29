"""Адреса страниц поиска.

Локацию и радиус проще всего задать на самом Авито и вставить ссылку поиска:
сервис подставляет в неё свои запросы (q) и номер страницы (p), остальные
фильтры ссылки сохраняются.
"""

from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit


def default_base(region: str) -> str:
    return f"https://www.avito.ru/{region}/predlozheniya_uslug"


def is_avito(url: str) -> bool:
    """Робот ходит только на Авито: иначе ссылка в настройках открыла бы роутер и домашние сервисы."""
    parts = urlsplit(url)
    host = (parts.hostname or "").lower()
    if not parts.netloc:
        return not parts.scheme and url.startswith("/")
    return parts.scheme in ("http", "https") and (host == "avito.ru" or host.endswith(".avito.ru"))


def page_url(base: str, query: str | None, page: int = 1) -> str:
    if not is_avito(base):
        raise ValueError(f"Не ссылка на Авито: {base}")
    parts = urlsplit(base)
    params = [(k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True) if k not in ("q", "p")]
    if query:
        params.append(("q", query))
    if page > 1:
        params.append(("p", str(page)))
    return urlunsplit(("https", parts.netloc or "www.avito.ru", parts.path, urlencode(params), ""))
