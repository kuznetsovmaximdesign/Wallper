from dataclasses import dataclass


@dataclass
class Listing:
    """Объявление из выдачи Авито вместе с тем, что видно о продавце в карточке."""

    item_id: str
    title: str
    url: str
    price: str | None
    seller_key: str
    seller_name: str | None
    seller_url: str | None
    rating: float | None
    reviews: int | None
    badges: str | None = None  # «Реквизиты проверены, Надёжный исполнитель»
