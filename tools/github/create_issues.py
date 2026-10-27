# -*- coding: utf-8 -*-
"""Заводит метки, этапы и стартовые задачи из backlog.json в GitHub и пишет docs/backlog.md.

Нужен GitHub CLI (gh), вход: gh auth login.
    python tools/github/create_issues.py --md                       # только обновить docs/backlog.md
    python tools/github/create_issues.py --repo owner/name          # показать, что будет сделано
    python tools/github/create_issues.py --repo owner/name --apply  # создать в GitHub
"""
import argparse
import json
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
TEAMS = {"team:all": "Все", "team:auth": "Авторизация", "team:ref": "Справочники", "team:ops": "Бизнес-логика", "team:data": "Данные"}


def load():
    with open(os.path.join(HERE, "backlog.json"), encoding="utf-8") as f:
        return json.load(f)


def write_md(b):
    lines = ["# Стартовые задачи", "",
             "Сгенерировано из `tools/github/backlog.json` скриптом `tools/github/create_issues.py --md`.",
             "В GitHub задачи заводятся тем же скриптом с ключом `--apply`. Дальше работаем в Issues, этот файл не правим.", ""]
    for i, m in enumerate(b["milestones"]):
        lines += [f"## {m['title']} (до {m['due'][8:10]}.{m['due'][5:7]})", "", m["description"], ""]
        for team, name in TEAMS.items():
            items = [x for x in b["issues"] if x["m"] == i and team in x["labels"]]
            if not items:
                continue
            lines.append(f"**{name}**")
            lines.append("")
            for x in items:
                body = x["body"].splitlines()
                if body[0].startswith("- [ ]"):
                    text = "; ".join(l.replace("- [ ] ", "") for l in body) + "."
                else:
                    text = body[0]
                lines.append(f"- {x['title']}. {text}")
            lines.append("")
    path = os.path.join(ROOT, "docs", "backlog.md")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    print("записан", path)


def run(cmd, apply):
    print("  $", " ".join(cmd) if len(" ".join(cmd)) < 160 else " ".join(cmd)[:157] + "...")
    if apply:
        subprocess.run(cmd, check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", help="owner/name репозитория на GitHub")
    ap.add_argument("--apply", action="store_true", help="выполнить, а не только показать")
    ap.add_argument("--md", action="store_true", help="записать docs/backlog.md")
    args = ap.parse_args()
    b = load()
    if args.md:
        write_md(b)
    if not args.repo:
        return
    repo = ["--repo", args.repo]
    for lb in b["labels"]:
        run(["gh", "label", "create", lb["name"], "--color", lb["color"], "--description", lb["description"], "--force"] + repo, args.apply)
    for m in b["milestones"]:
        run(["gh", "api", f"repos/{args.repo}/milestones", "-f", f"title={m['title']}", "-f", f"due_on={m['due']}T20:00:00Z",
             "-f", f"description={m['description']}"], args.apply)
    for x in b["issues"]:
        cmd = ["gh", "issue", "create", "--title", x["title"], "--body", x["body"], "--milestone", b["milestones"][x["m"]]["title"]] + repo
        for lb in x["labels"]:
            cmd += ["--label", lb]
        run(cmd, args.apply)
    if not args.apply:
        print("\nЭто проверка. Чтобы создать в GitHub, добавьте --apply.")


if __name__ == "__main__":
    main()
