#!/usr/bin/env python3
"""Adds TCP+Reality inbound on port 443 to existing xray config"""
import json

CONFIG_PATH = "/usr/local/etc/xray/config.json"

with open(CONFIG_PATH) as f:
    config = json.load(f)

# Check if port 443 already exists
for inbound in config["inbounds"]:
    if inbound.get("port") == 443:
        print("Port 443 inbound already exists, skipping.")
        exit(0)

# Get privateKey and shortIds from existing vless inbound
existing = next((ib for ib in config["inbounds"] if ib.get("protocol") == "vless" and "realitySettings" in ib.get("streamSettings", {})), None)
if not existing:
    print("ERROR: No vless+reality inbound found in config")
    exit(1)
reality = existing["streamSettings"]["realitySettings"]
private_key = reality["privateKey"]
short_ids = reality["shortIds"]
server_names = reality["serverNames"]
client_id = existing["settings"]["clients"][0]["id"]

new_inbound = {
    "listen": "0.0.0.0",
    "port": 443,
    "protocol": "vless",
    "settings": {
        "clients": [{"id": client_id, "flow": "xtls-rprx-vision"}],
        "decryption": "none"
    },
    "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
            "show": False,
            "dest": "www.microsoft.com:443",
            "serverNames": server_names,
            "privateKey": private_key,
            "shortIds": short_ids
        }
    }
}

config["inbounds"].append(new_inbound)

with open(CONFIG_PATH, "w") as f:
    json.dump(config, f, indent=2)

print("Done! Port 443 inbound added.")
