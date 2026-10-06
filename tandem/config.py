import json
import secrets
import socket
from pathlib import Path

from platformdirs import user_config_dir, user_data_dir

APP_NAME = "tandem"
DEFAULT_PORT = 9787


def config_path() -> Path:
    p = Path(user_config_dir(APP_NAME))
    p.mkdir(parents=True, exist_ok=True)
    return p / "config.json"


def data_dir() -> Path:
    p = Path(user_data_dir(APP_NAME)) / "inbox"
    p.mkdir(parents=True, exist_ok=True)
    return p


def load_config() -> dict:
    path = config_path()
    if path.exists():
        cfg = json.loads(path.read_text(encoding="utf-8"))
    else:
        cfg = {}
    changed = False
    if not cfg.get("token"):
        cfg["token"] = secrets.token_urlsafe(18)
        changed = True
    if not cfg.get("port"):
        cfg["port"] = DEFAULT_PORT
        changed = True
    if not cfg.get("device_name"):
        cfg["device_name"] = socket.gethostname()
        changed = True
    if changed:
        path.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    return cfg


def lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 80))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
