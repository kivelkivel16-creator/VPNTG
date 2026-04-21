#!/usr/bin/env python3
import os
import time
import json
import uuid
import secrets
import subprocess
import firebase_admin
from firebase_admin import credentials, db
from telegram import InlineKeyboardButton, InlineKeyboardMarkup, Update
from telegram.ext import Application, CallbackQueryHandler, CommandHandler, ContextTypes, MessageHandler, filters
import threading
import logging
import asyncio

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(message)s')
log = logging.getLogger(__name__)

BOT_TOKEN = os.environ.get("BOT_TOKEN", "")
ADMIN_ID  = int(os.environ.get("ADMIN_ID", "0"))
DB_URL    = "https://project-5309763298767572901-default-rtdb.europe-west1.firebasedatabase.app/"
CRED_FILE = "/root/firebase-key.json"

app = None
_bot_loop = None  # хранит event loop бота, устанавливается в main()


def is_admin(update: Update) -> bool:
    return bool(update.effective_user and update.effective_user.id == ADMIN_ID)


def log_command(update: Update, cmd: str) -> None:
    u = update.effective_user
    uid = u.id if u else None
    uname = f"@{u.username}" if u and u.username else "-"
    log.info("cmd /%s from user_id=%s %s (ADMIN_ID=%s ok=%s)", cmd, uid, uname, ADMIN_ID, uid == ADMIN_ID)


def init_firebase():
    cred = credentials.Certificate(CRED_FILE)
    firebase_admin.initialize_app(cred, {"databaseURL": DB_URL})
    log.info("Firebase initialized")


async def _post_init(application: Application) -> None:
    """Вызывается после старта event loop — сохраняем loop для Firebase poller."""
    global _bot_loop
    _bot_loop = asyncio.get_running_loop()
    log.info("Bot loop captured")
    await application.bot.delete_my_commands()


async def notify_admin(device_id, nickname):
    keyboard = InlineKeyboardMarkup([[
        InlineKeyboardButton("✅ Одобрить", callback_data=f"approve:{device_id}"),
        InlineKeyboardButton("❌ Отклонить", callback_data=f"reject:{device_id}"),
    ]])
    await app.bot.send_message(
        chat_id=ADMIN_ID,
        text=f"🔔 Запрос на VPN\n\n👤 <b>{nickname}</b>\n📱 <code>{device_id}</code>\n\nОтветь <b>Да</b> или <b>Нет</b>",
        parse_mode="HTML",
        reply_markup=keyboard
    )
    app.bot_data["last_pending_device_id"] = device_id
    app.bot_data["last_pending_nickname"] = nickname
    log.info("Notified admin: %s (%s)", nickname, device_id)


async def handle_callback(update, context):
    query = update.callback_query
    u = update.effective_user
    uid = u.id if u else None
    log.info("callback from user_id=%s (ADMIN_ID=%s ok=%s)", uid, ADMIN_ID, uid == ADMIN_ID)
    if not is_admin(update):
        await query.answer("Нет доступа", show_alert=True)
        return
    await query.answer()
    action, device_id = query.data.split(":", 1)

    if action == "delete":
        user_data = db.reference(f"vpn_users/{device_id}").get()
        if user_data and user_data.get("xray_uuid"):
            _xray_remove_client(user_data["xray_uuid"])
        db.reference(f"vpn_users/{device_id}").update({"status": "deleted", "_deleted": True})
        await query.edit_message_text(f"🗑 Удалён: <code>{device_id}</code>", parse_mode="HTML")
        log.info("Deleted: %s", device_id)
        return

    if action == "revoke":
        user_data = db.reference(f"vpn_users/{device_id}").get()
        if user_data and user_data.get("xray_uuid"):
            _xray_remove_client(user_data["xray_uuid"])
        await set_status(device_id, "rejected")
        nickname = user_data.get("nickname", device_id) if user_data else device_id
        await query.edit_message_text(f"⛔ <b>{nickname}</b> — отключён", parse_mode="HTML")
        log.info("Revoked: %s", device_id)
        return

    if action == "restore":
        user_data = db.reference(f"vpn_users/{device_id}").get()
        if not user_data:
            await query.edit_message_text("Пользователь не найден.")
            return
        nickname = user_data.get("nickname", device_id)
        platform = user_data.get("platform", "android")
        updates = {"status": "approved", "_deleted": False}
        if platform == "ios" and user_data.get("xray_uuid"):
            email = user_data.get("xray_email", f"ios_{device_id}")
            _xray_add_client(user_data["xray_uuid"], email, 443)
        db.reference(f"vpn_users/{device_id}").update(updates)
        await query.edit_message_text(f"✅ <b>{nickname}</b> — доступ восстановлен", parse_mode="HTML")
        log.info("Restored: %s", device_id)
        return

    if action == "purge":
        user_data = db.reference(f"vpn_users/{device_id}").get()
        if user_data and user_data.get("xray_uuid"):
            _xray_remove_client(user_data["xray_uuid"])
        db.reference(f"vpn_users/{device_id}").set(None)
        await query.edit_message_text(f"❌ Удалён навсегда: <code>{device_id}</code>", parse_mode="HTML")
        log.info("Purged: %s", device_id)
        return

    # approve / reject
    status = "approved" if action == "approve" else "rejected"
    user_data = db.reference(f"vpn_users/{device_id}").get()
    nickname = user_data.get("nickname", "Unknown") if user_data else "Unknown"
    platform = user_data.get("platform", "android") if user_data else "android"

    if action == "approve" and platform == "ios" and user_data and user_data.get("xray_uuid"):
        # Восстанавливаем xray-клиент для iOS при одобрении
        email = user_data.get("xray_email", f"ios_{device_id}")
        _xray_add_client(user_data["xray_uuid"], email, 443)
    elif action == "reject" and user_data and user_data.get("xray_uuid"):
        # Удаляем xray-клиент при отклонении
        _xray_remove_client(user_data["xray_uuid"])

    db.reference(f"vpn_users/{device_id}/status").set(status)
    emoji = "✅" if status == "approved" else "❌"
    await query.edit_message_text(
        f"{emoji} <b>{nickname}</b> — {'разрешён' if status == 'approved' else 'отклонён'}",
        parse_mode="HTML"
    )
    log.info("%s -> %s", nickname, status)


async def handle_text_reply(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """Обработка текстовых ответов 'Да' и 'Нет' для последней заявки."""
    if not is_admin(update):
        return
    text = (update.message.text or "").strip().lower()
    if text not in ("да", "нет"):
        return

    device_id = context.bot_data.get("last_pending_device_id")
    nickname = context.bot_data.get("last_pending_nickname", "Unknown")
    if not device_id:
        await update.message.reply_text("Нет активных заявок для ответа.")
        return

    user_data = db.reference(f"vpn_users/{device_id}").get()
    if not user_data or user_data.get("status") != "pending":
        await update.message.reply_text(f"Заявка <code>{device_id}</code> уже обработана.", parse_mode="HTML")
        context.bot_data.pop("last_pending_device_id", None)
        return

    if text == "да":
        await set_status(device_id, "approved")
        await update.message.reply_text(f"✅ <b>{nickname}</b> — разрешён", parse_mode="HTML")
        log.info("Text approve: %s (%s)", nickname, device_id)
    else:
        # При отклонении текстом — тоже убираем из xray если есть
        if user_data.get("xray_uuid"):
            _xray_remove_client(user_data["xray_uuid"])
        await set_status(device_id, "rejected")
        await update.message.reply_text(f"❌ <b>{nickname}</b> — отклонён", parse_mode="HTML")
        log.info("Text reject: %s (%s)", nickname, device_id)

    context.bot_data.pop("last_pending_device_id", None)
    context.bot_data.pop("last_pending_nickname", None)


def fmt_user_line(device_id: str, user_data: dict) -> str:
    nickname = user_data.get("nickname", "Unknown")
    status = user_data.get("status", "unknown")
    platform = user_data.get("platform", "android")
    icon = "🍎" if platform == "ios" else "📱"
    return f"• {icon} <b>{nickname}</b> — <code>{device_id}</code> [{status}]"


async def cmd_users(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "users")
    if not is_admin(update):
        return
    users = db.reference("vpn_users").get() or {}
    if not users:
        await update.message.reply_text("Пользователей пока нет.")
        return

    sent = 0
    total = sum(1 for ud in users.values() if isinstance(ud, dict) and not ud.get("_deleted") and ud.get("status") != "deleted")
    for device_id, user_data in list(users.items()):
        if not isinstance(user_data, dict):
            continue
        if user_data.get("_deleted") or user_data.get("status") == "deleted":
            continue
        if sent >= 30:
            break
        nickname = user_data.get("nickname", "Unknown")
        status = user_data.get("status", "unknown")
        platform = user_data.get("platform", "android")
        icon = "🍎" if platform == "ios" else "📱"
        if status == "rejected":
            keyboard = InlineKeyboardMarkup([[
                InlineKeyboardButton("✅ Вернуть", callback_data=f"restore:{device_id}"),
                InlineKeyboardButton("🗑 Удалить", callback_data=f"delete:{device_id}"),
            ]])
        else:
            keyboard = InlineKeyboardMarkup([[
                InlineKeyboardButton("⛔ Отключить", callback_data=f"revoke:{device_id}"),
                InlineKeyboardButton("🗑 Удалить", callback_data=f"delete:{device_id}"),
            ]])
        await update.message.reply_text(
            f"{icon} <b>{nickname}</b>\n<code>{device_id}</code> [{status}]",
            parse_mode="HTML",
            reply_markup=keyboard
        )
        sent += 1
    if total > 30:
        await update.message.reply_text(f"Показано {sent} из {total} пользователей")


async def send_users_by_status(chat_id: int, status_filter: str, title: str):
    users = db.reference("vpn_users").get() or {}
    filtered = [
        (did, ud) for did, ud in users.items()
        if isinstance(ud, dict) and ud.get("status") == status_filter
    ]
    if not filtered:
        await app.bot.send_message(chat_id=chat_id, text=f"{title}: пусто.")
        return

    lines = [f"<b>{title}: {len(filtered)}</b>"]
    for device_id, user_data in filtered[:50]:
        lines.append(fmt_user_line(device_id, user_data))

    # Telegram limit: 4096 chars per message
    text = "\n".join(lines)
    if len(text) > 4000:
        text = text[:4000] + "\n…(обрезано)"
    await app.bot.send_message(chat_id=chat_id, text=text, parse_mode="HTML")


async def send_pending_requests(chat_id: int):
    users = db.reference("vpn_users").get() or {}
    pending = [
        (did, ud) for did, ud in users.items()
        if isinstance(ud, dict) and ud.get("status") == "pending"
    ]
    if not pending:
        await app.bot.send_message(chat_id=chat_id, text="Ожидающих заявок нет.")
        return

    await app.bot.send_message(chat_id=chat_id, text=f"Ожидающих заявок: {len(pending)}")
    for device_id, user_data in pending[:30]:
        nickname = user_data.get("nickname", "Unknown")
        keyboard = InlineKeyboardMarkup([[
            InlineKeyboardButton("✅ Одобрить", callback_data=f"approve:{device_id}"),
            InlineKeyboardButton("❌ Отклонить", callback_data=f"reject:{device_id}"),
        ]])
        await app.bot.send_message(
            chat_id=chat_id,
            text=f"🔔 Ожидает решения\n\n👤 <b>{nickname}</b>\n📱 <code>{device_id}</code>",
            parse_mode="HTML",
            reply_markup=keyboard,
        )


async def cmd_user(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "user")
    if not is_admin(update):
        return
    if not context.args:
        await update.message.reply_text("Использование: /user <device_id>")
        return
    device_id = context.args[0].strip()
    user_data = db.reference(f"vpn_users/{device_id}").get()
    if not user_data:
        await update.message.reply_text("Пользователь не найден.")
        return
    nickname = user_data.get("nickname", "Unknown")
    status = user_data.get("status", "unknown")
    requested_at = user_data.get("requestedAt") or "-"
    device_model = user_data.get("deviceModel") or "-"
    android_version = user_data.get("androidVersion") or "-"
    app_version = user_data.get("appVersion") or "-"
    await update.message.reply_text(
        f"<b>{nickname}</b>\n"
        f"ID: <code>{device_id}</code>\n"
        f"Status: <b>{status}</b>\n"
        f"Устройство: <code>{device_model}</code>\n"
        f"Android: <code>{android_version}</code>\n"
        f"App: <code>{app_version}</code>\n"
        f"Requested: <code>{requested_at}</code>",
        parse_mode="HTML",
    )


async def set_status(device_id: str, status: str):
    db.reference(f"vpn_users/{device_id}/status").set(status)


def _xray_add_client(user_uuid: str, email: str, port: int) -> bool:
    """Add a client to xray config and restart xray."""
    config_path = "/usr/local/etc/xray/config.json"
    try:
        with open(config_path) as f:
            cfg = json.load(f)
        for ib in cfg["inbounds"]:
            if ib.get("port") == port and ib.get("protocol") == "vless":
                clients = ib["settings"]["clients"]
                if not any(c.get("id") == user_uuid for c in clients):
                    # port 443 = tcp+Reality → flow required; port 8443 = xhttp → flow must be empty
                    flow = "xtls-rprx-vision" if port == 443 else ""
                    clients.append({"id": user_uuid, "email": email, "flow": flow})
        with open(config_path, "w") as f:
            json.dump(cfg, f, indent=2)
        subprocess.run(["systemctl", "restart", "xray"], timeout=10)
        log.info("xray: added client %s (%s)", email, user_uuid)
        return True
    except Exception as e:
        log.error("xray: failed to add client: %s", e)
        return False


def _xray_remove_client(user_uuid: str) -> bool:
    """Remove a client from xray config and restart xray."""
    config_path = "/usr/local/etc/xray/config.json"
    try:
        with open(config_path) as f:
            cfg = json.load(f)
        removed = False
        for ib in cfg["inbounds"]:
            if ib.get("protocol") == "vless":
                clients = ib["settings"]["clients"]
                new_clients = [c for c in clients if c.get("id") != user_uuid]
                if len(new_clients) != len(clients):
                    removed = True
                ib["settings"]["clients"] = new_clients
        if removed:
            with open(config_path, "w") as f:
                json.dump(cfg, f, indent=2)
            subprocess.run(["systemctl", "restart", "xray"], timeout=10)
            log.info("xray: removed client uuid=%s", user_uuid)
        return removed
    except Exception as e:
        log.error("xray: failed to remove client: %s", e)
        return False


def _xray_get_traffic() -> dict:
    """Query xray API for per-user traffic stats. Returns {email: [up_bytes, down_bytes]}"""
    try:
        result = subprocess.run(
            ["/usr/local/bin/xray", "api", "statsquery", "--server=127.0.0.1:10085", "-pattern", ""],
            capture_output=True, text=True, timeout=5
        )
        if not result.stdout.strip():
            return {}
        stats = {}
        data = json.loads(result.stdout)
        for stat in data.get("stat", []):
            name = stat.get("name", "")
            value = int(stat.get("value", 0) or 0)
            parts = name.split(">>>")
            if len(parts) == 4 and parts[0] == "user":
                email = parts[1]
                direction = parts[3]
                if email not in stats:
                    stats[email] = [0, 0]
                if direction == "uplink":
                    stats[email][0] += value
                elif direction == "downlink":
                    stats[email][1] += value
        return stats
    except Exception as e:
        log.warning("xray traffic query failed: %s", e)
        return {}


async def cmd_approve(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "approve")
    if not is_admin(update):
        return
    if not context.args:
        await update.message.reply_text("Использование: /approve <device_id>")
        return
    device_id = context.args[0].strip()
    user_data = db.reference(f"vpn_users/{device_id}").get()
    # Восстанавливаем xray-клиент для iOS
    if user_data and user_data.get("platform") == "ios" and user_data.get("xray_uuid"):
        email = user_data.get("xray_email", f"ios_{device_id}")
        _xray_add_client(user_data["xray_uuid"], email, 443)
    await set_status(device_id, "approved")
    await update.message.reply_text(f"✅ Одобрено: <code>{device_id}</code>", parse_mode="HTML")


async def cmd_reject(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "reject")
    if not is_admin(update):
        return
    if not context.args:
        await update.message.reply_text("Использование: /reject <device_id>")
        return
    device_id = context.args[0].strip()
    user_data = db.reference(f"vpn_users/{device_id}").get()
    if user_data and user_data.get("xray_uuid"):
        _xray_remove_client(user_data["xray_uuid"])
    await set_status(device_id, "rejected")
    await update.message.reply_text(f"❌ Отклонено: <code>{device_id}</code>", parse_mode="HTML")


async def cmd_delete(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "delete")
    if not is_admin(update):
        return
    if not context.args:
        await update.message.reply_text("Использование: /delete <device_id>")
        return
    device_id = context.args[0].strip()
    try:
        user_data = db.reference(f"vpn_users/{device_id}").get()
        if user_data and user_data.get("xray_uuid"):
            _xray_remove_client(user_data["xray_uuid"])
        db.reference(f"vpn_users/{device_id}").set(None)
        await update.message.reply_text(f"🗑 Удалён: <code>{device_id}</code>", parse_mode="HTML")
    except Exception as e:
        log.error("cmd_delete error: %s", e)
        await update.message.reply_text(f"❌ Ошибка: <code>{e}</code>", parse_mode="HTML")


async def cmd_resetua(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "resetua")
    if not is_admin(update):
        return
    if not context.args:
        await update.message.reply_text("Использование: /resetua <device_id>")
        return
    device_id = context.args[0].strip()
    user_data = db.reference(f"vpn_users/{device_id}").get()
    if not user_data:
        await update.message.reply_text("Пользователь не найден.")
        return
    db.reference(f"vpn_users/{device_id}").update({"locked_ua": None})
    nickname = user_data.get("nickname", device_id)
    await update.message.reply_text(f"🔄 UA сброшен для <b>{nickname}</b> — при следующем запросе подписки заблокируется на новый UA", parse_mode="HTML")
    log.info("UA reset for %s (%s)", nickname, device_id)


async def cmd_revoke(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "revoke")
    if not is_admin(update):
        return
    if not context.args:
        await update.message.reply_text("Использование: /revoke <device_id>")
        return
    device_id = context.args[0].strip()
    user_data = db.reference(f"vpn_users/{device_id}").get()
    if user_data and user_data.get("xray_uuid"):
        _xray_remove_client(user_data["xray_uuid"])
    await set_status(device_id, "rejected")
    await update.message.reply_text(f"⛔ Доступ отключён: <code>{device_id}</code>", parse_mode="HTML")


async def cmd_ios(update: Update, context: ContextTypes.DEFAULT_TYPE):
    """Create iOS subscription: /ios <nickname>"""
    log_command(update, "ios")
    if not is_admin(update):
        return
    if not context.args:
        await update.message.reply_text("Использование: /ios <nickname>")
        return

    nickname = " ".join(context.args)
    token = secrets.token_hex(16)
    device_id = f"ios_{token[:8]}"
    user_uuid = str(uuid.uuid4())

    xray_ok = _xray_add_client(user_uuid, f"ios_{device_id}", 443)

    db.reference(f"vpn_users/{device_id}").set({
        "nickname": nickname,
        "status": "approved",
        "platform": "ios",
        "sub_token": token,
        "xray_email": f"ios_{device_id}",
        "xray_uuid": user_uuid,
        "requestedAt": int(time.time() * 1000)
    })

    sub_url = f"http://163.5.180.89:8080/sub/{token}"
    xray_note = "" if xray_ok else "\n⚠️ Не удалось добавить в xray — добавь вручную"

    await update.message.reply_text(
        f"✅ iOS подписка создана для <b>{nickname}</b>\n\n"
        f"📱 Ссылка для Happ/V2Box/Streisand:\n"
        f"<code>{sub_url}</code>\n\n"
        f"🆔 Device ID: <code>{device_id}</code>\n"
        f"🔑 UUID: <code>{user_uuid}</code>\n\n"
        f"Инструкция:\n"
        f"1. Установить Happ или V2Box из App Store\n"
        f"2. Добавить подписку → вставить ссылку\n"
        f"3. Обновить подписку → подключиться\n\n"
        f"⛔ Отключить: /revoke {device_id}{xray_note}",
        parse_mode="HTML"
    )
    log.info("iOS subscription created: %s (%s) uuid=%s", nickname, device_id, user_uuid)


async def cmd_help(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "help")
    if not is_admin(update):
        return
    await update.message.reply_text(
        "/start - меню и ожидающие заявки\n"
        "/online - кто сейчас онлайн и сколько трафика\n"
        "/pending - показать ожидающих\n"
        "/approved - показать одобренных\n"
        "/rejected - показать отклонённых\n"
        "/users - список пользователей\n"
        "/user <device_id> - детали устройства\n"
        "/approve <device_id> - разрешить\n"
        "/reject <device_id> - отклонить\n"
        "/revoke <device_id> - отключить доступ\n"
        "/resetua <device_id> - сбросить UA-блокировку (iOS)\n"
        "/delete <device_id> - удалить\n"
        "/ios <nickname> - создать iOS подписку\n"
        "/trash - корзина удалённых\n\n"
        "Да / Нет — одобрить/отклонить последнюю заявку текстом",
    )


async def cmd_online(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "online")
    if not is_admin(update):
        return
    users = db.reference("vpn_users").get() or {}
    online_users = []
    ios_recent = []
    now = time.time() * 1000
    ONLINE_TIMEOUT_MS = 10 * 60 * 1000  # 10 минут

    for device_id, user_data in users.items():
        if not isinstance(user_data, dict):
            continue
        if user_data.get("status") not in ("approved",):
            continue
        last_seen = user_data.get("lastSeen", 0)
        platform = user_data.get("platform", "android")
        # abs() на случай расхождения часов между сервером и устройством
        is_recently_seen = last_seen > 0 and abs(now - last_seen) < ONLINE_TIMEOUT_MS

        if platform != "ios" and (user_data.get("online") is True or is_recently_seen):
            online_users.append((device_id, user_data))
        elif platform == "ios" and is_recently_seen:
            ios_recent.append((device_id, user_data, last_seen))

    if not online_users and not ios_recent:
        await update.message.reply_text("Никого онлайн нет.")
        return

    xray_traffic = _xray_get_traffic()
    lines = [f"<b>Онлайн сейчас: {len(online_users) + len(ios_recent)}</b>"]

    for device_id, user_data in online_users:
        nickname = user_data.get("nickname", "Unknown")
        session_start = user_data.get("sessionStart")
        up_mb = user_data.get("sessionUpMB", 0) or 0
        down_mb = user_data.get("sessionDownMB", 0) or 0
        if session_start:
            duration_sec = int((now - session_start) / 1000)
            h, rem = divmod(duration_sec, 3600)
            m, s = divmod(rem, 60)
            duration_str = f"{h}ч {m}м" if h > 0 else f"{m}м {s}с"
        else:
            duration_str = "—"
        lines.append(
            f"\n📱 <b>{nickname}</b> [Android]\n"
            f"   ⏱ Онлайн: {duration_str}\n"
            f"   ⬆️ {up_mb:.1f} МБ  ⬇️ {down_mb:.1f} МБ"
        )

    for device_id, user_data, last_seen in ios_recent:
        nickname = user_data.get("nickname", "Unknown")
        xray_email = user_data.get("xray_email", "")
        minutes_ago = int((now - last_seen) / 1000 / 60)
        seen_str = f"{minutes_ago}м назад" if minutes_ago < 60 else f"{minutes_ago // 60}ч назад"
        if xray_email in xray_traffic:
            up_mb = xray_traffic[xray_email][0] / 1024 / 1024
            down_mb = xray_traffic[xray_email][1] / 1024 / 1024
            traffic_str = f"   ⬆️ {up_mb:.1f} МБ  ⬇️ {down_mb:.1f} МБ\n"
        else:
            traffic_str = ""
        lines.append(
            f"\n🍎 <b>{nickname}</b> [iOS]\n"
            f"   🕐 Последний раз: {seen_str}\n"
            f"{traffic_str}"
        )

    await update.message.reply_text("\n".join(lines), parse_mode="HTML")


async def cmd_start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "start")
    if not is_admin(update):
        return
    await update.message.reply_text(
        "Панель администратора VPN.\n"
        "Используй /pending для быстрых кнопок одобрения.",
    )
    await send_pending_requests(update.effective_chat.id)


async def cmd_pending(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "pending")
    if not is_admin(update):
        return
    await send_pending_requests(update.effective_chat.id)


async def cmd_trash(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "trash")
    if not is_admin(update):
        return
    users = db.reference("vpn_users").get() or {}
    deleted = [
        (did, ud) for did, ud in users.items()
        if isinstance(ud, dict) and (ud.get("_deleted") or ud.get("status") == "deleted")
    ]
    if not deleted:
        await update.message.reply_text("Корзина пуста.")
        return
    await update.message.reply_text(f"🗑 Корзина: {len(deleted)} пользователей")
    for device_id, user_data in deleted[:30]:
        nickname = user_data.get("nickname", "Unknown")
        platform = user_data.get("platform", "android")
        icon = "🍎" if platform == "ios" else "📱"
        keyboard = InlineKeyboardMarkup([[
            InlineKeyboardButton("♻️ Восстановить", callback_data=f"restore:{device_id}"),
            InlineKeyboardButton("❌ Удалить навсегда", callback_data=f"purge:{device_id}"),
        ]])
        await update.message.reply_text(
            f"{icon} <b>{nickname}</b>\n<code>{device_id}</code>",
            parse_mode="HTML",
            reply_markup=keyboard
        )


async def cmd_approved(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "approved")
    if not is_admin(update):
        return
    await send_users_by_status(update.effective_chat.id, "approved", "Одобренные")


async def cmd_rejected(update: Update, context: ContextTypes.DEFAULT_TYPE):
    log_command(update, "rejected")
    if not is_admin(update):
        return
    await send_users_by_status(update.effective_chat.id, "rejected", "Отклонённые")


def run_firebase_listener():
    """Polling Firebase каждые 3 секунды — надёжнее SSE listener."""
    global _bot_loop
    known_pending: set = set()
    log.info("Firebase poller: started")

    try:
        users = db.reference("vpn_users").get() or {}
        for device_id, user_data in users.items():
            if isinstance(user_data, dict) and user_data.get("status") == "pending":
                known_pending.add(device_id)
        log.info("Firebase poller: pre-loaded %d pending users", len(known_pending))
    except Exception as e:
        log.error("Firebase poller: init error: %s", e)

    while True:
        try:
            users = db.reference("vpn_users").get() or {}
            for device_id, user_data in users.items():
                if not isinstance(user_data, dict):
                    continue
                if user_data.get("status") == "pending" and device_id not in known_pending:
                    known_pending.add(device_id)
                    nickname = user_data.get("nickname", "Unknown")
                    log.info("New pending user: %s (%s)", nickname, device_id)
                    # Ждём пока loop бота будет готов
                    loop = None
                    for _ in range(20):
                        if _bot_loop is not None and _bot_loop.is_running():
                            loop = _bot_loop
                            break
                        time.sleep(0.5)
                    if loop is None:
                        log.error("Bot loop not ready for %s", device_id)
                        continue
                    try:
                        future = asyncio.run_coroutine_threadsafe(
                            notify_admin(device_id, nickname),
                            loop
                        )
                        future.result(timeout=15)
                    except Exception as e:
                        log.error("Failed to notify admin: %s", e)
                elif user_data.get("status") != "pending":
                    known_pending.discard(device_id)
        except Exception as e:
            log.error("Firebase poller error: %s", e)
        time.sleep(3)


def main():
    global app, _bot_loop
    init_firebase()
    threading.Thread(target=run_firebase_listener, daemon=True).start()
    app = Application.builder().token(BOT_TOKEN).post_init(
        _post_init
    ).build()
    app.add_handler(CallbackQueryHandler(handle_callback))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_text_reply))
    app.add_handler(CommandHandler("start", cmd_start))
    app.add_handler(CommandHandler("help", cmd_help))
    app.add_handler(CommandHandler("online", cmd_online))
    app.add_handler(CommandHandler("pending", cmd_pending))
    app.add_handler(CommandHandler("approved", cmd_approved))
    app.add_handler(CommandHandler("rejected", cmd_rejected))
    app.add_handler(CommandHandler("users", cmd_users))
    app.add_handler(CommandHandler("user", cmd_user))
    app.add_handler(CommandHandler("approve", cmd_approve))
    app.add_handler(CommandHandler("reject", cmd_reject))
    app.add_handler(CommandHandler("revoke", cmd_revoke))
    app.add_handler(CommandHandler("resetua", cmd_resetua))
    app.add_handler(CommandHandler("delete", cmd_delete))
    app.add_handler(CommandHandler("ios", cmd_ios))
    app.add_handler(CommandHandler("trash", cmd_trash))
    log.info("Bot started")
    app.run_polling(drop_pending_updates=True)


if __name__ == "__main__":
    main()
