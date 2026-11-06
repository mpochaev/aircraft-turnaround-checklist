# Цифровой чек-лист подготовки ВС к вылету

Учебный проект группы УВП-411 РУТ (МИИТ), дисциплина «Проектная деятельность».

Система для учёта наземного обслуживания самолёта на стоянке в оборотном рейсе. Техник отмечает на планшете постановку ВС, начало и окончание операций и отправление. Супервайзер в одной таблице видит ход обслуживания всех бортов, отставания и проблемы. Требования лежат в [docs/specs](docs/specs): ЧТЗ, ПМИ, спецификация API, модель данных, макеты, описание архитектуры.

## С чего начать

1. Прочитайте задание своей команды в [docs/teams](docs/teams/README.md): что делаете, где ваш код, план по неделям, что берёте у других команд.
2. Посмотрите [общий план](docs/plan.md): что каждая команда показывает на паре и кто от кого что ждёт.
3. Склонируйте репозиторий и запустите проект у себя (раздел «Как запустить» ниже).
4. Работайте в своей ветке и вливайте её пулреквестом, правила в [CONTRIBUTING.md](CONTRIBUTING.md).

```bash
git clone https://github.com/mpochaev/aircraft-turnaround-checklist.git
```

В IntelliJ IDEA откройте папку проекта, Maven подтянет зависимости сам. Готовые конфигурации запуска лежат в `.run`: «Приложение (dev, без Keycloak)», «Приложение (Keycloak)» и «Сборка и тесты (mvn verify)». Пример готового метода от контроллера до теста на настоящей базе: модуль `log` (`LogController`, `LogQueries`, `LogApiTest`).

## Стек

| Что | Чем |
|---|---|
| Сервер | Java 21, Spring Boot 4.1, монолит из четырёх модулей (Spring Modulith) |
| База данных | PostgreSQL 16, миграции Flyway, доступ через JdbcClient |
| Вход и роли | Keycloak 26 (токен в заголовке `Authorization: Bearer`) |
| Страницы | HTML, CSS и JavaScript без сборки, общая дизайн-система в `src/main/resources/static` |
| Окружение | Docker Compose: PostgreSQL, Keycloak, Adminer |

Почему так, написано в [docs/adr](docs/adr).

## Как запустить

Нужны JDK 21 и Docker Desktop. Docker нужен и для тестов: `./mvnw verify` поднимает PostgreSQL в контейнере.

```bash
cp .env.example .env
docker compose -f infra/docker-compose.yml --env-file .env up -d   # база, Keycloak, Adminer
./mvnw spring-boot:run                                              # приложение на http://localhost:8080
```

На Windows вместо `./mvnw` пишите `mvnw.cmd`.

При первом запуске Flyway создаёт схему базы и справочники из `src/main/resources/db/migration`.

**Без Keycloak.** Для работы над страницами и API Keycloak не обязателен: профиль `dev` пускает без входа от имени sup01 (супервайзер).

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Другого пользователя в режиме dev можно подставить заголовками `X-Dev-User: tech07` и `X-Dev-Roles: technician`.

**Демонстрационные данные.** Это трое суток работы SVO, около 150 оборотов в сутки. Загружаются после первого запуска приложения:

```bash
python tools/demo-data/generate_demo_data.py --now 11:00
docker compose -f infra/docker-compose.yml exec -T db psql -U checklist -d checklist < tools/demo-data/out/demo_data.sql
```

### Адреса

| Что | Адрес | Вход |
|---|---|---|
| Приложение | http://localhost:8080 | sup01, tech07, tech12, sup02; пароль `Demo-2026` |
| Описание API (Swagger) | http://localhost:8080/swagger | |
| Дизайн-система | http://localhost:8080/styleguide.html | |
| Keycloak (администрирование) | http://localhost:8180/admin | admin / admin |
| Adminer (просмотр базы) | http://localhost:8181 | сервер `db`, база, логин и пароль `checklist` |

Токен для Postman (клиент `checklist-tools`):

```bash
curl -d "grant_type=password&client_id=checklist-tools&username=tech07&password=Demo-2026" \
  http://localhost:8180/realms/checklist/protocol/openid-connect/token
```

## Структура

```
src/main/java/ru/miit/uvp411/checklist/
  auth/      Авторизация       команда «Авторизация»     схема auth
  ref/       Справочники       команда «Справочники»     схема ref
  ops/       Обороты           команда «Бизнес-логика»   схема ops
  log/       Журнал и отчёты   команда «Данные»          схема log
  shared/    общее: ошибки, безопасность, JDBC           ревью всех команд
src/main/resources/
  db/migration/   миграции Flyway (V1 = schema.sql 1.2)
  static/         страницы: css/, js/, technician/, supervisor/, ref/, users/, log/, history/
infra/            docker-compose.yml, realm Keycloak
tools/demo-data/  генератор демонстрационных данных
docs/             архитектура, дизайн-система, решения (adr), задачи (backlog), документы проекта (specs)
```

В каждом модуле:
- открытый интерфейс для других модулей лежит в корне пакета (`AuthApi`, `RefApi`, `AuditLog`);
- контроллеры лежат в `web`, реализация в `internal`;
- обращаться к `web` и `internal` чужого модуля нельзя, это проверяет тест `ModularityTests`.

Подробно в [docs/architecture.md](docs/architecture.md).

## Что уже работает

| Метод или страница | Статус |
|---|---|
| `GET /api/v1/me`, права по ролям из базы | готово |
| `GET /api/v1/ref/{dictionary}`: все 8 справочников | готово |
| `GET /api/v1/flights`, `POST /api/v1/flights` | готово; поиск, создание, защита от дубля и журнал |
| `GET /api/v1/turnarounds?date=`, `GET /api/v1/turnarounds/{id}` | готово, техник видит только свои обороты |
| `GET /api/v1/log`: период до 31 дня, пользователь, действие, оборот, страницы | готово, без права `log.read_all` только свои записи |
| `GET /api/v1/metrics`: показатели по закрытым оборотам | готово |
| Защита журнала от изменения, удаление данных старше 3 лет, резервные копии | готово, см. [docs/backup.md](docs/backup.md) |
| `AuditLog.record(...)`: запись журнала в транзакции изменения | готово |
| Страницы: таблица оборотов, мои обороты, чек-лист (только просмотр), справочники, профиль | готово |
| Страницы «Журнал действий» и «История», блок «Мои действия за смену» в профиле | готово; список «Истории» ждёт фильтров `GET /turnarounds` |
| Остальные 18 методов спецификации и кнопки отметок | задачи команд, см. [docs/backlog.md](docs/backlog.md) |

## Как работаем

Ветки, коммиты, пулреквесты и проверка кода описаны в [CONTRIBUTING.md](CONTRIBUTING.md). Коротко:
- задача заводится в Issues;
- под неё создаётся ветка, изменения идут в `main` через пулреквест;
- в пулреквесте должна пройти сборка в GitHub Actions и должно быть одобрение от своей команды.

## Команды

| Команда | Модуль | Разработчики | Связь с преподавателем | Данные и документы |
|---|---|---|---|---|
| Авторизация | `auth` | | | |
| Справочники | `ref` | | | |
| Бизнес-логика | `ops` | | | |
| Данные | `log`, дизайн-система, база и миграции, окружение | | | |

Распределение ролей внутри команд предложил преподаватель на паре 23.10: один-два разработчика, человек для связи с преподавателем, человек на подготовку данных и документов. Впишите фамилии.
