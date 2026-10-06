import asyncio
import io
import json
import re
import time
import webbrowser
from pathlib import Path

import qrcode
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, Request, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles

from . import __version__
from .certs import ca_cert_path
from .clipboard import ClipboardSync
from .config import lan_ip, load_config
from .discovery import Announcer
from . import devices, hotspot, store

STATIC_DIR = Path(__file__).parent / "static"
URL_RE = re.compile(r"^https?://", re.I)


class Hub:
    def __init__(self) -> None:
        self.clients: set[WebSocket] = set()

    async def broadcast(self, event: dict, exclude: WebSocket | None = None) -> None:
        msg = json.dumps(event, ensure_ascii=False)
        dead = []
        for ws in self.clients:
            if ws is exclude:
                continue
            try:
                await ws.send_text(msg)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.clients.discard(ws)


hub = Hub()
clipboard: ClipboardSync | None = None
beacon = None  # BLE pairing beacon, started in _startup


def create_app() -> FastAPI:
    cfg = load_config()
    announcer = Announcer()
    app = FastAPI(title="tandem", docs_url=None, redoc_url=None)

    def advertised_ip() -> str:
        # While the hotspot is up, phones reach the hub on the ICS address.
        return hotspot.hotspot_ip() or lan_ip()

    def authed(token: str | None = Query(None), authorization: str | None = Header(None)) -> None:
        supplied = token or (authorization or "").removeprefix("Bearer ").strip() or None
        if supplied and supplied != cfg["token"] and devices.verify(supplied):
            return
        if supplied != cfg["token"]:
            raise HTTPException(status_code=401, detail="bad token")

    @app.on_event("startup")
    async def _startup() -> None:
        global clipboard, beacon
        clipboard = ClipboardSync(
            lambda text: hub.broadcast({"type": "clipboard", "text": text})
        )
        clipboard.start()
        try:
            announcer.start(cfg["device_name"], cfg["port"])
        except Exception:
            pass
        try:
            from .ble import BleBeacon

            def _info():
                ips = {advertised_ip(), lan_ip()}
                hs = hotspot.hotspot_ip()
                if hs:
                    ips.add(hs)
                return {"n": cfg["device_name"], "p": cfg["port"], "ips": sorted(ips)}

            beacon = BleBeacon(_info, lambda name, key: devices.pending_begin(key, name))
            beacon.start()
        except Exception:
            beacon = None

    @app.on_event("shutdown")
    async def _shutdown() -> None:
        announcer.stop()
        if beacon:
            beacon.stop()

    # ---------- pairing ----------
    @app.get("/api/info")
    async def info():
        return {
            "name": cfg["device_name"],
            "version": __version__,
            "url": f"https://{lan_ip()}:{cfg['port']}/",
        }

    @app.get("/ca.crt")
    async def ca_cert():
        # The local CA's public cert — safe to serve unauthenticated.
        return FileResponse(ca_cert_path(), filename="tandem-ca.crt")

    def png_qr(text: str) -> Response:
        img = qrcode.make(text)
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return Response(content=buf.getvalue(), media_type="image/png")

    @app.get("/api/pair/qr.png", dependencies=[Depends(authed)])
    async def pair_qr(net: str = "auto"):
        ip = lan_ip() if net == "lan" else advertised_ip()
        return png_qr(f"https://{ip}:{cfg['port']}/?token={cfg['token']}")

    # ---------- device credentials (adb-style) ----------
    @app.post("/api/pair")
    async def pair(
        token: str = Form(""), name: str = Form("device"), key: str = Form("")
    ):
        # Two paths in: (a) QR/master token — immediate; (b) BLE-written key
        # that the user approved on the PC (/pair page). Returns a permanent
        # per-device credential either way.
        if token and token == cfg["token"]:
            return {**devices.issue(name), "hub_name": cfg["device_name"]}
        if key:
            cred = devices.pending_redeem(key)
            if cred:
                return {**cred, "hub_name": cfg["device_name"]}
            raise HTTPException(status_code=202, detail="pending approval")
        raise HTTPException(status_code=401, detail="bad token")

    @app.get("/api/devices", dependencies=[Depends(authed)])
    async def list_devices():
        return devices.list_devices()

    @app.delete("/api/devices/{device_id}", dependencies=[Depends(authed)])
    async def revoke_device(device_id: str):
        if not devices.revoke(device_id):
            raise HTTPException(404)
        return {"ok": True}

    # ---------- hotspot ("base station") mode ----------
    @app.get("/api/hotspot", dependencies=[Depends(authed)])
    async def hotspot_status():
        st = await asyncio.to_thread(hotspot.status)
        st["ip"] = hotspot.hotspot_ip()
        return st

    @app.post("/api/hotspot", dependencies=[Depends(authed)])
    async def hotspot_ctl(action: str = Form(...)):
        if action == "start":
            st = await asyncio.to_thread(hotspot.start)
        elif action == "stop":
            st = await asyncio.to_thread(hotspot.stop)
        else:
            raise HTTPException(400, "action must be start|stop")
        st["ip"] = hotspot.hotspot_ip()
        return st

    @app.get("/api/hotspot/qr.png", dependencies=[Depends(authed)])
    async def hotspot_qr():
        st = await asyncio.to_thread(hotspot.status)
        if st.get("state") != "On" or not st.get("ssid"):
            raise HTTPException(404, "hotspot not running")
        return png_qr(hotspot.wifi_qr_text(st["ssid"], st.get("pass") or ""))

    # ---------- pairing page ----------
    @app.get("/pair", dependencies=[Depends(authed)], response_class=HTMLResponse)
    async def pair_page():
        st = await asyncio.to_thread(hotspot.status)
        hs = ""
        if st.get("supported"):
            if st.get("state") == "On":
                hs = """
                <h2>第 1 步：手机加入电脑热点</h2>
                <p>用<b>系统相机</b>扫此码加入 <b>{ssid}</b>（也可用 App 内扫码）:</p>
                <img src="/api/hotspot/qr.png?token={t}" width="220">
                <p>密码: <b>{pw}</b>（电脑本身的网络不会断）</p>
                <h2>第 2 步：入网后扫配对码</h2>
                <img src="/api/pair/qr.png?token={t}" width="200">
                <form method="post" action="/pair/hotspot?token={t}"><input type="hidden" name="action" value="stop"><button>关闭热点</button></form>
                """.format(t=cfg["token"], ssid=st.get("ssid"), pw=st.get("pass"))
            else:
                hs = """
                <h2>不在同一局域网？</h2>
                <p>开启基站模式：电脑开热点，手机扫码直连（不会断开电脑当前网络）。</p>
                <form method="post" action="/pair/hotspot?token={t}"><input type="hidden" name="action" value="start"><button>开启热点</button></form>
                """.format(t=cfg["token"])
            if st.get("state") == "On":
                hs += """
                <details><summary>手机已在同一局域网，不想用热点？</summary>
                <img src="/api/pair/qr.png?net=lan&token={t}" width="200"></details>
                """.format(t=cfg["token"])
        pending_rows = "".join(
            "<tr><td>{}</td><td><form method='post' action='/pair/approve?token={t}' style='display:inline'><input type='hidden' name='key' value='{k}'><button>批准</button></form> "
            "<form method='post' action='/pair/deny?token={t}' style='display:inline'><input type='hidden' name='key' value='{k}'><button>拒绝</button></form></td></tr>".format(
                p["name"], k=p["key"], t=cfg["token"])
            for p in devices.pending_list())
        pend = (f"<h2>配对请求（蓝牙）</h2><table>{pending_rows}</table>" if pending_rows else "")
        dev_rows = "".join(
            "<tr><td>{}</td><td>{}</td><td><form method='post' action='/pair/revoke?token={t}' onsubmit='return confirm(\"revoke?\")'><input type='hidden' name='id' value='{}'><button>吊销</button></form></td></tr>".format(
                d["name"], time.strftime("%m-%d %H:%M", time.localtime(d["last_seen"] or d["created"])), d["id"], t=cfg["token"])
            for d in devices.list_devices())
        return f"""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Tandem — Pair</title>
        <body style="font-family:system-ui;max-width:560px;margin:2em auto;padding:0 1em;background:#0b1020;color:#e8ecf6">
        <h1>Tandem 配对 / Pair devices</h1>
        <img src="/api/pair/qr.png?token={cfg['token']}" width="240">
        <p>用手机 Tandem App 扫此码。凭证将自动保存，之后免扫码。</p>
        {hs}
        {pend}
        <h2>已配对设备</h2>
        <table style="width:100%">{dev_rows or "<tr><td>暂无设备</td></tr>"}</table>
        <style>img{{background:#fff;padding:8px;border-radius:8px}} button{{padding:6px 14px}} td{{padding:6px 4px}}</style>
        </body>"""

    @app.post("/pair/hotspot", dependencies=[Depends(authed)])
    async def pair_hotspot(action: str = Form(...), token: str = Query("")):
        await asyncio.to_thread(hotspot.start if action == "start" else hotspot.stop)
        return RedirectResponse(f"/pair?token={token}", status_code=303)

    @app.post("/pair/revoke", dependencies=[Depends(authed)])
    async def pair_revoke(id: str = Form(""), token: str = Query("")):
        devices.revoke(id)
        return RedirectResponse(f"/pair?token={token}", status_code=303)

    @app.post("/pair/approve", dependencies=[Depends(authed)])
    async def pair_approve(key: str = Form(""), token: str = Query("")):
        devices.pending_approve(key)
        return RedirectResponse(f"/pair?token={token}", status_code=303)

    @app.post("/pair/deny", dependencies=[Depends(authed)])
    async def pair_deny(key: str = Form(""), token: str = Query("")):
        devices.pending_deny(key)
        return RedirectResponse(f"/pair?token={token}", status_code=303)

    # ---------- inbox / files ----------
    @app.get("/api/items", dependencies=[Depends(authed)])
    async def items():
        return store.list_items()

    @app.post("/api/files", dependencies=[Depends(authed)])
    async def upload(file: UploadFile = File(...), origin: str = Form("")):
        item = await store.save_upload(file, origin)
        await hub.broadcast({"type": "file", "item": item})
        return item

    @app.get("/api/files/{item_id}", dependencies=[Depends(authed)])
    async def download(item_id: str):
        p = store.file_path(item_id)
        if not p:
            raise HTTPException(404)
        return FileResponse(p, filename=p.name.split("_", 1)[-1])

    @app.delete("/api/items/{item_id}", dependencies=[Depends(authed)])
    async def delete_item(item_id: str):
        if not store.delete_item(item_id):
            raise HTTPException(404)
        return {"ok": True}

    # ---------- text / clipboard / links ----------
    @app.post("/api/text", dependencies=[Depends(authed)])
    async def send_text(text: str = Form(...), origin: str = Form("")):
        if URL_RE.match(text.strip()):
            url = text.strip()
            webbrowser.open(url)
            await hub.broadcast({"type": "link", "url": url, "from": origin})
            return {"kind": "link", "url": url}
        item = store.add_text(text, origin)
        await hub.broadcast({"type": "text", "item": item})
        return item

    @app.post("/api/clipboard", dependencies=[Depends(authed)])
    async def push_clipboard(request: Request):
        body = await request.json()
        text = str(body.get("text", ""))
        if not text:
            raise HTTPException(400)
        clipboard.set(text)
        await hub.broadcast({"type": "clipboard", "text": text})
        return {"ok": True}

    @app.get("/api/clipboard", dependencies=[Depends(authed)])
    async def get_clipboard():
        return {"text": clipboard.last}

    # ---------- Web Share Target (Android) ----------
    @app.post("/api/share")
    async def share_target(
        request: Request,
        title: str = Form(""),
        text: str = Form(""),
        url: str = Form(""),
        files: list[UploadFile] = File(default=[]),
    ):
        shared_url = url or (text if URL_RE.match(text or "") else "")
        for f in files:
            if f.size == 0:
                continue
            item = await store.save_upload(f, "share")
            await hub.broadcast({"type": "file", "item": item})
        if shared_url:
            webbrowser.open(shared_url)
            await hub.broadcast({"type": "link", "url": shared_url, "from": "share"})
        elif text and not files:
            item = store.add_text(text, "share")
            await hub.broadcast({"type": "text", "item": item})
        return RedirectResponse("/?shared=1", status_code=303)

    # ---------- websocket ----------
    @app.websocket("/ws")
    async def ws(websocket: WebSocket, token: str = ""):
        if token != cfg["token"] and not devices.verify(token):
            await websocket.close(code=4401)
            return
        await websocket.accept()
        hub.clients.add(websocket)
        try:
            while True:
                data = json.loads(await websocket.receive_text())
                if data.get("type") == "clipboard" and data.get("text"):
                    clipboard.set(data["text"])
                    await hub.broadcast(
                        {"type": "clipboard", "text": data["text"]}, exclude=websocket
                    )
        except WebSocketDisconnect:
            pass
        finally:
            hub.clients.discard(websocket)

    # ---------- PWA ----------
    if STATIC_DIR.exists():
        app.mount("/assets", StaticFiles(directory=STATIC_DIR / "assets"), name="assets")

        @app.get("/{full_path:path}")
        async def pwa(full_path: str):
            # serve real static files (manifest, icons, sw.js); SPA fallback to index.html
            candidate = (STATIC_DIR / full_path).resolve()
            if full_path and candidate.is_file() and str(candidate).startswith(str(STATIC_DIR.resolve())):
                return FileResponse(candidate)
            return FileResponse(STATIC_DIR / "index.html")
    else:

        @app.get("/", response_class=HTMLResponse)
        async def not_built():
            return "<h1>tandem</h1><p>Web UI not built. Run <code>cd web && npm run build</code>.</p>"

    return app
