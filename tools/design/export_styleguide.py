# -*- coding: utf-8 -*-
"""Делает из /styleguide.html один файл со встроенными стилями, который открывается без запуска приложения.

    python tools/design/export_styleguide.py                        # tools/design/out/design-system.html
    python tools/design/export_styleguide.py ../Дизайн-система.html  # свой путь
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
STATIC = os.path.abspath(os.path.join(HERE, "..", "..", "src", "main", "resources", "static"))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "out", "design-system.html")
    with open(os.path.join(STATIC, "styleguide.html"), encoding="utf-8") as f:
        html = f.read()
    for name in ("tokens.css", "ui.css"):
        link = f'<link rel="stylesheet" href="/css/{name}">'
        if link not in html:
            sys.exit(f"в styleguide.html нет строки {link}")
        with open(os.path.join(STATIC, "css", name), encoding="utf-8") as f:
            html = html.replace(link, f"<style>\n/* {name} */\n{f.read()}</style>")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(html)
    print("записан", out)


if __name__ == "__main__":
    main()
