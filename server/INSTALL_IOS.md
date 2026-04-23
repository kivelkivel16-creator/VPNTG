# Установка iOS Subscription Server

## 1. Установить Flask на сервере

```bash
pip3 install flask
```

## 2. Скопировать файлы на сервер

```bash
# Если папка server/ ещё не на сервере
scp server/sub_server.py root@163.5.180.89:/root/server/
scp server/sub_server.service root@163.5.180.89:/etc/systemd/system/
```

## 3. Запустить сервис

```bash
ssh root@163.5.180.89

# Включить и запустить
systemctl daemon-reload
systemctl enable sub_server.service
systemctl start sub_server.service

# Проверить статус
systemctl status sub_server.service

# Проверить что работает
curl http://localhost:8080/health
# Должно вернуть: OK
```

## 4. Открыть порт 8080 (если firewall включён)

```bash
# Для ufw
ufw allow 8080/tcp

# Для firewalld
firewall-cmd --permanent --add-port=8080/tcp
firewall-cmd --reload
```

## 5. Обновить Firebase Rules

Загрузить обновлённый `firebase_database_rules.json` в Firebase Console:
- Firebase Console → Realtime Database → Rules → вставить содержимое файла → Publish

## 6. Использование

В Telegram боте:

```
/ios Иван
```

Бот вернёт ссылку вида:
```
http://163.5.180.89:8080/sub/abc123xyz
```

Друг вставляет эту ссылку в Happ/V2Box как подписку.

## 7. Отключение доступа

```
/revoke ios_abc123xy
```

Сервер начнёт отдавать 403 для этого токена.

## Логи

```bash
# Логи subscription server
journalctl -u sub_server.service -f

# Логи бота
journalctl -u vpn_bot.service -f
```

## Опционально: HTTPS через nginx

Если хочешь HTTPS (рекомендуется):

```nginx
server {
    listen 443 ssl;
    server_name 163.5.180.89;
    
    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;
    
    location /sub/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
    }
}
```

Тогда ссылка будет: `https://163.5.180.89/sub/abc123xyz`
