import json
import time
import uuid
from pathlib import Path

from .config import data_dir

INDEX = "index.json"
MAX_TEXT_BYTES = 1024 * 1024


def _index_path() -> Path:
    return data_dir() / INDEX


def _load() -> list[dict]:
    p = _index_path()
    if p.exists():
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return []
    return []


def _save(items: list[dict]) -> None:
    _index_path().write_text(json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")


def list_items() -> list[dict]:
    return _load()


def _add(item: dict) -> dict:
    items = _load()
    items.insert(0, item)
    _save(items[:500])
    return item


def add_text(text: str, origin: str = "") -> dict:
    return _add(
        {
            "id": uuid.uuid4().hex[:12],
            "kind": "text",
            "text": text[:MAX_TEXT_BYTES],
            "ts": time.time(),
            "from": origin,
        }
    )


def add_file(filename: str, body: bytes, origin: str = "") -> dict:
    safe = Path(filename).name or "file"
    fid = uuid.uuid4().hex[:12]
    dest = data_dir() / f"{fid}_{safe}"
    dest.write_bytes(body)
    return _add(_file_item(fid, safe, len(body), dest.name, origin))


CHUNK = 1 << 20


async def save_upload(upload, origin: str = "") -> dict:
    """Stream an UploadFile to disk — constant memory regardless of file size."""
    safe = Path(upload.filename or "file").name or "file"
    fid = uuid.uuid4().hex[:12]
    dest = data_dir() / f"{fid}_{safe}"
    size = 0
    try:
        with dest.open("wb") as f:
            while chunk := await upload.read(CHUNK):
                f.write(chunk)
                size += len(chunk)
    except Exception:
        dest.unlink(missing_ok=True)
        raise
    return _add(_file_item(fid, safe, size, dest.name, origin))


def _file_item(fid: str, name: str, size: int, stored: str, origin: str) -> dict:
    return {
        "id": fid,
        "kind": "file",
        "name": name,
        "size": size,
        "path": stored,
        "ts": time.time(),
        "from": origin,
    }


def file_path(item_id: str) -> Path | None:
    for it in _load():
        if it["id"] == item_id and it["kind"] == "file":
            p = data_dir() / it["path"]
            if p.exists():
                return p
    return None


def delete_item(item_id: str) -> bool:
    items = _load()
    kept = []
    found = False
    for it in items:
        if it["id"] == item_id:
            found = True
            if it["kind"] == "file":
                p = data_dir() / it.get("path", "")
                p.unlink(missing_ok=True)
        else:
            kept.append(it)
    if found:
        _save(kept)
    return found
