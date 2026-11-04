# -*- coding: utf-8 -*-
"""Генератор демонстрационных данных для Цифрового чек-листа (группа УВП-411).

Строит правдоподобное расписание оборотов в SVO на несколько суток, проигрывает
обслуживание до заданного момента «сейчас» и пишет:
  out/demo_data.sql          данные для базы (загружается после миграций Flyway)
  out/Тестовые данные.xlsx   те же данные таблицами для проверки глазами (если установлен openpyxl)

Запуск: python tools/demo-data/generate_demo_data.py --date 2026-10-27 --now 11:00
Загрузка: docker compose -f infra/docker-compose.yml exec -T db psql -U checklist -d checklist < tools/demo-data/out/demo_data.sql
"""
import argparse
import datetime as dt
import json
import os
import random
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------- справочники
# IATA, название, город, разница с Москвой в часах (только для расчёта местного времени рейсов),
# время в пути от/до SVO в минутах (примерное блочное время по открытому расписанию)
AIRPORTS = [
    ("SVO", "Шереметьево", "Москва", 0, 0),
    ("LED", "Пулково", "Санкт-Петербург", 0, 85),
    ("KGD", "Храброво", "Калининград", -1, 130),
    ("AER", "Сочи", "Сочи", 0, 140),
    ("MRV", "Минеральные Воды", "Минеральные Воды", 0, 135),
    ("MCX", "Уйташ", "Махачкала", 0, 150),
    ("KZN", "Казань", "Казань", 0, 95),
    ("GOJ", "Стригино", "Нижний Новгород", 0, 70),
    ("KUF", "Курумоч", "Самара", 1, 100),
    ("UFA", "Уфа", "Уфа", 2, 125),
    ("PEE", "Большое Савино", "Пермь", 2, 115),
    ("SVX", "Кольцово", "Екатеринбург", 2, 140),
    ("CEK", "Баландино", "Челябинск", 2, 135),
    ("TJM", "Рощино", "Тюмень", 2, 150),
    ("OMS", "Омск", "Омск", 3, 195),
    ("OVB", "Толмачёво", "Новосибирск", 4, 250),
    ("KJA", "Емельяново", "Красноярск", 4, 280),
    ("IKT", "Иркутск", "Иркутск", 5, 340),
    ("KHV", "Хабаровск", "Хабаровск", 7, 470),
    ("VVO", "Кневичи", "Владивосток", 7, 510),
    ("MMK", "Мурманск", "Мурманск", 0, 130),
    ("ARH", "Талаги", "Архангельск", 0, 115),
]
AP = {a[0]: a for a in AIRPORTS}
NEW_AIRPORTS = [a for a in AIRPORTS if a[0] not in ("SVO", "KGD", "LED")]   # SVO, KGD, LED уже есть в schema.sql

AIRLINES = {"SU": "Аэрофлот", "FV": "Россия"}
A320_DEST = {"LED": 6, "AER": 5, "KGD": 3, "MRV": 2, "MCX": 2, "KZN": 3, "GOJ": 1, "KUF": 2, "UFA": 2, "PEE": 2,
             "SVX": 3, "CEK": 2, "TJM": 1, "OMS": 1, "OVB": 2, "MMK": 2, "ARH": 1}
B777_DEST = {"VVO": 2, "KHV": 2, "IKT": 1, "AER": 3, "KJA": 1, "OVB": 2}

OPS = [  # код, название, порядок, A320, B777, обязательная
    ("STAIRS", "Подгон трапа", 1, 3, 5, True), ("DEBOARD", "Высадка пассажиров", 2, 10, 20, True),
    ("UNLOAD", "Выгрузка багажа", 3, 15, 25, True), ("INSPECT", "Технический осмотр", 4, 20, 30, True),
    ("WATER", "Санузлы и питьевая вода", 5, 10, 15, True), ("FUEL", "Заправка топливом", 6, 20, 35, True),
    ("CLEAN", "Уборка салона", 7, 15, 25, True), ("CATERING", "Загрузка бортпитания", 8, 10, 20, True),
    ("LOAD", "Загрузка багажа", 9, 15, 25, True), ("BOARD", "Посадка пассажиров", 10, 20, 30, True),
    ("DEICE", "Противообледенительная обработка", 11, 10, 15, False),
    ("CLOSE", "Уборка трапа, закрытие дверей", 12, 3, 5, True),
]
DEPS = {"DEBOARD": ["STAIRS"], "FUEL": ["DEBOARD"], "CLEAN": ["DEBOARD"], "CATERING": ["DEBOARD"],
        "LOAD": ["UNLOAD"], "BOARD": ["INSPECT", "FUEL", "CLEAN", "CATERING"], "DEICE": ["BOARD"],
        "CLOSE": ["LOAD", "BOARD", "DEICE"]}
DELAY_BY_OP = {"STAIRS": "34", "DEBOARD": "34", "UNLOAD": "32", "INSPECT": "41", "WATER": "34", "FUEL": "36",
               "CLEAN": "35", "CATERING": "37", "LOAD": "32", "BOARD": "15", "DEICE": "75", "CLOSE": "15"}
DELAY_NAMES = {"15": "Посадка пассажиров", "18": "Обработка багажа", "32": "Погрузка и выгрузка багажа",
               "33": "Погрузочное оборудование", "34": "Оборудование обслуживания (трап, спецмашины)",
               "35": "Уборка салона", "36": "Заправка топливом", "37": "Бортпитание",
               "41": "Техническая неисправность ВС", "75": "Противообледенительная обработка",
               "87": "Аэродромная инфраструктура, стоянка", "93": "Позднее прибытие ВС с предыдущего рейса"}
PROBLEMS = {
    "FUEL": ("Треснул заправочный шланг, ждём замену", "Шланг заменён, заправка продолжена"),
    "CATERING": ("Задерживается машина бортпитания", "Машина бортпитания прибыла"),
    "CLEAN": ("Нет бригады уборки, вызвана с соседней стоянки", "Бригада уборки прибыла"),
    "UNLOAD": ("Сломался багажный транспортёр", "Транспортёр заменён"),
    "LOAD": ("Не подвезли багаж с сортировки", "Багаж доставлен"),
    "WATER": ("Нет машины для обслуживания санузлов", "Машина прибыла"),
    "DEBOARD": ("Пассажир с ограниченной подвижностью, ждём амбулифт", "Амбулифт прибыл"),
    "INSPECT": ("Обнаружена течь гидравлики, вызван инженер", "Неисправность устранена инженером"),
    "STAIRS": ("Не подъехал трап", "Трап подан"),
}
STATUS_RU = {"planned": "Запланирован", "in_service": "Обслуживание", "ready": "Готов к вылету",
             "closed": "Закрыт", "cancelled": "Отменён"}
OP_STATUS_RU = {"not_started": "Не начата", "in_progress": "В работе", "problem": "Проблема",
                "done": "Выполнена", "not_required": "Не требуется"}

# Зарезервировано под сценарии ПМИ, генератор их не трогает
RESERVED_TAILS = {"RA-73725", "RA-89041"}
RESERVED_STANDS = {"124"}
RESERVED_NUMBERS = {"1001", "1002", "1416", "1417"}
# bcrypt-хеш пароля Demo-2026 (посчитан один раз, чтобы не зависеть от расширения pgcrypto)
DEMO_HASH = "$2b$10$EERvfJFdeUifetdXgLBivebLHYMmYiJOEB8U2cAHGPeGwQD9RusCy"

# ---------------------------------------------------------------- люди
M_LAST = ["Смирнов", "Попов", "Васильев", "Петров", "Соколов", "Михайлов", "Новиков", "Фёдоров", "Морозов",
          "Волков", "Алексеев", "Лебедев", "Семёнов", "Егоров", "Павлов", "Козлов", "Степанов", "Николаев",
          "Орлов", "Андреев", "Макаров", "Никитин", "Захаров", "Зайцев", "Соловьёв", "Борисов", "Яковлев",
          "Григорьев", "Романов", "Воробьёв"]
M_FIRST = ["Александр", "Сергей", "Андрей", "Дмитрий", "Алексей", "Максим", "Евгений", "Иван", "Михаил",
           "Артём", "Олег", "Павел", "Роман", "Николай", "Владимир", "Денис", "Игорь"]
M_MID = ["Александрович", "Сергеевич", "Андреевич", "Дмитриевич", "Алексеевич", "Викторович",
         "Николаевич", "Игоревич", "Олегович", "Владимирович", "Михайлович", "Павлович"]
F_FIRST = ["Анна", "Елена", "Ольга", "Мария", "Наталья", "Татьяна", "Ирина", "Екатерина", "Светлана"]
F_MID = ["Александровна", "Сергеевна", "Игоревна", "Викторовна", "Николаевна", "Андреевна", "Олеговна"]


def make_users(rng):
    users = [("sup01", "Петрова", "Анна", "Игоревна", "supervisor"),
             ("tech07", "Иванов", "Пётр", "Сергеевич", "technician"),
             ("tech12", "Кузнецов", "Дмитрий", "Олегович", "technician")]
    used = {(u[1], u[2]) for u in users}

    def person(female):
        while True:
            last = rng.choice(M_LAST)
            if female:
                last = last + "а"
                first, mid = rng.choice(F_FIRST), rng.choice(F_MID)
            else:
                first, mid = rng.choice(M_FIRST), rng.choice(M_MID)
            if (last, first) not in used:
                used.add((last, first))
                return last, first, mid

    for n in (2, 3):
        users.append(("sup%02d" % n,) + person(n == 3) + ("supervisor",))
    for n in range(1, 31):
        login = "tech%02d" % n
        if login in ("tech07", "tech12", "tech08"):   # tech08 создаётся в сценарии 7 ПМИ
            continue
        users.append((login,) + person(rng.random() < 0.15) + ("technician",))
    return users


def make_fleet(rng):
    fleet = [("RA-73711", "A320", "SU"), ("RA-73110", "A320", "SU")]
    used = {"RA-73711", "RA-73110"} | RESERVED_TAILS
    def tail(prefix):
        while True:
            t = "%s%03d" % (prefix, rng.randint(100, 999))
            if t not in used:
                used.add(t)
                return t
    fleet += [(tail("RA-73"), "A320", "SU") for _ in range(32)]
    fleet += [(tail("RA-73"), "A320", "FV") for _ in range(12)]
    fleet += [(tail("RA-89"), "B777", "SU") for _ in range(7)]
    return fleet


# ---------------------------------------------------------------- время
def r5(t):
    m = (t.minute // 5) * 5
    return t.replace(minute=m, second=0, microsecond=0)


def mins(n):
    return dt.timedelta(minutes=n)


def wchoice(rng, weights):
    keys = list(weights)
    return rng.choices(keys, [weights[k] for k in keys])[0]


# ---------------------------------------------------------------- расписание
def build_visits(rng, fleet, day0, last_day):
    visits = []
    end = dt.datetime.combine(last_day, dt.time(23, 0))
    for tail, typ, airline in fleet:
        dests = B777_DEST if typ == "B777" else A320_DEST
        t = r5(dt.datetime.combine(day0, dt.time(5, 30)) + mins(rng.randint(0, 420)))
        orig = wchoice(rng, dests)
        while t <= end:
            if typ == "B777":
                ground = rng.choice([110, 115, 120, 125, 130, 140]) if rng.random() > 0.08 else rng.choice([95, 100])
                out_ground = rng.choice([90, 100, 110, 120])
            else:
                ground = rng.choice([60, 65, 70, 75, 80, 90]) if rng.random() > 0.08 else rng.choice([50, 55])
                out_ground = rng.choice([45, 50, 55, 60])
            std = t + mins(ground)
            dest = wchoice(rng, dests)
            back = std + mins(2 * AP[dest][4] + out_ground)
            overnight = back.date() != std.date() or back.time() > dt.time(23, 0)
            if overnight:
                back = r5(dt.datetime.combine(std.date() + dt.timedelta(days=1), dt.time(5, 30)) + mins(rng.randint(0, 360)))
            else:
                back = r5(back + mins(4))
            visits.append(dict(tail=tail, type=typ, airline=airline, orig=orig, sta=t, std=std, dest=dest, back=back))
            t, orig = back, dest
    visits.sort(key=lambda v: v["sta"])
    return visits


class Numbers:
    """Номера рейсов: туда нечётный, обратно следующий чётный."""
    def __init__(self):
        self.base = {}
        self.used = set()
        self.counter = defaultdict(int)

    def _base(self, airline, typ, dest):
        key = (airline, typ, dest)
        if key not in self.base:
            start = {"SU": 1100, "FV": 6100}[airline] + (600 if typ == "B777" else 0)
            n = len([k for k in self.base if k[0] == airline and k[1] == typ])
            self.base[key] = start + 20 * n
        return self.base[key]

    def pair(self, airline, typ, dest, out_key_date, back_key_date):
        b = self._base(airline, typ, dest)
        k = self.counter[(airline, dest, out_key_date)]
        while True:
            n = b + 2 * k + 1
            out_key = (airline, str(n), out_key_date, "SVO", dest)
            back_key = (airline, str(n + 1), back_key_date, dest, "SVO")
            if str(n) not in RESERVED_NUMBERS and str(n + 1) not in RESERVED_NUMBERS \
                    and out_key not in self.used and back_key not in self.used:
                self.used |= {out_key, back_key}
                self.counter[(airline, dest, out_key_date)] = k + 1
                return str(n), str(n + 1)
            k += 1

    def single_back(self, airline, typ, orig, date):
        b = self._base(airline, typ, orig)
        k = 0
        while True:
            n = b + 2 * k + 2
            key = (airline, str(n), date, orig, "SVO")
            if str(n) not in RESERVED_NUMBERS and key not in self.used:
                self.used.add(key)
                return str(n)
            k += 1


def local(t, code):
    return t + dt.timedelta(hours=AP[code][3])


def allocate(visits, rng):
    stands = {str(s): dt.datetime.min for s in range(120, 151) if str(s) not in RESERVED_STANDS}
    for v in visits:
        lo, hi = v["sta"] - mins(10), v["std"] + mins(15)
        wide = [s for s in stands if int(s) >= 141]
        narrow = [s for s in stands if int(s) < 141]
        pools = [wide] if v["type"] == "B777" else [narrow, wide]
        v["stand"] = None
        for pool in pools:
            free = [s for s in pool if stands[s] <= lo]
            if free:
                s = rng.choice(free)
                stands[s] = hi
                v["stand"] = s
                break
    return [v for v in visits if v["stand"]]


def assign_techs(visits, users, rng):
    techs = [u[0] for u in users if u[4] == "technician"]
    half = len(techs) // 2
    shift_a, shift_b = set(techs[:half]), set(techs[half:])
    busy = {t: dt.datetime.min for t in techs}
    load = defaultdict(int)
    out = []
    for v in visits:
        lo, hi = v["sta"] - mins(15), v["std"] + mins(20)
        h = v["sta"].hour + v["sta"].minute / 60
        pref = shift_a if 5.5 <= h < 14 else shift_b if h >= 17.5 or h < 1 else shift_a | shift_b
        for pool in (pref, set(techs)):
            free = [t for t in pool if busy[t] <= lo]
            if free:
                t = min(free, key=lambda x: (load[(x, v["sta"].date())], rng.random()))
                busy[t] = hi
                load[(t, v["sta"].date())] += 1
                v["tech"] = t
                out.append(v)
                break
    return out


# ---------------------------------------------------------------- проигрывание оборота
def plan(base, typ, skip_deice):
    col = 3 if typ == "A320" else 4
    p = {}
    for op in OPS:
        code = op[0]
        if code == "DEICE" and skip_deice:
            continue
        preds = [x for x in DEPS.get(code, []) if x in p]
        start = max([p[x][1] for x in preds], default=base)
        p[code] = (start, start + mins(op[col]))
    if skip_deice:
        p["DEICE"] = (p["BOARD"][1], p["BOARD"][1] + mins(OPS[10][col]))
    return p


def secs(rng):
    return dt.timedelta(seconds=rng.randint(0, 59))


def simulate(v, seed, forced=None):
    rng = random.Random(seed * 100003 + v["id"])
    col = 3 if v["type"] == "A320" else 4
    r = rng.random()
    d_arr = rng.randint(20, 60) if r < 0.07 else max(-10, min(15, int(rng.gauss(0, 6))))
    on_block = v["sta"] + mins(d_arr) + secs(rng)
    deice = rng.random() < (0.15 if v["sta"].hour < 9 else 0.03)
    # план от следующей полной минуты после постановки: «постановка 10:02:31» даёт план с 10:03
    p = plan(on_block.replace(second=0) + (mins(1) if on_block.second else dt.timedelta(0)), v["type"], not deice)
    act = {}
    for op in OPS:
        code, norm = op[0], op[col]
        f = rng.uniform(1.2, 1.5) if rng.random() < 0.06 else max(0.85, min(1.12, rng.gauss(0.94, 0.06)))
        has_problem = rng.random() < 0.025 and code in PROBLEMS
        frac, plen = rng.uniform(0.2, 0.7), rng.randint(10, 25)
        # обычно операцию начинают сразу, иногда ждут людей или машину 1–2 минуты
        wait = mins(rng.choice([1, 2])) + secs(rng) if rng.random() < 0.15 else dt.timedelta(seconds=rng.randint(0, 20))
        if code == "DEICE" and not deice:
            continue
        preds = [x for x in DEPS.get(code, []) if x in act]
        start = max([act[x]["end"] for x in preds], default=on_block) + wait
        dur = mins(norm * f)
        prob = None
        if forced and code == forced[0]:
            ps = max(forced[1], start + mins(1))
            prob = (ps, ps + mins(forced[2]))
        elif has_problem:
            ps = start + dur * frac
            prob = (ps, ps + mins(plen))
        end = start + dur + (prob[1] - prob[0] if prob else dt.timedelta(0))
        act[code] = dict(start=start, end=end, problem=prob, overrun=(end - start) - mins(norm))
    for code in act:   # причина отставания
        a = act[code]
        lag = (a["end"] - p[code][1]).total_seconds() / 60
        if lag > 10:
            if a["overrun"] >= mins(5):
                a["code"] = DELAY_BY_OP[code]
            else:
                preds = [x for x in DEPS.get(code, []) if x in act]
                cause = max(preds, key=lambda x: act[x]["end"]) if preds else None
                a["code"] = act[cause].get("code") or DELAY_BY_OP[cause] if cause else DELAY_BY_OP[code]
    mand_end = max(a["end"] for c, a in act.items() if c != "DEICE" or deice)
    ready = mand_end + mins(rng.randint(1, 4)) + secs(rng)
    off = max(v["std"], ready + mins(rng.randint(2, 5))) + mins(rng.randint(0, 3)) + secs(rng)
    return dict(on_block=on_block, deice=deice, plan=p, act=act, ready=ready, off=off)


def state_at(v, s, now):
    """Статус оборота и операций на момент now."""
    if v.get("cancelled"):
        return "cancelled", {c: ("not_required" if c == "DEICE" and not s["deice"] else "not_started") for c, *_ in OPS}
    if s["off"] <= now:
        st = "closed"
    elif s["on_block"] > now:
        st = "planned"
    elif s["ready"] <= now:
        st = "ready"
    else:
        st = "in_service"
    ops = {}
    for code, *_ in OPS:
        if code == "DEICE" and not s["deice"]:
            ops[code] = "not_required"
            continue
        a = s["act"][code]
        if st == "planned" or a["start"] > now:
            ops[code] = "not_started"
        elif a["end"] <= now:
            ops[code] = "done"
        elif a["problem"] and a["problem"][0] <= now < a["problem"][1]:
            ops[code] = "problem"
        else:
            ops[code] = "in_progress"
    return st, ops


# ---------------------------------------------------------------- SQL
def q(x):
    if x is None:
        return "NULL"
    if isinstance(x, bool):
        return "true" if x else "false"
    if isinstance(x, (int, float)):
        return str(x)
    if isinstance(x, dt.datetime):
        return "'%s'" % x.strftime("%Y-%m-%d %H:%M:%S")
    if isinstance(x, dt.date):
        return "'%s'" % x.isoformat()
    if isinstance(x, (dict, list)):
        return "'%s'::jsonb" % json.dumps(x, ensure_ascii=False).replace("'", "''")
    return "'%s'" % str(x).replace("'", "''")


def values(rows, chunk=400):
    for i in range(0, len(rows), chunk):
        yield ",\n".join("    (" + ", ".join(q(c) for c in r) + ")" for r in rows[i:i + chunk])


def iso(t):
    return t.strftime("%Y-%m-%dT%H:%M:%S") if t else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", default=dt.date.today().isoformat(), help="день демонстрации (последний день набора), по умолчанию сегодня")
    ap.add_argument("--now", default="11:00", help="момент «сейчас» в день демонстрации")
    ap.add_argument("--days", type=int, default=3, help="сколько суток, включая день демонстрации")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    main_day = dt.date.fromisoformat(args.date)
    day0 = main_day - dt.timedelta(days=args.days - 1)
    now = dt.datetime.combine(main_day, dt.time.fromisoformat(args.now))

    users = make_users(rng)
    fleet = make_fleet(rng)
    visits = build_visits(rng, fleet, day0, main_day)
    visits = [v for v in visits if day0 <= v["sta"].date() <= main_day]
    visits = assign_techs(allocate(visits, rng), users, rng)

    # рейсы
    nums = Numbers()
    prev_dep = {}
    flights = []
    fid = 0
    def add_flight(airline, num, dep, arr, std_svo, block):
        nonlocal fid
        fid += 1
        std_l = local(std_svo, dep)
        sta_l = local(std_svo + mins(block), arr)
        flights.append(dict(id=fid, airline=airline, num=num, date=std_l.date(), dep=dep, arr=arr,
                            std=std_l, sta=sta_l, atd=None, ata=None, block=block))
        return fid
    for i, v in enumerate(visits, 1):
        v["id"] = i
        block_in, block_out = AP[v["orig"]][4], AP[v["dest"]][4]
        key = v["tail"]
        if key in prev_dep and prev_dep[key][1] == v["orig"]:
            arr_num = prev_dep[key][0]
        else:
            arr_num = nums.single_back(v["airline"], v["type"], v["orig"], local(v["sta"] - mins(block_in), v["orig"]).date())
        v["arr_id"] = add_flight(v["airline"], arr_num, v["orig"], "SVO", v["sta"] - mins(block_in), block_in)
        back_date = local(v["back"] - mins(block_out), v["dest"]).date()
        out_num, back_num = nums.pair(v["airline"], v["type"], v["dest"], v["std"].date(), back_date)
        v["dep_id"] = add_flight(v["airline"], out_num, "SVO", v["dest"], v["std"], block_out)
        prev_dep[key] = (back_num, v["dest"])

    # отмены: по одной в сутки
    by_day = defaultdict(list)
    for v in visits:
        by_day[v["sta"].date()].append(v)
    for day, vs in by_day.items():
        cand = [v for v in vs if (v["sta"] > now + mins(60)) == (day == main_day)] or vs
        c = rng.choice(cand)
        c["cancelled"] = True
        c["cancel_reason"] = rng.choice(["Отмена рейса вылета авиакомпанией", "Замена ВС, оборот создан заново на другой борт"])

    sims = {v["id"]: simulate(v, args.seed) for v in visits}
    # хотя бы одна открытая проблема в момент «сейчас»
    states = {v["id"]: state_at(v, sims[v["id"]], now) for v in visits}
    if not any("problem" in ops.values() for st, ops in states.values()):
        for v in visits:
            st, ops = states[v["id"]]
            running = [c for c, s in ops.items() if s == "in_progress" and c in ("FUEL", "CATERING", "CLEAN", "LOAD", "UNLOAD", "WATER")]
            if st == "in_service" and running:
                sims[v["id"]] = simulate(v, args.seed, forced=(running[0], now - mins(6), 22))
                states[v["id"]] = state_at(v, sims[v["id"]], now)
                if "problem" in states[v["id"]][1].values():
                    break

    sup_on = lambda t: "sup01" if 6 <= t.hour < 18 else "sup02"
    turn_rows, exec_rows, dep_rows, log_rows = [], [], [], []
    xl_turns, xl_ops, xl_log = [], [], []
    eid = 0
    first_action = {}
    fmap = {f["id"]: f for f in flights}

    def log(t, login, typ, entity, ent_id, tid, old, new):
        if t <= now:
            log_rows.append((t, login, typ, entity, ent_id, tid, old, new))
            k = (login, t.date())
            if k not in first_action or t < first_action[k]:
                first_action[k] = t

    for v in visits:
        s, (st, ops) = sims[v["id"]], states[v["id"]]
        tid, tech = v["id"], v["tech"]
        fa, fd = fmap[v["arr_id"]], fmap[v["dep_id"]]
        if st != "planned" and st != "cancelled":
            fa["ata"] = s["on_block"]
            fa["atd"] = local(s["on_block"] - mins(fa["block"]), fa["dep"])
        if st == "closed":
            fd["atd"] = s["off"]
            if s["off"] + mins(fd["block"]) <= now:
                fd["ata"] = local(s["off"] + mins(fd["block"]), fd["arr"])
        base_plan = s["plan"] if st in ("in_service", "ready", "closed") else plan(v["sta"], v["type"], not s["deice"])
        on_b = s["on_block"] if st in ("in_service", "ready", "closed") else None
        ready = s["ready"] if st in ("ready", "closed") else None
        off = s["off"] if st == "closed" else None
        turn_rows.append((tid, v["tail"], v["stand"], v["arr_id"], v["dep_id"], tech, st, on_b, ready, off, v.get("cancel_reason")))
        created = dt.datetime.combine(v["sta"].date() - dt.timedelta(days=1), dt.time(19, 0)) + mins(rng.randint(0, 150)) + secs(rng)
        log(created, "sup02", "flight_created", "flight", v["arr_id"], None, None, {"flight": f"{v['airline']} {fa['num']}"})
        log(created + dt.timedelta(seconds=40), "sup02", "flight_created", "flight", v["dep_id"], None, None, {"flight": f"{v['airline']} {fd['num']}"})
        log(created + mins(1), "sup02", "turnaround_created", "turnaround", tid, tid, None,
            {"status": "planned", "aircraft": v["tail"], "stand": v["stand"], "technician": tech})
        if v.get("cancelled"):
            ct = min(now - mins(30), v["sta"] - mins(90)) if v["sta"].date() == main_day else v["sta"] - mins(rng.randint(60, 180))
            log(ct + secs(rng), sup_on(ct), "turnaround_cancelled", "turnaround", tid, tid,
                {"status": "planned"}, {"status": "cancelled", "cancelReason": v["cancel_reason"]})
        if on_b:
            log(on_b, tech, "on_block", "turnaround", tid, tid, {"status": "planned"}, {"status": "in_service", "onBlockAt": iso(on_b)})
        ops_ids = {}
        col = 3 if v["type"] == "A320" else 4
        for op in OPS:
            code = op[0]
            eid += 1
            ops_ids[code] = eid
            ost = ops[code]
            a = s["act"].get(code)
            pst, pend = base_plan[code]
            ast = a["start"] if a and ost in ("in_progress", "problem", "done") else None
            aen = a["end"] if a and ost == "done" else None
            dcode = a.get("code") if a and ost == "done" else None
            comment = None
            if a and a["problem"] and a["problem"][0] <= now and ost in ("problem", "in_progress", "done"):
                comment = PROBLEMS[code][0] if ost == "problem" else PROBLEMS[code][1]
            executor = tech if ost in ("in_progress", "problem", "done") else None
            exec_rows.append((eid, tid, code, v["type"], op[2], op[col], op[5], ost, pst, pend, ast, aen, executor, dcode, comment))
            xl_ops.append((tid, op[2], op[1], OP_STATUS_RU[ost], pst, pend, ast, aen,
                           round((aen - pend).total_seconds() / 60) if aen else None, dcode, comment))
            if code == "DEICE" and not s["deice"] and st != "cancelled":
                log(created + mins(2), "sup02", "op_not_required", "operation_exec", eid, tid, {"status": "not_started"}, {"status": "not_required"})
            if ast:
                log(ast, tech, "op_start", "operation_exec", eid, tid, {"status": "not_started"}, {"status": "in_progress", "actualStart": iso(ast)})
            if a and a["problem"] and ast:
                p0, p1 = a["problem"]
                log(p0, tech, "op_problem", "operation_exec", eid, tid, {"status": "in_progress"}, {"status": "problem", "comment": PROBLEMS[code][0]})
                log(p1, sup_on(p1), "op_resolve", "operation_exec", eid, tid, {"status": "problem"}, {"status": "in_progress", "comment": PROBLEMS[code][1]})
            if aen:
                new = {"status": "done", "actualEnd": iso(aen)}
                if dcode:
                    new["delayCode"] = dcode
                log(aen, tech, "op_finish", "operation_exec", eid, tid, {"status": "in_progress"}, new)
        for code, preds in DEPS.items():
            for pcode in preds:
                dep_rows.append((ops_ids[code], ops_ids[pcode]))
        if ready:
            log(ready, sup_on(ready), "ready", "turnaround", tid, tid, {"status": "in_service"}, {"status": "ready", "readyAt": iso(ready)})
        if off:
            log(off, tech, "off_block", "turnaround", tid, tid, {"status": "ready"}, {"status": "closed", "offBlockAt": iso(off)})
        risk = max(e[1] for c, e in base_plan.items() if not (c == "DEICE" and not s["deice"])) > v["std"]
        codes = sorted({r[13] for r in exec_rows[-12:] if r[13]})
        xl_turns.append((tid, v["sta"].date(), f"{v['airline']} {fa['num']} {fa['dep']}–SVO", v["sta"],
                         f"{v['airline']} {fd['num']} SVO–{fd['arr']}", v["std"], v["tail"], v["type"], v["stand"], tech,
                         STATUS_RU[st], on_b, ready, off,
                         max(0, round((off - v["std"]).total_seconds() / 60)) if off else None,
                         "да" if risk and st in ("planned", "in_service") else "",
                         sum(1 for x in ops.values() if x == "problem") or "", ", ".join(codes), v.get("cancel_reason") or ""))

    for (login, day), t in first_action.items():
        log_rows.append((t - mins(random.Random(hash((login, str(day))) % 1000).randint(3, 10)), login, "login", "user", None, None, None, None))
    log_rows.sort(key=lambda r: r[0])

    # ---------------------------------------------------------------- запись SQL
    out = []
    w = out.append
    w("-- Демонстрационные данные. Сгенерировано generate_demo_data.py")
    w(f"-- Дни: {day0} .. {main_day}, момент «сейчас»: {now:%Y-%m-%d %H:%M}, seed {args.seed}")
    w("-- Загружается после 01_schema.sql. Пароль всех демонстрационных пользователей: Demo-2026")
    w("-- Не используются (оставлены для сценариев ПМИ): борта RA-73725, RA-89041, стоянка 124, рейсы SU 1001, 1002, 1416, 1417, логин tech08\n")
    w("BEGIN;")
    w("INSERT INTO ref.airport (iata_code, name, city) VALUES")
    w(next(values([(a[0], a[1], a[2]) for a in NEW_AIRPORTS], 999)) + ";\n")
    w("INSERT INTO ref.aircraft (tail_number, aircraft_type_id, airline_id)\nSELECT v.tail, t.id, l.id FROM (VALUES")
    w(next(values([f for f in fleet if f[0] not in ("RA-73711", "RA-73110")], 999)))
    w(") AS v (tail, type_code, airline_code)\nJOIN ref.aircraft_type t ON t.code = v.type_code\nJOIN ref.airline l ON l.iata_code = v.airline_code;\n")
    w("INSERT INTO auth.app_user (login, password_hash, last_name, first_name, middle_name, must_change_password) VALUES")
    w(",\n".join("    (%s, %s, %s, %s, %s, false)" % (q(u[0]), q(DEMO_HASH), q(u[1]), q(u[2]), q(u[3])) for u in users) + ";\n")
    w("INSERT INTO auth.user_role (user_id, role_id)\nSELECT u.id, r.id FROM (VALUES")
    w(next(values([(u[0], u[4]) for u in users], 999)))
    w(") AS v (login, role_code)\nJOIN auth.app_user u ON u.login = v.login\nJOIN auth.role r ON r.code = v.role_code;\n")
    for chunk in values([(f["id"], f["airline"], f["num"], f["date"], f["dep"], f["arr"], f["std"], f["sta"], f["atd"], f["ata"]) for f in flights]):
        w("INSERT INTO ops.flight (id, airline_id, flight_number, flight_date, dep_airport_id, arr_airport_id, std, sta, atd, ata)")
        w("SELECT v.id, al.id, v.num, v.fdate::date, d.id, a.id, v.std::timestamp, v.sta::timestamp, v.atd::timestamp, v.ata::timestamp FROM (VALUES")
        w(chunk)
        w(") AS v (id, airline, num, fdate, dep, arr, std, sta, atd, ata)\nJOIN ref.airline al ON al.iata_code = v.airline\n"
          "JOIN ref.airport d ON d.iata_code = v.dep\nJOIN ref.airport a ON a.iata_code = v.arr;\n")
    for chunk in values(turn_rows):
        w("INSERT INTO ops.turnaround (id, aircraft_id, stand_id, arr_flight_id, dep_flight_id, technician_id, status, on_block_at, ready_at, off_block_at, cancel_reason)")
        w("SELECT v.id, ac.id, s.id, v.arr_id, v.dep_id, u.id, v.status, v.on_block::timestamp, v.ready::timestamp, v.off_block::timestamp, v.reason FROM (VALUES")
        w(chunk)
        w(") AS v (id, tail, stand, arr_id, dep_id, tech, status, on_block, ready, off_block, reason)\n"
          "JOIN ref.aircraft ac ON ac.tail_number = v.tail\n"
          "JOIN ref.stand s ON s.code = v.stand AND s.airport_id = (SELECT id FROM ref.airport WHERE iata_code = 'SVO')\n"
          "JOIN auth.app_user u ON u.login = v.tech;\n")
    for chunk in values(exec_rows):
        w("INSERT INTO ops.operation_exec (id, turnaround_id, operation_id, norm_id, seq_no, duration_min, is_mandatory, status,"
          " planned_start, planned_end, actual_start, actual_end, executor_id, delay_code_id, comment)")
        w("SELECT v.id, v.tid, o.id, n.id, v.seq, v.dur, v.mand, v.status, v.ps::timestamp, v.pe::timestamp, v.ast::timestamp,"
          " v.aen::timestamp, u.id, dc.id, v.comment FROM (VALUES")
        w(chunk)
        w(") AS v (id, tid, op, type, seq, dur, mand, status, ps, pe, ast, aen, executor, dcode, comment)\n"
          "JOIN ref.operation o ON o.code = v.op\nJOIN ref.aircraft_type t ON t.code = v.type\n"
          "JOIN ref.operation_norm n ON n.operation_id = o.id AND n.aircraft_type_id = t.id\n"
          "LEFT JOIN auth.app_user u ON u.login = v.executor\nLEFT JOIN ref.delay_code dc ON dc.code = v.dcode;\n")
    for chunk in values(dep_rows, 1000):
        w("INSERT INTO ops.exec_dependency (exec_id, prev_exec_id) VALUES")
        w(chunk + ";\n")
    # журнал разбит на секции по месяцам: создаём секции месяцев, за которые пишем записи
    for month in sorted({r[0].strftime("%Y-%m-01") for r in log_rows}):
        w(f"SELECT log.create_month_partition('{month}');")
    for chunk in values(log_rows):
        w("INSERT INTO log.action_log (action_time, user_id, login, action_type, entity, entity_id, turnaround_id, old_value, new_value)")
        w("SELECT v.t::timestamp, u.id, v.login, v.typ, v.entity, v.eid, v.tid, v.old, v.new FROM (VALUES")
        w(chunk)
        w(") AS v (t, login, typ, entity, eid, tid, old, new)\nJOIN auth.app_user u ON u.login = v.login;\n")
    for tbl in ("ops.flight", "ops.turnaround", "ops.operation_exec"):
        w(f"SELECT setval(pg_get_serial_sequence('{tbl}', 'id'), (SELECT max(id) FROM {tbl}));")
    w("\nCOMMIT;")
    sql_path = os.path.join(HERE, "out", "demo_data.sql")
    os.makedirs(os.path.dirname(sql_path), exist_ok=True)
    with open(sql_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(out) + "\n")

    # ---------------------------------------------------------------- Excel для проверки
    try:
        from openpyxl import Workbook
        from openpyxl.styles import Font, Alignment
    except ImportError:
        print("openpyxl не установлен, Excel не создан (pip install openpyxl)")
        print(sql_path)
        return
    wb = Workbook()
    ws = wb.active
    ws.title = "Описание"
    cnt = defaultdict(int)
    for r in turn_rows:
        if r[0] and next(v for v in visits if v["id"] == r[0])["sta"].date() == main_day:
            cnt[r[6]] += 1
    text = [
        "Демонстрационные данные: Цифровой чек-лист подготовки ВС к вылету, группа УВП-411",
        "",
        f"Период: {day0:%d.%m.%Y} – {main_day:%d.%m.%Y}. Момент «сейчас» в последний день: {now:%H:%M}. Базовый аэропорт SVO.",
        f"Оборотов всего: {len(visits)}, рейсов: {len(flights)}, операций: {len(exec_rows)}, записей журнала: {len(log_rows)}.",
        "Обороты в последний день по статусам: " + ", ".join(f"{STATUS_RU[k]} {cnt[k]}" for k in STATUS_RU if cnt[k]) + ".",
        "",
        "Что взято из открытых источников",
        "Коды IATA, названия и города аэропортов, коды авиакомпаний, типы ВС, стандартные коды задержек IATA (AHM 730).",
        "Время в пути до SVO и разница во времени с Москвой: примерные значения по открытым расписаниям.",
        "",
        "Что сгенерировано",
        "Бортовые номера, номера рейсов, расписание, люди, ход обслуживания. Совпадение с реальными бортами и рейсами случайно.",
        "Нормативы операций учебные (те же, что в schema.sql и ПМИ). Реальные графики обслуживания Заказчик передавать не может.",
        "",
        "Правила генерации",
        "Каждый борт летает по цепочке SVO – город – SVO. Ночует в последнем городе дня и прилетает обратно на следующий день между 5:30 и 11:30.",
        "Время на земле в SVO: A320 60–90 минут, B777 110–140 минут. Примерно в 8 % оборотов время сокращено (50–55 и 95–100 минут), там возникает риск задержки.",
        "B777 ставится только на стоянки 141–150, A320 на 120–140 (при нехватке на 141–150).",
        "Номер рейса туда нечётный, обратно следующий чётный. Время рейса указано по местному времени аэропорта вылета и прилёта.",
        "Прилёт: обычно ±10 минут от расписания, примерно 7 % рейсов опаздывают на 20–60 минут.",
        "План считается от следующей полной минуты после постановки ВС. Операцию начинают сразу после предыдущих, примерно в 15 % случаев ждут 1–2 минуты.",
        "Операции: длительность в пределах −15…+12 % от норматива, примерно 6 % операций дольше на 20–50 %, около 2,5 % операций с проблемой на 10–25 минут. В срок заканчивается около 2/3 операций.",
        "Противообледенительная обработка нужна примерно в 15 % утренних оборотов (до 9:00) и в 3 % остальных. В остальных супервайзер отмечает «Не требуется».",
        "Код задержки ставится при отставании больше 10 минут: по своей операции, а если она задержалась из-за предыдущей, то по причине предыдущей.",
        "Время прилёта ATA равно времени постановки, время вылета ATD равно времени отправления ВС.",
        "Одна отмена оборота в сутки. Если в момент «сейчас» нет открытой проблемы, генератор создаёт одну.",
        "",
        "Не используются (оставлены для сценариев ПМИ): борта RA-73725 и RA-89041, стоянка 124, рейсы SU 1001, 1002, 1416, 1417, логин tech08.",
        "Пароль всех демонстрационных пользователей: Demo-2026.",
    ]
    for line in text:
        ws.append([line])
    ws["A1"].font = Font(bold=True, size=13)
    for row in (7, 11, 15):
        ws.cell(row=row, column=1).font = Font(bold=True)
    ws.column_dimensions["A"].width = 160

    def sheet(title, header, rows, widths=None):
        s = wb.create_sheet(title)
        s.append(header)
        for c in s[1]:
            c.font = Font(bold=True)
            c.alignment = Alignment(wrap_text=True, vertical="top")
        for r in rows:
            s.append([x.strftime("%d.%m.%Y %H:%M") if isinstance(x, dt.datetime) else
                      x.strftime("%d.%m.%Y") if isinstance(x, dt.date) else x for x in r])
        s.freeze_panes = "A2"
        for i, w_ in enumerate(widths or [], 1):
            s.column_dimensions[s.cell(row=1, column=i).column_letter].width = w_
        return s

    sheet("Аэропорты", ["IATA", "Название", "Город", "Разница с Москвой, ч", "В пути от SVO, мин"],
          [(a[0], a[1], a[2], a[3], a[4] or "") for a in AIRPORTS], [8, 22, 22, 14, 14])
    sheet("Авиакомпании", ["IATA", "Название"], [("SU", "Аэрофлот"), ("FV", "Россия"), ("DP", "Победа")], [8, 20])
    sheet("Типы ВС", ["Код", "Название"], [("A320", "Airbus A320"), ("B777", "Boeing 777-300ER")], [8, 22])
    sheet("Борта", ["Бортовой номер", "Тип", "Авиакомпания"],
          [("RA-73725", "A320", "SU"), ("RA-89041", "B777", "SU")] + [f for f in fleet], [16, 8, 12])
    sheet("Стоянки", ["Аэропорт", "Стоянка", "Для каких ВС в генераторе"],
          [("SVO", str(s), "B777, A320" if s >= 141 else ("резерв ПМИ" if s == 124 else "A320")) for s in range(120, 151)], [10, 10, 26])
    sheet("Нормативы", ["№", "Код", "Операция", "A320, мин", "B777, мин", "Обязательная", "Начинать после"],
          [(o[2], o[0], o[1], o[3], o[4], "да" if o[5] else "нет",
            ", ".join(str(next(x[2] for x in OPS if x[0] == d_)) for d_ in DEPS.get(o[0], [])) or "постановки ВС") for o in OPS],
          [5, 10, 32, 10, 10, 12, 34])
    sheet("Коды задержек", ["Код", "Причина"], sorted(DELAY_NAMES.items()), [8, 44])
    sheet("Пользователи", ["Логин", "Фамилия", "Имя", "Отчество", "Роль"],
          [(u[0], u[1], u[2], u[3], "Супервайзер" if u[4] == "supervisor" else "Техник") for u in users], [10, 16, 14, 18, 14])
    sheet("Рейсы", ["№", "Авиакомпания", "Номер", "Дата", "Откуда", "Куда", "STD (местное)", "STA (местное)", "ATD", "ATA"],
          [(f["id"], f["airline"], f["num"], f["date"], f["dep"], f["arr"], f["std"], f["sta"], f["atd"], f["ata"]) for f in flights],
          [6, 12, 8, 11, 8, 8, 16, 16, 16, 16])
    sheet("Обороты", ["№", "Дата", "Рейс прилёта", "STA", "Рейс вылета", "STD", "Борт", "Тип", "Стоянка", "Техник", "Статус",
                      "Постановка", "Готовность", "Отправление", "Задержка вылета, мин", "Риск задержки", "Проблем сейчас",
                      "Коды задержек", "Причина отмены"], xl_turns,
          [6, 11, 20, 16, 20, 16, 11, 7, 8, 9, 15, 16, 16, 16, 10, 9, 9, 12, 30])
    sheet(f"Операции {main_day:%d.%m}", ["Оборот", "№", "Операция", "Статус", "План начало", "План конец", "Факт начало",
                                       "Факт конец", "Отставание, мин", "Код задержки", "Комментарий"],
          [r for r in xl_ops if next(v for v in visits if v["id"] == r[0])["sta"].date() == main_day],
          [8, 4, 30, 13, 16, 16, 16, 16, 10, 10, 44])
    sheet(f"Журнал {main_day:%d.%m}", ["Время", "Пользователь", "Действие", "Объект", "id", "Оборот", "Было", "Стало"],
          [(r[0], r[1], r[2], r[3], r[4], r[5], json.dumps(r[6], ensure_ascii=False) if r[6] else "",
            json.dumps(r[7], ensure_ascii=False) if r[7] else "") for r in log_rows if r[0].date() == main_day],
          [16, 10, 18, 14, 8, 8, 30, 60])
    xl_path = os.path.join(HERE, "out", "Тестовые данные.xlsx")
    os.makedirs(os.path.dirname(xl_path), exist_ok=True)
    wb.save(xl_path)
    print(f"обороты {len(visits)}, рейсы {len(flights)}, операции {len(exec_rows)}, журнал {len(log_rows)}")
    print("последний день:", dict(cnt))
    print(sql_path)
    print(os.path.abspath(xl_path))


if __name__ == "__main__":
    main()
