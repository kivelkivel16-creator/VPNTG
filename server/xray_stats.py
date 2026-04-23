#!/usr/bin/env python3
"""
Xray traffic stats poller.
Reads per-user traffic from xray stats API and writes to Firebase.
Runs every 60 seconds as a systemd service.
"""
import time
import subprocess
import json
import logging
import firebase_admin
from firebase_admin import credentials, db

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(message)s')
log = logging.getLogger(__name__)

DB_URL = "https://project-5309763298767572901-default-rtdb.europe-west1.firebasedatabase.app/"
CRED_FILE = "/root/firebase-key.json"
XRAY_API = "127.0.0.1:10085"
POLL_INTERVAL = 60  # seconds


def init_firebase():
    cred = credentials.Certificate(CRED_FILE)
    firebase_admin.initialize_app(cred, {"databaseURL": DB_URL})


def get_stat(name: str, reset: bool = True) -> int:
    """Query a single stat value from xray stats API."""
    try:
        args = [
            "/usr/local/bin/xray", "api", "stats",
            "--server", XRAY_API,
            "--name", name,
        ]
        if reset:
            args.append("--reset")
        result = subprocess.run(args, capture_output=True, text=True, timeout=5)
        if result.returncode != 0:
            return 0
        data = json.loads(result.stdout)
        return int(data.get("stat", {}).get("value", 0))
    except Exception:
        return 0


def poll_and_update():
    """Read traffic for all users from Firebase, query xray, update Firebase."""
    users = db.reference("vpn_users").get() or {}
    now = int(time.time() * 1000)

    for device_id, user_data in users.items():
        if not isinstance(user_data, dict):
            continue
        if user_data.get("status") != "approved":
            continue

        email = user_data.get("xray_email")
        if not email:
            continue

        up = get_stat(f"user>>>{email}>>>traffic>>>uplink")
        down = get_stat(f"user>>>{email}>>>traffic>>>downlink")

        total_up = user_data.get("totalUpBytes", 0) + up
        total_down = user_data.get("totalDownBytes", 0) + down

        update = {
            "totalUpBytes": total_up,
            "totalDownBytes": total_down,
            "totalUpMB": round(total_up / 1024 / 1024, 2),
            "totalDownMB": round(total_down / 1024 / 1024, 2),
            "lastStatUpdate": now,
        }
        # Update lastSeen if there was actual traffic this interval
        if up > 0 or down > 0:
            update["lastSeen"] = now

        db.reference(f"vpn_users/{device_id}").update(update)
        log.info("%s (%s): +%.1f KB up, +%.1f KB down | total: %.1f MB down",
                 user_data.get("nickname", device_id), email,
                 up / 1024, down / 1024, total_down / 1024 / 1024)


def main():
    init_firebase()
    log.info("Xray stats poller started (interval=%ds)", POLL_INTERVAL)
    while True:
        try:
            poll_and_update()
        except Exception as e:
            log.error("Poll error: %s", e)
        time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()
