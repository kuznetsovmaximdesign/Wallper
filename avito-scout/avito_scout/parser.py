"""Разбор HTML выдачи Авито.

CSS-классы у Авито хешированные и меняются, поэтому опора — data-marker и href
(разметка сверена с живой выдачей 27.10.2026):

    item[data-item-id] → item-title, item-price-value, item-link (имя + /brands/<slug>),
                         seller-rating/score, seller-info/summary («74 отзыва Реквизиты проверены»)

Если маркеров нет — запасной разбор по тексту «4,9 · 74 отзыва».
"""

import re

from bs4 import BeautifulSoup, Tag

from .models import Listing
from .sellers import fallback_key, profile_key, profile_url

_RATING_REVIEWS = re.compile(r"(\d[.,]\d)\s*(?:·|\||\s)\s*(\d[\d\s ]*)\s*отзыв")
_REVIEWS_ONLY = re.compile(r"(\d[\d\s ]*)\s*отзыв")
_RATING_ONLY = re.compile(r"(?<![\d.,])([1-5][.,]\d)(?![\d.,])")
_NO_REVIEWS = re.compile(r"нет отзывов", re.I)


def _int(s: str) -> int:
    return int(re.sub(r"\D", "", s))


def rating_and_reviews(text: str) -> tuple[float | None, int | None]:
    text = text.replace(" ", " ")
    m = _RATING_REVIEWS.search(text)
    if m:
        return float(m.group(1).replace(",", ".")), _int(m.group(2))
    if _NO_REVIEWS.search(text):
        return None, 0
    reviews = _REVIEWS_ONLY.search(text)
    rating = _RATING_ONLY.search(text)
    return (
        float(rating.group(1).replace(",", ".")) if rating else None,
        _int(reviews.group(1)) if reviews else None,
    )


_BADGES = ("Реквизиты проверены", "Документы проверены", "Надёжный исполнитель")


def badges(text: str) -> str | None:
    found = [b for b in _BADGES if b.lower() in text.lower()]
    return ", ".join(found) or None


def _seller_link(card: Tag) -> Tag | None:
    """Ссылка на профиль; у аватарки текста нет, поэтому предпочитаем ссылку с именем."""
    marked = card.select_one('a[data-marker="item-link"][href]')
    if marked is not None and profile_key(marked["href"]):
        return marked
    links = [a for a in card.find_all("a", href=True) if profile_key(a["href"])]
    named = [a for a in links if a.get_text(strip=True)]
    return (named or links or [None])[0]


def _seller_block(card: Tag, link: Tag | None) -> Tag:
    """Блок продавца в карточке: от ссылки на профиль вверх, пока в нём нет отзывов."""
    if link is None:
        return card
    node: Tag = link
    while node is not card and node.parent is not None:
        if "отзыв" in node.get_text(" ") or "нет отзывов" in node.get_text(" ").lower():
            return node
        node = node.parent
    return card


def parse_search(html: str) -> list[Listing]:
    soup = BeautifulSoup(html, "html.parser")
    out: list[Listing] = []
    for card in soup.select('[data-marker="item"]'):
        item_id = card.get("data-item-id") or card.get("id", "").lstrip("i")
        title_a = card.select_one('[data-marker="item-title"]') or card.find("a", href=True)
        if not item_id or title_a is None:
            continue
        href = title_a.get("href", "")
        url = href if href.startswith("http") else f"https://www.avito.ru{href.split('?')[0]}"
        title = (title_a.get("title") or title_a.get_text(" ")).strip()

        price_meta = card.select_one('meta[itemprop="price"]')
        price_el = card.select_one('[data-marker="item-price-value"], [data-marker="item-price"]')
        price = price_meta["content"] if price_meta and price_meta.get("content") else (
            price_el.get_text(" ").strip() if price_el else None
        )

        link = _seller_link(card)
        score = card.select_one('[data-marker="seller-rating/score"]')
        summary = card.select_one('[data-marker="seller-info/summary"]')
        if summary is not None:
            text = (score.get_text(" ") + " · " if score else "") + summary.get_text(" ")
        else:
            text = _seller_block(card, link).get_text(" ")
        rating, reviews = rating_and_reviews(text)
        name = None
        if link is not None:
            name = (link.get_text(" ").strip() or None)
        key = profile_key(link["href"]) if link is not None else None
        if key is None:
            key = fallback_key(name, rating, reviews)
        out.append(
            Listing(
                item_id=str(item_id),
                title=title,
                url=url,
                price=price,
                seller_key=key,
                seller_name=name,
                seller_url=profile_url(key),
                rating=rating,
                reviews=reviews,
                badges=badges(text),
            )
        )
    return out


def total_count(html: str) -> int | None:
    """«867 объявлений» из шапки выдачи, если есть."""
    soup = BeautifulSoup(html, "html.parser")
    el = soup.select_one('[data-marker="page-title/count"]')
    if el:
        try:
            return _int(el.get_text())
        except ValueError:
            return None
    return None


def is_blocked(status: int, html: str) -> bool:
    """Жёсткая блокировка: 429/403 или страница «Доступ ограничен» вместо выдачи."""
    if status in (403, 429):
        return True
    return "Доступ ограничен" in html and 'data-marker="item"' not in html
