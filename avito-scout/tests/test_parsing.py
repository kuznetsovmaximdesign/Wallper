from pathlib import Path

from avito_scout import parser
from avito_scout.search_urls import page_url
from avito_scout.sellers import fallback_key, profile_key, profile_url

HTML = (Path(__file__).parent / "fixtures" / "search.html").read_text()


def test_profile_key_ignores_tails_case_and_domain():
    assert profile_key("/brands/bas500?iid=1&src=search_seller_info") == "brands:bas500"
    assert profile_key("https://www.avito.ru/brands/BAS500?shopId=1125558&page_from=x") == "brands:bas500"
    assert profile_key("/brands/i304651403") == "brands:i304651403"
    assert profile_key("/user/397e08b9/profile?src=x") == "user:397e08b9"
    assert profile_key("/moskva/predlozheniya_uslug/x_1") is None
    assert profile_key(None) is None


def test_profile_url_round_trip():
    assert profile_url("brands:bas500") == "https://www.avito.ru/brands/bas500"
    assert profile_url("user:abc") == "https://www.avito.ru/user/abc/profile"
    assert profile_url(fallback_key("Иван", None, None)) is None


def test_page_url_keeps_filters_and_replaces_query_and_page():
    base = "https://www.avito.ru/istra/predlozheniya_uslug?radius=50&q=old&p=3"
    assert page_url(base, "монтаж септика") == (
        "https://www.avito.ru/istra/predlozheniya_uslug?radius=50&q=%D0%BC%D0%BE%D0%BD%D1%82%D0%B0%D0%B6+"
        "%D1%81%D0%B5%D0%BF%D1%82%D0%B8%D0%BA%D0%B0"
    )
    assert page_url(base, None, 2) == "https://www.avito.ru/istra/predlozheniya_uslug?radius=50&p=2"


def test_rating_and_reviews_variants():
    assert parser.rating_and_reviews("BAS 4,9 · 74 отзыва") == (4.9, 74)
    assert parser.rating_and_reviews("4,9 · 1 204 отзыва") == (4.9, 1204)
    assert parser.rating_and_reviews("Иван Нет отзывов") == (None, 0)
    assert parser.rating_and_reviews("Компания") == (None, None)


def test_parse_search():
    items = parser.parse_search(HTML)
    assert [i.item_id for i in items] == ["1001", "1002", "1003", "1004"]
    a, b, c, d = items
    assert a.seller_key == b.seller_key == "brands:bas500"
    assert (a.seller_name, a.rating, a.reviews, a.price) == ("BAS", 4.9, 74, "45 000 ₽")
    assert a.badges == "Реквизиты проверены" and b.badges is None
    assert a.url == "https://www.avito.ru/moskva/predlozheniya_uslug/montazh_septika_1001"
    assert a.title == "Монтаж септика под ключ"
    assert b.price == "от 30 000 ₽"
    assert (c.seller_key, c.seller_name, c.reviews) == ("user:397e08b939e5", "Иван", 0)
    assert d.seller_key.startswith("name:") and (d.rating, d.reviews) == (4.6, 12)
    assert parser.total_count(HTML) == 867


def test_is_blocked():
    assert parser.is_blocked(429, "")
    assert parser.is_blocked(200, "<h2>Доступ ограничен: проблема с IP</h2>")
    assert not parser.is_blocked(439, HTML)  # мягкая метка: выдача пришла целиком
