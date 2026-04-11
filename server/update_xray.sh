#!/bin/bash
# Обновление xray — запускать раз в месяц
# crontab -e -> 0 3 1 * * /opt/vpn_bot/update_xray.sh

BOT_TOKEN="${BOT_TOKEN}"
ADMIN_ID="${ADMIN_ID}"
CONFIG="/usr/local/etc/xray/config.json"

send_msg() {
    curl -s -X POST "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage" \
        -d "chat_id=${ADMIN_ID}" -d "text=$1" > /dev/null
}

# Скачиваем последнюю версию
bash <(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh) install

# Проверяем конфиг перед рестартом
if /usr/local/bin/xray run -test -config "$CONFIG"; then
    systemctl restart xray
    VER=$(/usr/local/bin/xray version | head -1)
    send_msg "Xray updated and restarted: ${VER}"
else
    send_msg "Xray update FAILED: config test failed, keeping old version"
fi
