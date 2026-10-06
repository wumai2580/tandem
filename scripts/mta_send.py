"""MTA sender: push a file/text TO a Xiaomi phone via 互传协议.

Proof-of-concept for the wire protocol: BLE handshake -> phone joins our
hotspot -> phone connects wss -> sendRequest -> phone GETs the ZIP.

Usage: python -u scripts/mta_send.py [--text "hello"] [--file path]
       [--addr AABBCCDDEEFF]  (phone's current BLE MAC; sniff first)
"""

import asyncio
import base64
import datetime
import io
import json
import os
import ssl
import sys
import tempfile
import threading
import time
import uuid
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

SVC = uuid.UUID("00009955-0000-1000-8000-00805f9b34fb")
CHR_STATUS = uuid.UUID("00009954-0000-1000-8000-00805f9b34fb")
CHR_P2P = uuid.UUID("00009953-0000-1000-8000-00805f9b34fb")
AES_IV = b"0102030405060708"
WS_PORT = 2355


def _mk_cert():
    """Throwaway self-signed cert for the transfer socket."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "tandem-mta")])
    crt = (
        x509.CertificateBuilder()
        .subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=1))
        .not_valid_after(datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=1))
        .sign(key, hashes.SHA256())
    )
    d = tempfile.mkdtemp(prefix="mta")
    kp, cp = Path(d) / "k.pem", Path(d) / "c.pem"
    kp.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                 serialization.PrivateFormat.TraditionalOpenSSL,
                 serialization.NoEncryption()))
    cp.write_bytes(crt.public_bytes(serialization.Encoding.PEM))
    return str(cp), str(kp)


class Ecdh:
    def __init__(self):
        from cryptography.hazmat.primitives.asymmetric import ec
        self._priv = ec.generate_private_key(ec.SECP256R1())

    def pub_b64(self):
        from cryptography.hazmat.primitives import serialization
        return base64.b64encode(self._priv.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo)).decode()

    def cipher(self, peer_b64):
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        from cryptography.hazmat.primitives.serialization import load_der_public_key
        peer = load_der_public_key(base64.b64decode(peer_b64))
        secret = self._priv.exchange(ec.ECDH(), peer)
        self._cipher = Cipher(algorithms.AES(secret), modes.CTR(AES_IV))

    def enc(self, s: str) -> str:
        e = self._cipher.encryptor()
        return base64.b64encode(e.update(s.encode()) + e.finalize()).decode()


async def ble_handshake(addr: int, payload: bytes):
    from winrt.windows.devices.bluetooth import BluetoothLEDevice
    from winrt.windows.devices.bluetooth.genericattributeprofile import (
        GattCharacteristicProperties, GattWriteOption)
    from winrt.windows.storage.streams import DataReader, DataWriter

    dev = await BluetoothLEDevice.from_bluetooth_address_async(addr)
    print(f"[ble] connected: {dev.name}", flush=True)
    res = await dev.get_gatt_services_async()
    status_char = p2p_char = None
    for s in res.services:
        if str(s.uuid).lower() == str(SVC):
            cs = await s.get_characteristics_async()
            for c in cs.characteristics:
                u = str(c.uuid).lower()
                if u == str(CHR_STATUS):
                    status_char = c
                elif u == str(CHR_P2P):
                    p2p_char = c
    assert status_char and p2p_char, "mta chars not found"
    r = await status_char.read_value_async()
    rd = DataReader.from_buffer(r.value)
    raw = bytes(rd.read_buffer(r.value.length))
    print(f"[ble] peer status: {raw.decode()}", flush=True)
    return json.loads(raw), p2p_char, dev


async def ble_write(char, payload: bytes):
    from winrt.windows.devices.bluetooth.genericattributeprofile import GattWriteOption
    from winrt.windows.storage.streams import DataWriter
    w = DataWriter()
    w.write_bytes(payload)
    r = await char.write_value_with_result_and_option_async(
        w.detach_buffer(), GattWriteOption.WRITE_WITH_RESPONSE)
    print(f"[ble] p2p write status: {r.status}", flush=True)


# ----------------------------------------------------------- wss+https app --

def build_app(zip_bytes: bytes, text_to_send: str | None, done_ev: threading.Event):
    from fastapi import FastAPI, WebSocket
    from fastapi.responses import Response

    app = FastAPI()
    state = {"task": str(uuid.uuid4())[:8], "sent": False}

    @app.websocket("/websocket")
    async def ws(ws: WebSocket):
        await ws.accept()
        print("[wss] phone connected!", flush=True)

        async def send(msg):
            print(f"[wss] >> {msg}", flush=True)
            await ws.send_text(msg)

        try:
            # greet first — protocol wants the sender to negotiate version
            await send(f'action:1:versionNegotiation?{{"version":1,"threadLimit":5}}')
            while True:
                raw = await ws.receive_text()
                print(f"[wss] << {raw}", flush=True)
                parts = raw.split(":", 3)
                if len(parts) >= 3 and parts[0] == "ack":
                    name = parts[2].split("?")[0]
                    if name.lower() == "versionnegotiation" and not state["sent"]:
                        state["sent"] = True
                        if text_to_send is not None:
                            body = {"id": state["task"], "taskId": state["task"],
                                    "catShareText": text_to_send}
                        else:
                            body = {"id": state["task"], "taskId": state["task"],
                                    "files": []}
                        await send(f"action:2:sendRequest?{json.dumps(body, separators=(',', ':'))}")
                    continue
                if len(parts) >= 3 and parts[0] == "action":
                    name = parts[2].split("?")[0]
                    if name.lower() == "status":
                        print("[wss] status from phone:", raw, flush=True)
                        done_ev.set()
                        return
        except Exception as e:
            print(f"[wss] closed: {e!r}", flush=True)

    @app.get("/download")
    async def download(taskId: str = ""):
        print(f"[https] download taskId={taskId}", flush=True)
        return Response(zip_bytes, media_type="application/zip")

    return app


def serve(zip_bytes: bytes, text_to_send, done_ev):
    import uvicorn
    cert, key = _mk_cert()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    app = build_app(zip_bytes, text_to_send, done_ev)
    cfg = uvicorn.Config(app, host="0.0.0.0", port=WS_PORT, log_level="error",
                         ssl_certfile=cert, ssl_keyfile=key)
    uvicorn.Server(cfg).run()


ADV_MTA = uuid.UUID("00003331-0000-1000-8000-008123456789")


def find_phone(timeout=15) -> int:
    """Return the freshest advertiser carrying 0x3331 (phone rotates RPAs)."""
    from winrt.windows.devices.bluetooth.advertisement import (
        BluetoothLEAdvertisementWatcher, BluetoothLEScanningMode)

    got = {}
    w = BluetoothLEAdvertisementWatcher()
    w.scanning_mode = BluetoothLEScanningMode.ACTIVE

    def on_recv(_w, a):
        try:
            if str(ADV_MTA) in [str(u) for u in a.advertisement.service_uuids]:
                got[a.bluetooth_address] = time.time()
        except Exception:
            pass

    w.add_received(on_recv)
    w.start()
    deadline = time.time() + timeout
    while time.time() < deadline and not got:
        time.sleep(0.5)
    w.stop()
    assert got, "no 0x3331 advertiser seen — open 互传/接收 mode on the phone"
    addr = max(got, key=got.get)
    print(f"[scan] freshest MTA adv: {addr:012X}", flush=True)
    return addr


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--addr", default="auto", help="phone BLE addr hex, no colons")
    ap.add_argument("--text")
    ap.add_argument("--file")
    args = ap.parse_args()
    addr = find_phone() if args.addr == "auto" else int(args.addr, 16)

    # hotspot must already be up (we reuse CampusShare)
    from tandem import hotspot
    st = hotspot.status()
    ssid, psk = st.get("ssid"), st.get("pass")
    host_ip = hotspot.hotspot_ip()
    print(f"[hotspot] ssid={ssid} ip={host_ip} state={st.get('state')}", flush=True)
    assert ssid and host_ip, "hotspot not running"

    zip_bytes = b""
    if args.file:
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.write(args.file, Path(args.file).name)
        zip_bytes = buf.getvalue()

    done_ev = threading.Event()
    t = threading.Thread(target=serve, args=(zip_bytes, args.text, done_ev), daemon=True)
    t.start()
    time.sleep(1.5)

    ecdh = Ecdh()

    async def go():
        status, p2p, dev = await ble_handshake(addr, b"")
        ecdh.cipher(status["key"])
        # mac = sender's P2P/GO device address (the phone calls
        # WifiP2pManager.connect with it) — for Windows hotspot that's the
        # Wi-Fi Direct virtual adapter MAC.
        go_mac = os.environ.get("MTA_GO_MAC", "92:74:ae:53:6e:70")
        body = {
            "ssid": ecdh.enc(ssid),
            "psk": ecdh.enc(psk),
            "mac": ecdh.enc(go_mac),
            "port": str(WS_PORT),
            "key": ecdh.pub_b64(),
        }
        payload = json.dumps(body, separators=(",", ":")).encode()
        print(f"[ble] writing p2p: {len(payload)}B", flush=True)
        await ble_write(p2p, payload)
        print("[ble] credentials sent — phone should join the hotspot", flush=True)

    asyncio.run(go())
    try:
        done_ev.wait(120)
        print("[done] status received / timeout", flush=True)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
