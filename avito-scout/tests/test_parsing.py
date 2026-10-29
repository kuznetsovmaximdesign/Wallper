from pathlib import Path

from avito_scout import parser
import pytest

from avito_scout.search_urls import is_avito, page_url
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
    assert parser.rating_and_reviews("Рустал Нет завершённых объявлений") == (None, 0)
    assert parser.rating_and_reviews("Септик 4,5 м · 58 завершённых объявлений") == (None, 0)


def test_parse_search():
    items = parser.parse_search(HTML)
    assert [i.item_id for i in items] == ["1001", "1002", "1003", "1004", "1005", "1006", "1007"]
    a, b, c, d, e, f, g = items
    assert a.seller_key == b.seller_key == "brands:bas500"
    assert (a.seller_name, a.rating, a.reviews, a.price) == ("BAS", 4.9, 74, "45 000 ₽")
    assert a.badges == "Реквизиты проверены"
    assert (b.seller_name, b.rating, b.reviews, b.badges) == ("BAS", 4.9, 74, "Надёжный исполнитель")
    assert a.url == "https://www.avito.ru/moskva/predlozheniya_uslug/montazh_septika_1001"
    assert a.title == "Монтаж септика под ключ"
    assert b.price == "от 30 000 ₽"
    assert (c.seller_key, c.seller_name, c.reviews) == ("user:397e08b939e5", "Иван", 0)
    # Нет ни ссылки на профиль, ни блока продавца — отдельная строка, рейтинг не угадываем.
    assert (d.seller_key, d.rating, d.reviews) == ("item:1004", None, None)
    assert (e.seller_key, e.seller_name, e.rating, e.reviews, e.badges) == (
        "brands:i304651403", "Септик для дома и дачи", None, 0, "Реквизиты проверены")
    assert (f.seller_key, f.rating, f.reviews) == ("item:1006", None, None)  # «4,8 км» не рейтинг
    assert g.seller_key == "item:1007"
    assert parser.total_count(HTML) == 867


def test_seller_name_drops_rating_tail():
    # Строки из живой выдачи 27.10.2026.
    assert parser.seller_name("Александр 5,0 5,0 · 151 отзыв") == "Александр"
    assert parser.seller_name("ПРОСТО СЕПТИК 5,0 5,0 · 3 отзыва") == "ПРОСТО СЕПТИК"
    assert parser.seller_name("Септик Канализация 4,7 4,7 · 315 отзывов") == "Септик Канализация"
    assert parser.seller_name("  АИС - Бурение скважин ") == "АИС - Бурение скважин"
    assert parser.seller_name("Септик 2,5 м³") == "Септик 2,5 м³"
    assert parser.seller_name("") is None


def test_is_blocked():
    assert parser.is_blocked(429, "")
    assert parser.is_blocked(200, "<h2>Доступ ограничен: проблема с IP</h2>")
    assert not parser.is_blocked(439, HTML)  # мягкая метка: выдача пришла целиком


@pytest.mark.parametrize("url", [
    "http://192.168.3.1/", "http://127.0.0.1:8123", "file:///etc/passwd", "https://avito.ru.evil.com/x",
    "https://evil.com/?u=www.avito.ru", "javascript:alert(1)", "//192.168.3.1/", "https://user@192.168.3.1/",
])
def test_only_avito_links(url):
    assert not is_avito(url)
    with pytest.raises(ValueError):
        page_url(url, "забор")


def test_avito_links_allowed():
    assert is_avito("https://www.avito.ru/moskva/predlozheniya_uslug")
    assert is_avito("https://m.avito.ru/x") and is_avito("https://avito.ru/x")
    assert page_url("http://www.avito.ru/x", None).startswith("https://www.avito.ru/")
