#!/bin/bash
# Резервная копия конфига xray — раз в неделю
# crontab -e -> 0 4 * * 0 /opt/vpn_bot/backup_config.sh

BOT_TOKEN="${BOT_TOKEN}"
ADMIN_ID="${ADMIN_ID}"
CONFIG="/usr/local/etc/xray/config.json"
BACKUP_DIR="/opt/vpn_bot/backups"
DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/xray_config_${DATE}.json.enc"

mkdir -p "$BACKUP_DIR"

# Шифруем через openssl (пароль задай один раз и запомни)
# Пароль хранится в переменной окружения BACKUP_PASSWORD
if [ -z "$BACKUP_PASSWORD" ]; then
    echo "BACKUP_PASSWORD not set, skipping backup"
    exit 1
fi

openssl enc -aes-256-cbc -pbkdf2 -in "$CONFIG" -out "$BACKUP_FILE" -pass "pass:${BACKUP_PASSWORD}"

# Отправляем зашифрованный файл в Telegram (не в чат с пользователями!)
curl -s -X POST "https://api.telegram.org/bot${BOT_TOKEN}/sendDocument" \
    -F "chat_id=${ADMIN_ID}" \
    -F "document=@${BACKUP_FILE}" \
    -F "caption=xray config backup ${DATE}" > /dev/null

# Удаляем старые бэкапы старше 30 дней
find "$BACKUP_DIR" -name "*.enc" -mtime +30 -delete

echo "Backup done: $BACKUP_FILE"
