"""Persistent device credentials — like adb's remembered RSA keys.

The QR/master token only proves first contact; pairing exchanges it for a
per-device credential stored server-side in devices.json. Devices can be
listed and revoked without rotating the master token.
"""

import json
import secrets
import time
from pathlib import Path

from platformdirs import user_config_dir

from .config import APP_NAME


def _path() -> Path:
    p = Path(user_config_dir(APP_NAME))
    p.mkdir(parents=True, exist_ok=True)
    return p / "devices.json"


def _load() -> dict:
    p = _path()
    if p.exists():
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            pass
    return {}


def _save(devices: dict) -> None:
    _path().write_text(json.dumps(devices, indent=2), encoding="utf-8")


def issue(name: str) -> dict:
    devices = _load()
    device_id = secrets.token_hex(4)
    devices[device_id] = {
        "name": name or device_id,
        "token": secrets.token_urlsafe(24),
        "created": int(time.time()),
        "last_seen": None,
    }
    _save(devices)
    return {"device_id": device_id, "device_token": devices[device_id]["token"]}


def verify(token: str) -> str | None:
    """Return device_id if token belongs to a registered device."""
    devices = _load()
    for device_id, d in devices.items():
        if secrets.compare_digest(d.get("token", ""), token):
            d["last_seen"] = int(time.time())
            _save(devices)
            return device_id
    return None


def list_devices() -> list[dict]:
    return [{"id": k, **{kk: vv for kk, vv in v.items() if kk != "token"}}
            for k, v in _load().items()]


def revoke(device_id: str) -> bool:
    devices = _load()
    if device_id not in devices:
        return False
    del devices[device_id]
    _save(devices)
    return True


# ---------- pending pair requests (BLE write path, in-memory) ----------
_pending: dict[str, dict] = {}
PENDING_TTL = 300  # seconds — user needs time to click approve


def _gc() -> None:
    now = time.time()
    for k in [k for k, v in _pending.items() if now - v["ts"] > PENDING_TTL]:
        del _pending[k]


def pending_begin(key: str, name: str) -> None:
    if not key:
        return
    _gc()
    _pending[key] = {"name": name, "ts": time.time(), "approved": False}


def pending_list() -> list[dict]:
    _gc()
    return [{"key": k, "name": v["name"], "ts": v["ts"]} for k, v in _pending.items()]


def pending_approve(key: str) -> bool:
    _gc()
    if key in _pending:
        _pending[key]["approved"] = True
        return True
    return False


def pending_redeem(key: str) -> dict | None:
    """Return device creds once approved; None while pending/absent."""
    _gc()
    req = _pending.get(key)
    if not req or not req.get("approved"):
        return None
    del _pending[key]
    return issue(req["name"])


def pending_deny(key: str) -> None:
    _pending.pop(key, None)
