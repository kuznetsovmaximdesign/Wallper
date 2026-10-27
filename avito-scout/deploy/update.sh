#!/usr/bin/env bash
# Автообновление Avito Scout на мини-ПК (по образцу neiron-update): раз в минуту
# забирает ветку из GitHub; при изменениях в avito-scout/ гоняет тесты и
# перезапускает службу, при падении тестов откатывается на прежний коммит.
set -euo pipefail

ROOT=/opt/avito-scout
REPO=$ROOT/repo
VENV=$ROOT/.venv
# shellcheck source=/dev/null
. "$ROOT/deploy.env"   # BRANCH=…

cd "$REPO"
git fetch -q origin "$BRANCH"
old=$(git rev-parse HEAD)
new=$(git rev-parse FETCH_HEAD)
[ "$old" = "$new" ] && exit 0

git reset -q --hard "$new"
if git diff --quiet "$old" "$new" -- avito-scout; then
    exit 0  # изменения не в нашей папке
fi

if ! git diff --quiet "$old" "$new" -- avito-scout/requirements.txt avito-scout/requirements-dev.txt; then
    "$VENV/bin/pip" install -q -r avito-scout/requirements-dev.txt
fi

if ! (cd avito-scout && "$VENV/bin/python" -m pytest -q -p no:cacheprovider); then
    echo "avito-scout: тесты упали на $new, откат на $old" >&2
    git reset -q --hard "$old"
    exit 1
fi

sudo -n systemctl restart avito-scout
sleep 3
systemctl is-active --quiet avito-scout
echo "avito-scout: обновлено до $new"
