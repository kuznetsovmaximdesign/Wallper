import csv
import io


def to_csv(rows: list[dict]) -> str:
    """CSV для Excel: точка с запятой и BOM, чтобы кириллица открылась без мастера импорта."""
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=";")
    w.writerow(["Исполнитель", "Рейтинг", "Отзывов", "Статус", "Профиль", "Объявлений", "Объявления", "Заметка"])
    for r in rows:
        w.writerow([
            r["name"] or "",
            str(r["rating"]).replace(".", ",") if r["rating"] is not None else "",
            r["reviews"] if r["reviews"] is not None else "",
            r["status"],
            r["url"] or "",
            len(r["listings"]),
            "\n".join(f'{i["title"]} — {i["url"]}' for i in r["listings"]),
            r["note"],
        ])
    return "﻿" + buf.getvalue()
