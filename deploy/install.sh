#!/usr/bin/env bash
# Ставит или обновляет бота. Ожидает, что сборка (gradlew installDist) уже скопирована в /opt/awgbot/app,
# а deploy/ - в /opt/awgbot/deploy. Запускать от root.
set -euo pipefail

BOT_DIR=/opt/awgbot

id awgbot >/dev/null 2>&1 || useradd --system --home-dir "$BOT_DIR" --shell /usr/sbin/nologin awgbot
mkdir -p "$BOT_DIR/data"

if [ ! -x "$BOT_DIR/jre/bin/java" ]; then
    mkdir -p "$BOT_DIR/jre"
    curl -sSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jre/hotspot/normal/eclipse" \
        | tar -xz -C "$BOT_DIR/jre" --strip-components=1
fi

install -m 755 -o root -g root "$BOT_DIR/deploy/awgbot-peer" /usr/local/sbin/awgbot-peer
echo "awgbot ALL=(root) NOPASSWD: /usr/local/sbin/awgbot-peer" > /etc/sudoers.d/awgbot
chmod 440 /etc/sudoers.d/awgbot
visudo -cf /etc/sudoers.d/awgbot

[ -f "$BOT_DIR/.env" ] || printf 'TELEGRAM_BOT_TOKEN=\nADMIN_IDS=\n' > "$BOT_DIR/.env"
chown -R awgbot:awgbot "$BOT_DIR"
chown root:awgbot "$BOT_DIR/.env" "$BOT_DIR/server.env"
chmod 640 "$BOT_DIR/.env" "$BOT_DIR/server.env"

install -m 644 "$BOT_DIR/deploy/awgbot.service" /etc/systemd/system/awgbot.service
systemctl daemon-reload
systemctl enable awgbot
if grep -q '^TELEGRAM_BOT_TOKEN=.\+' "$BOT_DIR/.env"; then
    systemctl restart awgbot
    systemctl status awgbot --no-pager
else
    echo "Впишите TELEGRAM_BOT_TOKEN и ADMIN_IDS в $BOT_DIR/.env и выполните: systemctl restart awgbot"
fi
