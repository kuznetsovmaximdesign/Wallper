"""Ключ учётки продавца — по нему сворачиваются объявления одного исполнителя."""

import re
from urllib.parse import urlsplit

# /brands/bas500, /brands/i304651403, /brands/397e08b9…, /user/<id>/profile
_PROFILE = re.compile(r"^/(brands|user)/([^/?#]+)")


def profile_key(href: str | None) -> str | None:
    """Ключ из ссылки на профиль; хвосты ?iid=…&src=… и домен отбрасываются."""
    if not href:
        return None
    m = _PROFILE.match(urlsplit(href).path)
    if not m:
        return None
    return f"{m.group(1)}:{m.group(2).lower()}"


def profile_url(key: str) -> str | None:
    kind, _, ident = key.partition(":")
    if kind == "brands":
        return f"https://www.avito.ru/brands/{ident}"
    if kind == "user":
        return f"https://www.avito.ru/user/{ident}/profile"
    return None


def fallback_key(name: str | None, rating: float | None, reviews: int | None) -> str:
    """Запасной ключ, если ссылки на профиль в карточке нет.

    Имя само по себе не уникально, поэтому к нему добавляются рейтинг и отзывы.
    """
    norm = re.sub(r"\s+", " ", (name or "").strip().lower())
    return f"name:{norm}|{rating if rating is not None else '-'}|{reviews if reviews is not None else '-'}"
