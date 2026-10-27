# Avito Scout

Поиск исполнителей на Авито: темы («Забор», «Септик»), запросы и ссылки поиска,
критерии (рейтинг, отзывы), одна строка таблицы на учётку, экспорт в Excel.
План и решения — [PLAN.md](PLAN.md).

> Отдельный проект, к Wallper не относится. Перенос в свой репозиторий:
> `git subtree split --prefix=avito-scout -b avito-scout` → push в новый репозиторий.

## Устройство

| Модуль | Что делает |
| --- | --- |
| `avito_scout/web.py` | Веб-интерфейс (FastAPI + шаблоны), адаптивный для телефона |
| `avito_scout/runner.py` | Поиск по теме: запросы × ссылки × страницы, остановка при блокировке |
| `avito_scout/fetcher.py` | Робот: Playwright Chromium с окном, без прокси, профиль браузера хранится |
| `avito_scout/parser.py` | Разбор выдачи: объявления, продавец, рейтинг, отзывы |
| `avito_scout/sellers.py` | Ключ учётки (`/brands/<id>`, `/user/<id>`) — дедупликация |
| `avito_scout/collect.py` | Сохранение в SQLite, критерии отбора, строки таблицы |

## Локальный запуск

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/python -m pytest -q
AVITO_SCOUT_DATA=var .venv/bin/python -m avito_scout   # http://127.0.0.1:8793
```

## Мини-ПК

Служба `avito-scout` (порт 8793, пользователь `avito`, данные в `/opt/avito-scout/var`)
и таймер `avito-scout-update` — раз в минуту забирает ветку из `deploy.env`, гоняет
тесты и перезапускает службу; при падении тестов откатывается. Файлы — в `deploy/`.

Первичная установка (один раз, с sudo):

```bash
sudo useradd -r -m -d /opt/avito-scout -s /usr/sbin/nologin avito
sudo -u avito mkdir -p /opt/avito-scout/.ssh /opt/avito-scout/var
sudo -u avito ssh-keygen -t ed25519 -N '' -f /opt/avito-scout/.ssh/id_ed25519
#   .pub → GitHub: Wallper → Settings → Deploy keys (read-only)
sudo -u avito git clone --filter=blob:none --sparse git@github.com:kuznetsovmaximdesign/Wallper.git /opt/avito-scout/repo
sudo -u avito git -C /opt/avito-scout/repo sparse-checkout set avito-scout
echo 'BRANCH=claude/avito-executor-search-vnc5rq' | sudo -u avito tee /opt/avito-scout/deploy.env
sudo -u avito git -C /opt/avito-scout/repo checkout -B deploy "origin/$(. /opt/avito-scout/deploy.env; echo $BRANCH)"
sudo -u avito python3 -m venv /opt/avito-scout/.venv
sudo -u avito /opt/avito-scout/.venv/bin/pip install -r /opt/avito-scout/repo/avito-scout/requirements-dev.txt
sudo -u avito env PLAYWRIGHT_BROWSERS_PATH=/opt/avito-scout/ms-playwright /opt/avito-scout/.venv/bin/playwright install chromium
echo 'AVITO_SCOUT_PASSWORD=…' | sudo -u avito tee /opt/avito-scout/avito-scout.env && sudo chmod 600 /opt/avito-scout/avito-scout.env
sudo cp /opt/avito-scout/repo/avito-scout/deploy/avito-scout{,-update}.service /opt/avito-scout/repo/avito-scout/deploy/avito-scout-update.timer /etc/systemd/system/
sudo install -m 440 /opt/avito-scout/repo/avito-scout/deploy/sudoers /etc/sudoers.d/avito-scout
sudo systemctl daemon-reload && sudo systemctl enable --now avito-scout avito-scout-update.timer
```

Git у пользователя `avito` ходит в GitHub через тот же выход, что и остальные
службы; если GitHub без прокси недоступен — добавить прокси в `~avito/.gitconfig`
(`http.proxy`), но **не** в окружение службы: робот к Авито ходит напрямую.

С телефона — через Tailscale (`http://100.103.242.45:8793`, если слушать на
Tailscale-IP) или публикацией KeenDNS `avito` → `127.0.0.1:8793`.
