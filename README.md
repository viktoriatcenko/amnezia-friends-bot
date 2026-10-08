# amnezia-friends-bot

Telegram-бот, который раздаёт друзьям личные конфиги **AmneziaWG** с общего VPS.

- Друг пишет боту, нажимает «Хочу доступ». Админу приходит заявка с кнопками «Одобрить» / «Отклонить».
- Пока заявка ждёт, бот говорит, что конфиг готовится, и ведёт по установке приложения:
  - **iPhone / Mac**: ссылка на App Store, а если приложение «недоступно в вашей стране», пошаговое создание второго Apple ID (США) только для App Store;
  - **Android**: ссылка на Google Play, а если не открывается, APK прямо в чат (бот сам берёт последний релиз с GitHub);
  - **Windows**: MSI прямо в чат.
- После одобрения бот сам создаёт пира в `awg1` и присылает два конфига:
  - `friends-blocked.conf`: через VPN идёт только заблокированное (YouTube, Meta, Telegram, X, Discord, ChatGPT, Claude, LinkedIn, Pornhub, реестр РКН, Cloudflare и т.д.), остальное напрямую;
  - `friends-all.conf`: весь трафик, плюс QR-код.
- Один конфиг на человека. Инструкцию одним HTML-файлом можно скачать командой `/guide`.

## Команды

| Кто | Команда | Что делает |
|---|---|---|
| все | `/start` | главное меню |
| все | `/config` | прислать конфиг ещё раз |
| все | `/guide` | инструкция файлом |
| админ | `/pending` | заявки с кнопками |
| админ | `/users` | у кого доступ, когда подключались, трафик |
| админ | `/revoke ID` | отключить доступ |

## Как устроено на сервере

- `awg1`: отдельный интерфейс для друзей, UDP 47213, `10.9.0.0/24` + `fd09:9::/64`, свои параметры обфускации. Личный `awg0` не затронут. Клиенты `awg1` не видят друг друга и `awg0`, исходящий 25/tcp закрыт.
- Бот работает под пользователем `awgbot` без root. Пиров добавляет и удаляет `/usr/local/sbin/awgbot-peer` через sudo, со строгой проверкой аргументов.
- `/opt/awgbot/`: `app/` (сборка), `jre/`, `data/awgbot.db` (SQLite: пользователи и ключи), `.env` (токен, админы), `server.env` (публичные параметры `awg1`).

## Развёртывание

```bash
# 1. На сервере (один раз): интерфейс awg1. Печатает параметры для server.env
ENDPOINT_HOST=130.17.30.215 bash deploy/setup-awg1.sh

# 2. Локально: сборка и копирование
./gradlew installDist
scp -r build/install/amnezia-friends-bot root@SERVER:/opt/awgbot/app
scp -r deploy root@SERVER:/opt/awgbot/

# 3. На сервере: /opt/awgbot/server.env (из шага 1) и /opt/awgbot/.env
#    TELEGRAM_BOT_TOKEN=...   (от @BotFather)
#    ADMIN_IDS=123456789      (через запятую)
bash /opt/awgbot/deploy/install.sh
```

Обновление: пересобрать, скопировать `app/`, выполнить `systemctl restart awgbot`.

## Список заблокированного

`src/main/resources/routes.txt` собирается скриптом `tools/update_routes.py` из анонсов AS (RIPEstat), списков [antifilter](https://antifilter.download), диапазонов Google, Telegram и Cloudflare, а также из резолва отдельных доменов. Мелкие сети расширяются до /24, чтобы в конфиге было около 1100 маршрутов. Обновлять раз в пару месяцев:

```bash
python tools/update_routes.py && ./gradlew installDist   # и задеплоить
```

Друзьям после этого нужно заново взять конфиг (`/config`) и импортировать его поверх старого.
