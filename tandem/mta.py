"""MTA (互传联盟) receiver — appear in Xiaomi/OPPO/vivo native share menus.

Protocol (independently implemented from the public wire format):
  1. BLE advertise service 00003331-0000-1000-8000-008123456789.
     Advert layouts differ per vendor (``--profile``):
       xiaomi: svcdata 0x1b1e <rand16><4x00>; name in svcdata 0xffff
               blob 8x00 + 2B id + name[16] + 0x01  (captured, Mi 17 Pro)
       oppo:   svcdata 0x012a <6 ascii id>; scan-rsp svcdata 0x0204
               <10B prefix><name16 pad0><0x01>; companion uuid16 0x3334
               + svcdata 0x3334 <name>  (captured, OnePlus bgm145632)
     The share-list filter keys on serviceData sizes (6B = brand/5GHz via
     the svcdata uuid, 27B = senderId+name)
     NOTE: OPPO emits uuid + svcdata in ONE advert — impossible via WinRT
     (uuid sections are ACCESS_DENIED, GATT adverts can't carry svcdata).
     For OPPO use the bluez path documented in AGENTS.md instead.
  2. Phone connects to GATT service 00009955-...:
       0x9954 read  -> {"state":0,"mac":"..","key":"<b64 SPKI P-256 pubkey>"}
       0x9953 write -> {"ssid","psk","mac","port","key"?:<sender pubkey>}
       (if "key" present, ssid/psk/mac are AES-256-CTR encrypted under the
       ECDH-derived secret; IV = b"0102030405060708")
  3. We join the phone's DIRECT-xxxx WPA2 network via netsh (the main Wi-Fi
     leaves for a few seconds, then rejoins automatically).
  4. wss://<phone>:<port>/websocket (self-signed) — MTA message format
     "type:id:name?json"; ack versionNegotiation + sendRequest.
  5. GET https://<phone>:<port>/download?taskId=X -> ZIP -> extract to inbox.

Standalone: python -m tandem.mta --out <dir>
"""

import asyncio
import base64
import json
import os
import random
import re
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import zipfile
import io
from pathlib import Path

ADV_UUID = uuid.UUID("00003331-0000-1000-8000-008123456789")
SERVICE_UUID = uuid.UUID("00009955-0000-1000-8000-00805f9b34fb")
CHAR_STATUS = uuid.UUID("00009954-0000-1000-8000-00805f9b34fb")
CHAR_P2P = uuid.UUID("00009953-0000-1000-8000-00805f9b34fb")
SD_UUID_1 = uuid.UUID("00001b1e-0000-1000-8000-00805f9b34fb")
SD_UUID_2 = uuid.UUID("0000ffff-0000-1000-8000-00805f9b34fb")
# OPPO phones also emit a companion ADV_IND: uuid16 0x3334 + svcdata = name.
# Publishers can't carry UUID sections (ACCESS_DENIED), so a GATT provider
# emits it instead — ParcelUuid matching ignores 16/128-bit width.
NAME_SVC_UUID = uuid.UUID("00003334-0000-1000-8000-00805f9b34fb")

AES_IV = b"0102030405060708"


def _local_mac() -> str:
    """Our MAC for the status JSON — real devices send their (randomized)
    P2P/Wi-Fi MAC, e.g. "2e:d6:1b:7b:68:04" from a live Xiaomi.
    uuid.getnode() returns the first NIC's MAC as a 48-bit int."""
    node = uuid.getnode()
    if node & (1 << 40):  # multicast bit would be odd for a device MAC
        node = (node | 0x020000000000) & 0xFEFFFFFFFFFF
    return ":".join(f"{(node >> (8 * i)) & 0xFF:02x}" for i in range(5, -1, -1))


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


def _uuid_le_bytes(u: uuid.UUID) -> bytes:
    """128-bit UUID as it goes on the wire in an AD section (little-endian)."""
    return u.bytes_le


# ---------------------------------------------------------------- crypto ----


class MtaCrypto:
    """ECDH P-256 + AES-256-CTR for P2P credential exchange."""

    def __init__(self):
        from cryptography.hazmat.primitives.asymmetric import ec

        self._priv = ec.generate_private_key(ec.SECP256R1())

    def public_key_b64(self) -> str:
        from cryptography.hazmat.primitives import serialization

        der = self._priv.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        return base64.b64encode(der).decode()

    def cipher_for(self, peer_b64: str):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
        from cryptography.hazmat.primitives.serialization import load_der_public_key

        peer = load_der_public_key(base64.b64decode(peer_b64))
        secret = self._priv.exchange(ec.ECDH(), peer)
        cipher = Cipher(algorithms.AES(secret), modes.CTR(AES_IV))

        def decrypt(s: str) -> str:
            d = cipher.decryptor()
            return (d.update(base64.b64decode(s)) + d.finalize()).decode()

        return decrypt


# ------------------------------------------------------------- GATT server --


class MtaGattServer:
    """WinRT GATT service 0x9955 + publisher advertising 0x3331."""

    def __init__(self, device_name: str, on_p2p, profile: str = "xiaomi"):
        self.name = device_name
        self.on_p2p = on_p2p  # callback(p2p dict)
        self.profile = profile  # advert layout: "xiaomi" | "oppo"
        self.crypto = MtaCrypto()
        self._provider = None
        self._beacon = None
        self._namesvc = None
        self._name_disc = None
        self._pubs: list = []
        self._rotate_stop: threading.Event | None = None
        self._beacon_disc = False
        self._phone_active = False  # a phone reached GATT — adverts moot
        self.ok = False
        self.error: str | None = None

    def start(self) -> None:
        try:
            self._start()
            self.ok = True
        except Exception as e:
            self.error = f"{type(e).__name__}: {e}"

    def _start(self) -> None:
        from winrt.windows.devices.bluetooth.genericattributeprofile import (
            GattCharacteristicProperties,
            GattLocalCharacteristicParameters,
            GattProtectionLevel,
            GattServiceProvider,
        )
        from winrt.windows.storage.streams import DataReader, DataWriter

        res = _get(GattServiceProvider.create_async(SERVICE_UUID))
        provider = getattr(res, "service_provider", res)
        self._provider = provider
        svc = provider.service

        # STATUS: real devices expose it READ|WRITE (captured from a live
        # Xiaomi). Sender may write an ack — accept and log it.
        p = GattLocalCharacteristicParameters()
        p.characteristic_properties = (
            GattCharacteristicProperties.READ | GattCharacteristicProperties.WRITE
        )
        p.read_protection_level = GattProtectionLevel.PLAIN
        p.write_protection_level = GattProtectionLevel.PLAIN
        r = _get(svc.create_characteristic_async(CHAR_STATUS, p))
        status_char = getattr(r, "characteristic", r)

        def on_status_write(_c, args):
            try:
                deferral = args.get_deferral()
                req = _get(args.get_request_async())
                reader = DataReader.from_buffer(req.value)
                raw = reader.read_string(reader.unconsumed_buffer_length)
                print(f"[mta] status write: {raw[:200]!r}", flush=True)
                req.respond()
                deferral.complete()
            except Exception as e:
                print(f"[mta] status write error: {e!r}", flush=True)

        status_char.add_write_requested(on_status_write)

        def on_read(_c, args):
            self._phone_active = True
            try:
                deferral = args.get_deferral()
                req = _get(args.get_request_async())
                info = {
                    "state": 0,
                    "mac": _local_mac(),
                    "key": self.crypto.public_key_b64(),
                    # frequency=5300 tells the sender we can host/join 5GHz —
                    # seen in a real Xiaomi's status JSON
                    "frequency": 5300,
                }
                w = DataWriter()
                w.write_bytes(json.dumps(info, separators=(",", ":")).encode())
                req.respond_with_value(w.detach_buffer())
                deferral.complete()
                print("[mta] status probed by phone", flush=True)
            except Exception as e:
                print(f"[mta] read error: {e!r}", flush=True)
                try:
                    deferral.complete()
                except Exception:
                    pass

        status_char.add_read_requested(on_read)

        # P2P: write (phone sends Wi-Fi credentials)
        p2 = GattLocalCharacteristicParameters()
        p2.characteristic_properties = (
            GattCharacteristicProperties.WRITE | GattCharacteristicProperties.WRITE_WITHOUT_RESPONSE
        )
        p2.write_protection_level = GattProtectionLevel.PLAIN
        r2 = _get(svc.create_characteristic_async(CHAR_P2P, p2))
        p2p_char = getattr(r2, "characteristic", r2)

        def on_write(_c, args):
            self._phone_active = True
            deferral = req = None
            try:
                deferral = args.get_deferral()
                req = _get(args.get_request_async())
                val = getattr(req, "value", None)
                if val is None:
                    return
                reader = DataReader.from_buffer(val)
                raw = reader.read_string(reader.unconsumed_buffer_length)
                # skip any non-JSON preamble bytes
                start = raw.find("{")
                data = json.loads(raw[start:])
                print(f"[mta] p2p write: keys={list(data)}", flush=True)
                if data.get("key"):
                    dec = self.crypto.cipher_for(data["key"])
                    for k in ("ssid", "psk", "mac"):
                        if k in data:
                            data[k] = dec(data[k])
                self.on_p2p(data)
            except Exception as e:
                print(f"[mta] write error: {e!r}", flush=True)
            finally:
                try:
                    req.respond()
                except Exception:
                    pass
                try:
                    deferral.complete()
                except Exception:
                    pass

        p2p_char.add_write_requested(on_write)

        # Advert layout copied from a real Xiaomi MTA packet capture:
        #   ADV:      Flags | 0x07 incomplete-UUID128 0x3331
        #                    | 0x16 service-data 0x1b1e <rand16><4x00>
        #   SCAN_RSP: 0x16 service-data 0xffff <8x00><rand16><name16><0x01>
        # Windows can't put UUIDs in a publisher advert (ACCESS_DENIED) and
        # GATT-provider adverts can't carry service data — so we run three
        # adverts from the same radio address and let the phone's scanner
        # merge them into one ScanResult:
        #   beacon(0x3331, connectable) + publisher(0x1b1e) + publisher(0xffff)
        from winrt.windows.devices.bluetooth.advertisement import (
            BluetoothLEAdvertisement,
            BluetoothLEAdvertisementDataSection,
            BluetoothLEAdvertisementPublisher,
        )
        from winrt.windows.devices.bluetooth.genericattributeprofile import (
            GattServiceProviderAdvertisingParameters,
        )

        rand16 = os.urandom(2)
        name16 = self.name.encode("utf-8")[:16]

        def sd_section(uuid16: int, payload: bytes):
            w = DataWriter()
            w.write_uint16(uuid16)
            w.write_bytes(payload)
            s = BluetoothLEAdvertisementDataSection()
            s.data_type = 0x16
            s.data = w.detach_buffer()
            return s

        def pub_for(*sections):
            adv = BluetoothLEAdvertisement()
            for s in sections:
                adv.data_sections.append(s)
            p = BluetoothLEAdvertisementPublisher(adv)
            p.start()
            return p

        def gatt_advert(provider, timeout: float = 2.0):
            """Start a connectable advert; returns the winning discoverable
            flag once it is on the air, or None. MediaTek radios abort on
            connectable+discoverable while BARROT dongles only emit when
            discoverable is set — try plain first, then with discoverable."""
            for disc in (False, True):
                apx = GattServiceProviderAdvertisingParameters()
                apx.is_connectable = True
                apx.is_discoverable = disc
                try:
                    provider.start_advertising_with_parameters(apx)
                except Exception:
                    continue
                t0 = time.time()
                while time.time() - t0 < timeout:
                    if int(provider.advertisement_status) in (2, 4):
                        return disc
                    time.sleep(0.1)
                try:
                    provider.stop_advertising()
                except Exception:
                    pass
            return None

        # The connectable beacon gets the advert slot first — it is what the
        # phone connects to; everything else is decoration the scanner merges.
        res_a = _get(GattServiceProvider.create_async(ADV_UUID))
        self._beacon = getattr(res_a, "service_provider", res_a)
        beacon_disc = None
        for _try in range(3):  # killed instances leave slots occupied briefly
            beacon_disc = gatt_advert(self._beacon)
            if beacon_disc is not None:
                break
            time.sleep(2.0)
        beacon_on = beacon_disc is not None
        if beacon_on:
            self._beacon_disc = beacon_disc
            if self.profile == "oppo":
                # 0x3334 companion advert — OPPO's share list keys on this
                # name-bearing beacon; 0x9955 service needs no advert of its
                # own (it is enumerated once the phone connects via 0x3331).
                res_n = _get(GattServiceProvider.create_async(NAME_SVC_UUID))
                self._namesvc = getattr(res_n, "service_provider", res_n)
                self._name_disc = gatt_advert(self._namesvc)
                gatt_on = self._name_disc is not None
            else:
                gatt_on = gatt_advert(provider) is not None
        else:
            gatt_on = False
        second = self._namesvc if self._namesvc is not None else provider
        print(
            f"[mta] beacon status={self._beacon.advertisement_status} "
            f"gatt status={second.advertisement_status}",
            flush=True,
        )
        if not beacon_on:
            raise RuntimeError("connectable BLE advert never started")
        if not gatt_on:
            # No slot for the 0x9955 advert — harmless: the service stays
            # registered and shows up when the phone enumerates over the
            # beacon connection.
            try:
                provider.stop_advertising()
            except Exception:
                pass

        # Service-data layout differs per vendor. Xiaomi (captured):
        #   ADV 0x1b1e <rand16><4x00> ; SCAN_RSP 0xffff <8x00><rand16><name><0x01>
        # OPPO/OnePlus (captured from a OnePlus, name=bgm145632):
        #   ADV 0x012a <6 ascii id> ; SCAN_RSP 0x0204 <10B prefix><name16 pad0><0x01>
        #   companion: uuid16 0x3334 + svcdata 0x3334 = <name ascii>
        pubs: list = []
        if self.profile == "oppo":
            ascii_id = "".join(random.choice("0123456789ABCDEF") for _ in range(6))
            # senderId rides at bytes 8..9 of the 27-byte blob — "1x" mimics
            # the OnePlus capture ("EEFB52FY1x" + name)
            prefix = "".join(random.choice("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                             for _ in range(8)).encode() + b"1x"
            name_a = name16[:16]
            blob204 = prefix + name_a.ljust(16, b"\x00") + b"\x01"
            pubs = [
                [sd_section(0x012A, ascii_id.encode())],
                [sd_section(0x0204, blob204)],
                # plain svcdata keyed 0x3334 is legal — the uuid16-LIST
                # section is what publishers reject
                [sd_section(0x3334, name_a)],
            ]
        else:
            pubs = [
                [sd_section(0x1B1E, rand16 + b"\x00" * 4)],
                [sd_section(0xFFFF, b"\x00" * 8 + rand16 + name16 + b"\x01")],
            ]

        self._pubs = []
        for secs in pubs:
            try:
                self._pubs.append(pub_for(*secs))
            except Exception as e:
                print(f"[mta] advert payload rejected: {e!r}", flush=True)
        time.sleep(1.0)
        pubs_on = self._pubs and all(int(p.status) == 2 for p in self._pubs)
        print(f"[mta] pubs {[int(p.status) for p in self._pubs]}", flush=True)
        if not pubs_on and self._pubs:
            # Some dongles (BARROT) grant only one advert slot. Time-share it:
            # every advert takes turns on the air — connectable beacons get
            # the longest dwell so the phone can still reach GATT.
            for p in self._pubs:
                try:
                    p.stop()
                except Exception:
                    pass
            self._rotate_stop = threading.Event()
            threading.Thread(target=self._rotate_adverts, daemon=True).start()
            print("[mta] single advert slot — rotating all adverts", flush=True)
        print(f"[mta] advertising as MTA receiver '{self.name}'", flush=True)

    def _rotate_adverts(self) -> None:
        from winrt.windows.devices.bluetooth.genericattributeprofile import (
            GattServiceProviderAdvertisingParameters,
        )

        def gatt_start(provider, disc):
            try:
                apx = GattServiceProviderAdvertisingParameters()
                apx.is_connectable = True
                apx.is_discoverable = disc
                provider.start_advertising_with_parameters(apx)
            except Exception:
                pass

        def gatt_stop(provider):
            try:
                provider.stop_advertising()
            except Exception:
                pass

        def on_air(provider, timeout=0.9) -> bool:
            t0 = time.time()
            while time.time() - t0 < timeout:
                if int(provider.advertisement_status) in (2, 4):
                    return True
                time.sleep(0.05)
            return False

        # cycle: (item, dwell) — beacon dominates for connectability
        cycle = [("beacon", 1.6)]
        if self._namesvc is not None:
            cycle.append(("namesvc", 0.7))
        for i in range(len(self._pubs)):
            cycle.append((f"pub{i}", 0.7))
        i = 0
        cur = None
        gatt_start(self._beacon, self._beacon_disc)
        cur = "beacon"
        while not self._rotate_stop.is_set() and not self._phone_active:
            name, dwell = cycle[i % len(cycle)]
            i += 1
            if name != cur:
                # switch the on-air advert
                if cur == "beacon":
                    gatt_stop(self._beacon)
                elif cur == "namesvc" and self._namesvc is not None:
                    gatt_stop(self._namesvc)
                else:
                    try:
                        self._pubs[int(cur[3:])].stop()
                    except Exception:
                        pass
                if name == "beacon":
                    gatt_start(self._beacon, self._beacon_disc)
                    on_air(self._beacon)
                elif name == "namesvc" and self._namesvc is not None:
                    disc = self._name_disc if self._name_disc is not None \
                        else self._beacon_disc
                    gatt_start(self._namesvc, disc)
                    on_air(self._namesvc)
                else:
                    p = self._pubs[int(name[3:])]
                    try:
                        p.start()
                        if not on_air(p, 0.6):
                            print(f"[mta] rotate: {name} status={p.status} "
                                  "(missed window)", flush=True)
                    except Exception:
                        pass
                cur = name
            time.sleep(dwell)
        # rotation stopped because a phone connected — make sure the
        # connectable beacon is the one left on the air
        if cur != "beacon":
            gatt_start(self._beacon, self._beacon_disc)

    def stop(self):
        if self._rotate_stop:
            self._rotate_stop.set()
        for a in ("_provider", "_beacon", "_namesvc"):
            try:
                getattr(self, a) and getattr(self, a).stop_advertising()
            except Exception:
                pass
        for p in self._pubs:
            try:
                p.stop()
            except Exception:
                pass


# ---------------------------------------------------------------- Wi-Fi -----


def _current_ssid() -> str | None:
    out = subprocess.run(
        ["netsh", "wlan", "show", "interfaces"], capture_output=True, text=True
    ).stdout
    m = re.search(r"^\s*SSID\s*:\s*(.+)$", out, re.M)
    return m.group(1).strip() if m else None


def join_wifi(ssid: str, psk: str, timeout: float = 25.0) -> None:
    """Join a WPA2 network via netsh profile. Caller restores the old SSID."""
    profile = f"""<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
 <name>{ssid}</name>
 <SSIDConfig><SSID><name>{ssid}</name></SSID></SSIDConfig>
 <connectionType>ESS</connectionType>
 <connectionMode>manual</connectionMode>
 <MSM><security>
  <authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption>
  <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>{psk}</keyMaterial></sharedKey>
 </security></MSM>
</WLANProfile>"""
    with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as f:
        f.write(profile)
        tmp = f.name
    try:
        subprocess.run(["netsh", "wlan", "add", "profile", f"filename={tmp}"],
                       capture_output=True, check=True)
        subprocess.run(["netsh", "wlan", "connect", f"name={ssid}"],
                       capture_output=True, check=True)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if _current_ssid() == ssid:
                return
            time.sleep(0.5)
        raise TimeoutError(f"join {ssid} timed out")
    finally:
        os.unlink(tmp)
        subprocess.run(["netsh", "wlan", "delete", "profile", f"name={ssid}"],
                       capture_output=True)


def rejoin(ssid: str | None) -> None:
    if not ssid:
        return
    subprocess.run(["netsh", "wlan", "connect", f"name={ssid}"], capture_output=True)


def _default_gateway() -> str | None:
    """Default gateway of the phone's AP we just joined — that IS the phone.
    Android P2P group owners aren't always 192.168.49.1 across vendors."""
    try:
        out = subprocess.run(["ipconfig"], capture_output=True, text=True,
                             timeout=10).stdout
    except OSError:
        return None
    gws = re.findall(
        r"(?:Default Gateway|默认网关)[.\s]*:\s*(\d+\.\d+\.\d+\.\d+)", out)
    for gw in gws:  # prefer the phone-grade subnet
        if gw.startswith("192.168."):
            return gw
    return gws[-1] if gws else None


# --------------------------------------------------------------- receiver --


MSG_RE = re.compile(r"^(\w+):(\d+):(\w+)(\?(.*))?$")


def _parse_msg(text: str):
    m = MSG_RE.match(text)
    if not m:
        return None
    payload = json.loads(m.group(5)) if m.group(5) else None
    return m.group(1), int(m.group(2)), m.group(3), payload


async def _receive_session(host: str, port: int, out_dir: Path, on_file, on_text):
    """WebSocket handshake + HTTPS ZIP download from the sender."""
    import websockets

    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    async with websockets.connect(
        f"wss://{host}:{port}/websocket", ssl=ctx, open_timeout=15
    ) as ws:
        task_id = ""
        accepted = asyncio.Event()
        req_payload: dict = {}

        async for raw in ws:
            if isinstance(raw, bytes):
                raw = raw.decode()
            msg = _parse_msg(raw)
            if not msg or msg[0] != "action":
                continue
            _, mid, name, payload = msg
            lname = name.lower()
            if lname == "versionnegotiation":
                v = min((payload or {}).get("version", 1), 1)
                await ws.send(f'ack:{mid}:{name}?{{"version":{v},"threadLimit":5}}')
            elif lname == "sendrequest":
                req_payload = payload or {}
                task_id = str(req_payload.get("taskId") or req_payload.get("id") or "")
                await ws.send(f"ack:{mid}:{name}")
                text = req_payload.get("Text")
                if text is not None:
                    if on_text:
                        on_text(text)
                    await ws.send(f'action:99:status?{{"taskId":"{task_id}","id":"{task_id}","type":1,"reason":"ok"}}')
                    return
                # auto-accept: Tandem's promise is low-friction
                accepted.set()
            elif lname == "status":
                if (payload or {}).get("type") == 3:
                    return  # sender cancelled
        if not accepted.is_set():
            return

        # download ZIP stream
        url = f"https://{host}:{port}/download?taskId={task_id}"
        blob = await asyncio.get_event_loop().run_in_executor(
            None, lambda: _https_get(url, ctx)
        )
        names = _extract_zip(blob, out_dir)
        for n in names:
            if on_file:
                on_file(out_dir / n)
        await ws.send(f'action:99:status?{{"taskId":"{task_id}","id":"{task_id}","type":1,"reason":"ok"}}')


def _https_get(url: str, ctx: ssl.SSLContext) -> bytes:
    import urllib.request

    with urllib.request.urlopen(url, context=ctx, timeout=60) as r:
        return r.read()


def _extract_zip(blob: bytes, out_dir: Path) -> list[str]:
    out_dir.mkdir(parents=True, exist_ok=True)
    names = []
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        for n in z.namelist():
            safe = Path(n).name
            if not safe:
                continue
            (out_dir / safe).write_bytes(z.read(n))
            names.append(safe)
    return names


class MtaReceiver:
    """Runs BLE advertise + GATT + transfer loop on a background thread."""

    def __init__(self, out_dir: Path, device_name: str, on_file=None, on_text=None,
                 profile: str = "xiaomi"):
        self.out_dir = Path(out_dir)
        self.device_name = device_name
        self.on_file = on_file
        self.on_text = on_text
        self.profile = profile
        self._server: MtaGattServer | None = None
        self._thread: threading.Thread | None = None
        self.ok = False
        self.error: str | None = None
        self.received: list[str] = []

    def start(self) -> None:
        if sys.platform != "win32":
            self.error = "MTA receiver is Windows-only for now"
            return
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self):
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        loop.run_until_complete(self._loop())
        loop.close()

    async def _loop(self):
        p2p_event = asyncio.Event()
        box: dict = {}
        loop = asyncio.get_event_loop()

        def got_p2p(d):
            box["p2p"] = d
            loop.call_soon_threadsafe(p2p_event.set)

        self._server = MtaGattServer(self.device_name, got_p2p, self.profile)
        self._server.start()
        if not self._server.ok:
            self.error = self._server.error
            print(f"[mta] gatt server failed: {self.error}", flush=True)
            return
        self.ok = True

        while True:
            p2p_event.clear()
            box.pop("p2p", None)
            try:
                await asyncio.wait_for(p2p_event.wait(), timeout=600)
            except asyncio.TimeoutError:
                continue
            info = box["p2p"]
            await self._handle_transfer(info)

    async def _handle_transfer(self, info: dict):
        ssid, psk, port = info["ssid"], info["psk"], info["port"]
        prev = _current_ssid()
        print(f"[mta] joining '{ssid}' (was '{prev}')", flush=True)
        try:
            join_wifi(ssid, psk)
        except Exception as e:
            print(f"[mta] wifi join failed: {e}", flush=True)
            rejoin(prev)
            return
        try:
            host = _default_gateway() or "192.168.49.1"
            print(f"[mta] reaching phone at {host}:{port}", flush=True)
            await _receive_session(
                host, port, self.out_dir, self.on_file, self.on_text
            )
        except Exception as e:
            print(f"[mta] transfer error: {e!r}", flush=True)
        finally:
            rejoin(prev)
            print(f"[mta] back on '{prev}'", flush=True)


def main():
    import argparse

    ap = argparse.ArgumentParser(description="Tandem MTA receiver")
    ap.add_argument("--out", default=str(Path.home() / "Downloads" / "Tandem"))
    ap.add_argument("--name", default=os.environ.get("COMPUTERNAME", "Tandem PC"))
    ap.add_argument("--profile", default="xiaomi", choices=["xiaomi", "oppo"],
                    help="advert layout — xiaomi (Mi/红米) or oppo (OPPO/一加/realme)")
    args = ap.parse_args()

    def on_file(p):
        print(f"[mta] received: {p.name}", flush=True)

    def on_text(t):
        print(f"[mta] text: {t[:80]}", flush=True)

    r = MtaReceiver(Path(args.out), args.name, on_file, on_text, args.profile)
    r.start()
    t0 = time.time()
    while not r.ok and r.error is None and time.time() - t0 < 40:
        time.sleep(0.5)
    if not r.ok:
        print(f"[mta] failed: {r.error}")
        sys.exit(1)
    print(f"[mta] listening — share to '{args.name}' from your phone's 互传 menu")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        r._server and r._server.stop()


if __name__ == "__main__":
    main()
