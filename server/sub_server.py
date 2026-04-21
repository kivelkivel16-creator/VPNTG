#!/usr/bin/env python3
"""
Subscription server for iOS clients (Happ, V2Box, Streisand)
- token_hex tokens (no special chars)
- plain text response (no base64 wrapping)
- device lock via User-Agent
"""
import time
import firebase_admin
from firebase_admin import credentials, db
from flask import Flask, Response, request
import logging

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(message)s')
log = logging.getLogger(__name__)

app = Flask(__name__)

DB_URL = "https://project-5309763298767572901-default-rtdb.europe-west1.firebasedatabase.app/"
CRED_FILE = "/root/firebase-key.json"

cred = credentials.Certificate(CRED_FILE)
firebase_admin.initialize_app(cred, {"databaseURL": DB_URL})

# Simple in-memory rate limiter: token -> last_request_time
_rate_cache = {}

def is_rate_limited(token: str, min_interval: int = 10) -> bool:
    """Rate limit: allow one request per min_interval seconds per token."""
    now = time.time()
    last = _rate_cache.get(token, 0)
    if now - last < min_interval:
        return True
    _rate_cache[token] = now
    return False

VLESS_TEMPLATE = (
    "vless://{uuid}@163.5.180.89:443"
    "?encryption=none"
    "&security=reality"
    "&sni=www.microsoft.com"
    "&fp=chrome"
    "&pbk=U-KX0IXOGK-_FU_1SAROQDHheSWkbFMmtQpobCY0b14"
    "&sid=ce39eccf69fbb727"
    "&type=tcp"
    "&flow=xtls-rprx-vision"
    "#ApexTG_iOS"
)

# Fallback для старых пользователей без персонального UUID
VLESS_CONFIG_LEGACY = (
    "vless://22faa5c3-3d86-4a31-8b0c-873f1e3ebc21@163.5.180.89:443"
    "?encryption=none"
    "&security=reality"
    "&sni=www.microsoft.com"
    "&fp=chrome"
    "&pbk=U-KX0IXOGK-_FU_1SAROQDHheSWkbFMmtQpobCY0b14"
    "&sid=ce39eccf69fbb727"
    "&type=tcp"
    "&flow=xtls-rprx-vision"
    "#ApexTG_iOS"
)


@app.route("/sub/<token>")
def subscription(token):
    token = token.strip()
    log.info("Sub request for token: '%s'", token)

    if is_rate_limited(token):
        log.info("Rate limited token: '%s'", token)
        return Response("Too many requests", status=429)

    users = db.reference("vpn_users").get() or {}

    for device_id, user_data in users.items():
        if not isinstance(user_data, dict):
            continue
        stored_token = user_data.get("sub_token", "").strip()

        if stored_token != token:
            continue

        if user_data.get("status") != "approved":
            log.info("Access denied for %s: status=%s", device_id, user_data.get("status"))
            return Response("Access denied", status=403)

        user_agent = request.headers.get("User-Agent", "unknown")

        # Ignore bot/crawler requests (Telegram link preview, etc.)
        bot_keywords = ("TelegramBot", "TwitterBot", "facebookexternalhit", "Googlebot", "crawler", "spider")
        if any(kw.lower() in user_agent.lower() for kw in bot_keywords):
            log.info("Ignoring bot UA for %s: %s", device_id, user_agent)
            return Response("OK", status=200)

        locked_ua = user_data.get("locked_ua")

        if locked_ua is None:
            db.reference(f"vpn_users/{device_id}").update({
                "locked_ua": user_agent,
                "lastSeen": int(time.time() * 1000),
                "platform": "ios"
            })
            log.info("Locked %s to UA: %s", device_id, user_agent)
        elif locked_ua != user_agent:
            log.info("UA mismatch for %s: locked='%s' got='%s'", device_id, locked_ua, user_agent)
            return Response("Access denied", status=403)
        else:
            # Update lastSeen at most once every 5 minutes to avoid spamming Firebase
            last_seen = user_data.get("lastSeen", 0)
            now_ms = int(time.time() * 1000)
            if now_ms - last_seen > 5 * 60 * 1000:
                db.reference(f"vpn_users/{device_id}").update({"lastSeen": now_ms})

        # Plain text — works with Happ, V2Box, Streisand
        user_uuid = user_data.get("xray_uuid")
        config = VLESS_TEMPLATE.format(uuid=user_uuid) if user_uuid else VLESS_CONFIG_LEGACY
        return Response(config + "\n", mimetype="text/plain; charset=utf-8")

    log.info("Token not found: '%s'", token)
    return Response("Not found", status=404)


@app.route("/health")
def health():
    return "OK"


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
