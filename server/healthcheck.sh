#!/bin/bash
# Healthcheck для xray — добавь в cron: */5 * * * * /opt/vpn_bot/healthcheck.sh
# crontab -e -> */5 * * * * /opt/vpn_bot/healthcheck.sh

BOT_TOKEN="${BOT_TOKEN}"
ADMIN_ID="${ADMIN_ID}"
SERVER_HOST="163.5.180.89"
SERVER_PORT="8443"
LOCK_FILE="/tmp/xray_alert_sent"

send_alert() {
    curl -s -X POST "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage" \
        -d "chat_id=${ADMIN_ID}" \
        -d "text=$1" > /dev/null
}

if ! nc -z -w3 "$SERVER_HOST" "$SERVER_PORT" 2>/dev/null; then
    # Отправляем алерт только один раз, не спамим
    if [ ! -f "$LOCK_FILE" ]; then
        send_alert "ALERT: xray port ${SERVER_PORT} unreachable on ${SERVER_HOST}"
        touch "$LOCK_FILE"
    fi
else
    # Порт доступен — удаляем lock если был
    if [ -f "$LOCK_FILE" ]; then
        send_alert "OK: xray port ${SERVER_PORT} is back online"
        rm "$LOCK_FILE"
    fi
fi
