import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class Config:
    data_dir: Path = field(default_factory=lambda: Path(os.environ.get("AVITO_SCOUT_DATA", "var")))
    # Регион по умолчанию, если у темы нет своей ссылки поиска.
    region: str = os.environ.get("AVITO_SCOUT_REGION", "moskva")
    # Пароль на веб-интерфейс (HTTP Basic). Пусто — без пароля, только для локального запуска.
    password: str = os.environ.get("AVITO_SCOUT_PASSWORD", "")
    headless: bool = os.environ.get("AVITO_SCOUT_HEADLESS", "0") == "1"
    pages_per_query: int = int(os.environ.get("AVITO_SCOUT_PAGES", "2"))
    # Пауза между страницами, секунды: случайно в этом диапазоне.
    delay: tuple[float, float] = (6.0, 14.0)

    @property
    def db_path(self) -> Path:
        return self.data_dir / "avito-scout.sqlite3"

    @property
    def browser_dir(self) -> Path:
        return self.data_dir / "browser"


def load() -> Config:
    cfg = Config()
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    return cfg
