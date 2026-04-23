#!/bin/bash
# Настройка firewall — запустить один раз на сервере
# Оставляем только SSH (22) и xray (8443)

# Сбрасываем все правила
iptables -F
iptables -X

# По умолчанию — всё запрещено входящее
iptables -P INPUT DROP
iptables -P FORWARD DROP
iptables -P OUTPUT ACCEPT

# Разрешаем loopback
iptables -A INPUT -i lo -j ACCEPT

# Разрешаем уже установленные соединения
iptables -A INPUT -m state --state ESTABLISHED,RELATED -j ACCEPT

# SSH только по ключу (порт 22)
iptables -A INPUT -p tcp --dport 22 -j ACCEPT

# xray
iptables -A INPUT -p tcp --dport 8443 -j ACCEPT
iptables -A INPUT -p udp --dport 8443 -j ACCEPT

# iOS subscription server
iptables -A INPUT -p tcp --dport 8080 -j ACCEPT

# Сохраняем правила
iptables-save > /etc/iptables/rules.v4

echo "Firewall configured: only 22 and 8443 open"
