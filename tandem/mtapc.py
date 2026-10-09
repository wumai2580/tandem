"""MiShare PC-mode (mfr-911 BLE) receiver — phone→PC transfers without GATT.

Reverse engineered from com.miui.mishare.connectivity (HyperOS 3).

Wire format (manufacturer data, company id 911 / 0x038F):
  ability packet (we advertise, subtype 0):
    [0]    total_len = 12 + nameLen
    [1,3]  0x11 0x00 0x11 magic
    [4]    version: 0x12 (or 0x02 when the "short" variant is used)
    [5]    (nameLen<<4)|3  -> TLV type-3 = device name (<=12 bytes utf-8)
    [..]   0x24           -> TLV type-4 len2 = device id u16 LE
    [..]   0x38           -> TLV type-8 len3 = app0 {caps0, caps1, devCode}
  transfer offer (phone advertises, app0 subtype 1):
    type-8 TLV whose first byte &7 == 1:
      [flags, fileCount, channel, toDevId(2B), taskId(2B), nonce(2B), name(6B)]
      flags bit4 = support2_0 (DIRECT- ssid) ; trailing [0x2F, ip2, ip3]
  credentials:
    ssid = "ap_mishare_%04x" % senderDevId   (2.0 -> "DIRECT-%04x")
    psk  = b64(AES-CBC(key,iv).encrypt([devIdLE,nonceLE,name4B]))[:8]

Then the phone hosts https://192.168.<ip2>.<ip3>:9999/api/1.0/* (NanoHTTPD,
self-signed) — fileinfo / file?id= / report / taskcomplete / fileverify.

Windows-only implementation.
"""

import asyncio
import base64
import json
import os
import random
import re
import ssl
import struct
import subprocess
import threading
import time
import zlib
from pathlib import Path

from Crypto.Cipher import AES  # pycryptodome already in deps (certs.py)

MFR_ID = 911
_PSK_KEY = bytes.fromhex("15c8761d9e36456b9af35e1d084665a5")
_PSK_IV = bytes.fromhex("02050b11171f292f3b43495361676d7f")


def _get(op, timeout: float = 10.0):
    if hasattr(op, "get"):
        return op.get()
    done = threading.Event()
    box = {}

    def _completed(o, _s):
        box["r"] = o.get_results()
        done.set()

    op.completed = _completed
    done.wait(timeout)
    return box.get("r")


def derive_psk(dev_id: int, name: bytes, nonce: int) -> str:
    blk = bytes([dev_id & 0xFF, (dev_id >> 8) & 0xFF,
                 nonce & 0xFF, (nonce >> 8) & 0xFF]) + name[:4].ljust(4, b"\x00")
    # Android uses AES/CBC/PKCS5Padding -> pad the 8-byte block to 16
    blk += b"\x08" * 8
    ct = AES.new(_PSK_KEY, AES.MODE_CBC, _PSK_IV).encrypt(blk)
    return base64.b64encode(ct).decode()[:8]


def build_ability(name: str, dev_id: int, caps0: int = 0x40,
                  caps1: int = 0x0C, dev_code: int = 0x30) -> bytes:
    """d.a() — PC ability packet (subtype 0).

    Verified on-device: the phone parsed TLV 0x24 correctly (devId read back
    verbatim), so the canonical builder layout is the right one — no padding.
    caps0 bit3 (a) must be 0 — p079n1.b.n() drops the device otherwise.
    caps0 bit6 (d)=1 & b=c=0 -> phone marks us i6=0 (idle/available).
    caps1 bit4 (support2_0) stays 0 -> phone takes the legacy soft-AP path.
    """
    nb = name.encode("utf-8")[:12]
    d = bytearray()
    d.append(len(nb) + 12)
    d += b"\x11\x00\x11\x12"
    d.append((len(nb) << 4) | 3)
    d += nb
    d.append(0x24)
    d += bytes([dev_id & 0xFF, (dev_id >> 8) & 0xFF])
    d.append(0x38)
    d += bytes([caps0, caps1, dev_code])
    return bytes(d)


def build_response(our_dev_id: int, sender_dev_id: int, task_id: int,
                   accept: bool = True) -> bytes:
    """d.c() — subtype-3 response to a transfer offer.

    Packet: {0d,11,00,11,12, 24,<devLE>, 78, (accept?0x13:0x23),
             <senderLE>, <taskLE>}.  The sender scans with filter
    m(senderDevId, taskId) + mask n(): requires [9]&7==3, [10-11]=its own
    devId, [12-13]=taskId.  action byte &248: 16=accept, 32=refuse.
    """
    return bytes([
        0x0D, 0x11, 0x00, 0x11, 0x12, 0x24,
        our_dev_id & 0xFF, (our_dev_id >> 8) & 0xFF,
        0x78, (0x23 if not accept else 0x13),
        sender_dev_id & 0xFF, (sender_dev_id >> 8) & 0xFF,
        task_id & 0xFF, (task_id >> 8) & 0xFF,
    ])


def parse_tlv(payload: bytes):
    """Walk the TLV stream of a mfr-911 payload; return dict of finds."""
    out = {"name": None, "dev_id": None, "app0": None, "ok": False}
    if len(payload) < 6 or payload[1] != 0x11 or payload[3] != 0x11:
        return out
    if payload[4] not in (0x12, 0x02):
        return out
    out["ok"] = True
    i = 5
    guard = 0
    while i < len(payload) and guard < 10:
        guard += 1
        tag = payload[i]
        t = tag & 15
        if t == 0 or t == 1 or t == 2:
            i += 1
        elif t == 3:
            ln = (tag >> 4) & 15
            if i + 1 + ln <= len(payload):
                out["name"] = payload[i + 1:i + 1 + ln]
                i += 1 + ln
            else:
                break
        elif t == 4:
            if i + 2 < len(payload):
                out["dev_id"] = payload[i + 1] | (payload[i + 2] << 8)
            i += 3
        elif t == 8:
            ln = (tag >> 4) & 15
            if i + 1 + ln <= len(payload):
                out["app0"] = payload[i + 1:i + 1 + ln]
                i += 1 + ln
            else:
                break
        elif t == 15:
            i += 1
        else:
            break
    return out


def parse_offer(payload: bytes):
    """Parse subtype-1 transfer packet -> dict or None."""
    tlv = parse_tlv(payload)
    app0 = tlv.get("app0")
    if not app0 or (app0[0] & 7) != 1 or len(app0) < 9:
        return None
    flags = app0[0]
    o = {
        "sender_dev_id": tlv["dev_id"],
        "flags": flags,
        "support2_0": bool(flags & 16),
        "file_count": app0[1],
        "channel": app0[2],
        "to_dev_id": app0[3] | (app0[4] << 8),
        "task_id": app0[5] | (app0[6] << 8),
        "nonce": app0[7] | (app0[8] << 8),
        "name": app0[9:].decode("utf-8", "replace"),
        "ip": None,
    }
    # trailing [0x2F, ip2, ip3]
    i = payload.rfind(b"\x2f")
    if 0 < i + 2 < len(payload) + 1 and i + 2 <= len(payload) - 1:
        o["ip"] = f"192.168.{payload[i + 1]}.{payload[i + 2]}"
    return o


def _netsh(*args) -> str:
    return subprocess.run(["netsh"] + list(args), capture_output=True,
                          timeout=20).stdout.decode("gbk", "replace")


def _wlan_profile(ssid: str, psk: str, path: str) -> str:
    xml = f"""<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
  <name>{ssid}</name>
  <SSIDConfig><SSID><name>{ssid}</name></SSID><nonBroadcast>true</nonBroadcast></SSIDConfig>
  <connectionType>ESS</connectionType>
  <connectionMode>auto</connectionMode>
  <MSM>
    <security>
      <authEncryption>
        <authentication>WPA2PSK</authentication>
        <encryption>AES</encryption>
        <useOneX>false</useOneX>
      </authEncryption>
      <sharedKey>
        <keyType>passPhrase</keyType>
        <protected>false</protected>
        <keyMaterial>{psk}</keyMaterial>
      </sharedKey>
    </security>
  </MSM>
</WLANProfile>"""
    with open(path, "w", encoding="utf-8") as fp:
        fp.write(xml)
    return path


def join_phone_ap(offer: dict, log=print, timeout: float = 25.0) -> str:
    sid = offer["sender_dev_id"]
    if sid is None:
        raise RuntimeError("offer missing sender device id")
    ssid = ("DIRECT-%04x" if offer["support2_0"] else "ap_mishare_%04x") % sid
    name_b = offer["name"].encode("utf-8")
    psk = derive_psk(sid, b"" if offer["support2_0"] else name_b, offer["nonce"])
    log(f"[mtapc] joining {ssid} psk={psk} ip={offer['ip']}")
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as f:
        path = f.name
    _wlan_profile(ssid, psk, path)
    _netsh("wlan", "add", "profile", f"filename={path}", "user=all")
    try:
        os.unlink(path)
    except OSError:
        pass
    _netsh("wlan", "connect", f"name={ssid}")
    t0 = time.time()
    while time.time() - t0 < timeout:
        out = _netsh("wlan", "show", "interfaces")
        # zh-CN: "状态 : 已连接" / en-US: "State : connected"
        if ssid in out and ("connected" in out.lower() or "已连接" in out):
            return ssid
        time.sleep(1.0)
    raise TimeoutError(f"failed to join {ssid}")


def pull_files(offer: dict, out_dir: Path, log=print,
               user: str = None) -> list:
    """HTTPS pull from the phone's NanoHTTPD :9999."""
    import urllib.request

    ip = offer["ip"] or "192.168.43.1"
    uid = user or str(offer["to_dev_id"])
    base = f"https://{ip}:9999/api/1.0"
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    def get(path):
        url = f"{base}/{path}"
        log(f"[mtapc] GET {url}")
        req = urllib.request.Request(url, headers={"Accept": "application/json",
                                                   "Connection": "keep-alive"})
        return urllib.request.urlopen(req, context=ctx, timeout=15)

    def post(path, body: dict):
        url = f"{base}/{path}"
        log(f"[mtapc] POST {url} {body}")
        req = urllib.request.Request(
            url, data=json.dumps(body).encode(), method="POST",
            headers={"Content-Type": "application/json",
                     "Accept": "application/json", "Connection": "keep-alive"})
        return urllib.request.urlopen(req, context=ctx, timeout=15)

    # task id on the wire is the *decimal* string (p079n1.b.u = toString)
    task = str(offer["task_id"])
    info = json.loads(get(f"fileinfo?user={uid}&task={task}").read().decode())
    files = info.get("data") or []
    saved = []
    for fm in files:
        fid, name = fm.get("id"), fm.get("name") or "file"
        resp = get(f"file?user={uid}&task={task}&id={fid}")
        data = resp.read()
        out = out_dir / f"{offer['task_id']:04x}_{name.replace('/', '_')}"
        out.write_bytes(data)
        log(f"[mtapc] saved {out.name} ({len(data)}B)")
        saved.append(out)
    try:
        post(f"taskcomplete?user={uid}&task={task}",
             {"status": 0, "data": {}})
    except Exception as e5:
        log(f"[mtapc] taskcomplete err: {e5}")
    return saved


class MtaPcReceiver:
    """Ability advert + offer watcher + transfer runner."""

    def __init__(self, name: str, out_dir: Path, log=print):
        self.name = name[:12]
        self.out_dir = Path(out_dir)
        # stable per machine so the phone sees a consistent identity
        self.dev_id = 0x4000 | (zlib.crc32(name.encode()) & 0x3FFF)
        self.log = log
        self._pub = None
        self._watch = None
        self._seen = set()
        self._busy = threading.Lock()

    # ---------------- advertising ----------------
    def _set_payload(self, data: bytes):
        """Swap the manufacturer payload of the running publisher."""
        from winrt.windows.devices.bluetooth.advertisement import (
            BluetoothLEManufacturerData)
        from winrt.windows.storage.streams import DataWriter
        self._pub.stop()
        adv = self._pub.advertisement
        adv.manufacturer_data.clear()
        md = BluetoothLEManufacturerData()
        md.company_id = MFR_ID
        w = DataWriter()
        w.write_bytes(data)
        md.data = w.detach_buffer()
        adv.manufacturer_data.append(md)
        self._pub.start()
        time.sleep(0.3)

    def start(self):
        from winrt.windows.devices.bluetooth.advertisement import (
            BluetoothLEAdvertisement,
            BluetoothLEManufacturerData,
            BluetoothLEAdvertisementPublisher,
            BluetoothLEAdvertisementWatcher,
        )
        from winrt.windows.storage.streams import DataWriter

        self._ability = build_ability(self.name, self.dev_id)
        adv = BluetoothLEAdvertisement()
        md = BluetoothLEManufacturerData()
        md.company_id = MFR_ID
        w = DataWriter()
        w.write_bytes(self._ability)
        md.data = w.detach_buffer()
        adv.manufacturer_data.append(md)
        self._pub = BluetoothLEAdvertisementPublisher(adv)
        self._pub.start()
        time.sleep(0.5)
        self.log(f"[mtapc] ability advert status={self._pub.status} "
                 f"devId={self.dev_id:04x} name={self.name} pkt="
                 f"{self._ability.hex()}")

        w2 = BluetoothLEAdvertisementWatcher()
        w2.add_received(self._on_adv)
        w2.start()
        self._watch = w2
        self.log("[mtapc] watching for transfer offers")

    def stop(self):
        for o in (self._pub, self._watch):
            try:
                o and o.stop()
            except Exception:
                pass

    # ---------------- scan ----------------
    def _on_adv(self, _w, args):
        try:
            adv = args.advertisement
            sections = getattr(adv, "manufacturer_data_sections", None)
            if sections is None:
                sections = getattr(adv, "manufacturer_data", [])
            for sec in sections:
                if sec.company_id != MFR_ID:
                    continue
                from winrt.windows.storage.streams import DataReader
                r = DataReader.from_buffer(sec.data)
                buf = r.read_buffer(r.unconsumed_buffer_length)
                payload = bytes(buf)
                if payload and payload[1] == 0x11 and payload[3] == 0x11:
                    self.log(f"[mtapc] rx {len(payload)}B {payload.hex()}")
                offer = parse_offer(payload)
                if not offer:
                    continue
                self.log(f"[mtapc] offer to={offer['to_dev_id']:04x} "
                         f"ours={self.dev_id:04x} sender="
                         f"{offer['sender_dev_id']:04x} task="
                         f"{offer['task_id']}")
                if offer["to_dev_id"] != self.dev_id:
                    self.log("[mtapc] (addressed to a stale devId — "
                             "accepting anyway, single-PC env)")
                key = (offer["task_id"], offer["nonce"])
                if key in self._seen:
                    continue
                self._seen.add(key)
                self.log(f"[mtapc] OFFER {offer}")
                threading.Thread(target=self._handle, args=(offer,),
                                 daemon=True).start()
        except Exception as e5:
            self.log(f"[mtapc] scan err {e5}")

    def _respond_thread(self, offer: dict, seconds: float = 12.0):
        """Swap the publisher to the subtype-3 accept packet for a while."""
        try:
            resp = build_response(self.dev_id, offer["sender_dev_id"],
                                  offer["task_id"], accept=True)
            self._set_payload(resp)
            self.log(f"[mtapc] advertising ACCEPT resp {resp.hex()}")
            time.sleep(seconds)
        except Exception as e5:
            self.log(f"[mtapc] resp err {e5}")
        finally:
            try:
                self._set_payload(self._ability)
                self.log("[mtapc] ability advert restored")
            except Exception as e5:
                self.log(f"[mtapc] restore err {e5}")

    def _handle(self, offer: dict):
        if not self._busy.acquire(blocking=False):
            return
        try:
            # remember the network we are about to leave so we can come back
            cur = _netsh("wlan", "show", "interfaces")
            m = re.search(r"SSID\s*:\s*(.+)", cur)
            home_ssid = m.group(1).strip() if m else None
            # 1) tell the phone we accept (it scans ~30s for this)
            threading.Thread(target=self._respond_thread, args=(offer,),
                             daemon=True).start()
            # 2) join the phone's AP and pull the files over HTTPS
            ssid = join_phone_ap(offer, self.log)
            files = pull_files(offer, self.out_dir, self.log)
            self.log(f"[mtapc] done: {[f.name for f in files]}")
            # 3) leave the temporary AP
            if home_ssid and home_ssid != ssid:
                self.log(f"[mtapc] rejoining {home_ssid}")
                _netsh("wlan", "connect", f"name={home_ssid}")
        except Exception as e5:
            self.log(f"[mtapc] transfer failed: {e5}")
        finally:
            self._busy.release()


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default=os.environ.get("COMPUTERNAME", "TANDEM-PC"))
    ap.add_argument("--out", default=str(
        Path(os.environ.get("LOCALAPPDATA", ".")) / "tandem" / "tandem" / "inbox"))
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    r = MtaPcReceiver(a.name, out)
    r.start()
    print("[mtapc] running — send a file to this PC from 小米互传 share sheet")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        r.stop()


if __name__ == "__main__":
    main()
