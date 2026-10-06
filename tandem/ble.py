"""BLE beacon + GATT service — pairing/discovery without any shared network.

The hub advertises a Tandem service UUID over BLE. A phone app scans for it
(works even with Wi-Fi off / on a foreign network), then:

  INFO (read):  {"n": name, "p": port, "ips": [reachable candidates]}
  PAIR (write): {"k": one-time key, "n": device name}  → shows up as a
                pending pair request the user approves on the PC (/pair page).
                No secrets ride the air — the write only *requests* pairing.

Windows-only implementation via WinRT; gracefully no-ops elsewhere.
"""

import json
import sys
import threading
import uuid

SERVICE_UUID = uuid.UUID("f47ac10b-58cc-4372-a567-0e02b2c3d479")
INFO_UUID = uuid.UUID("f47ac10b-58cc-4372-a567-0e02b2c3d480")
PAIR_UUID = uuid.UUID("f47ac10b-58cc-4372-a567-0e02b2c3d481")


def _get(op, timeout: float = 10.0):
    """Block on a WinRT IAsyncOperation."""
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


class BleBeacon:
    """Advertises the hub and handles pair-request writes. Threaded."""

    def __init__(self, info_cb, on_pair_request):
        # info_cb() -> dict served on INFO reads; on_pair_request(name, key)
        self._info_cb = info_cb
        self._on_pair = on_pair_request
        self._provider = None
        self.ok = False
        self.error: str | None = None

    def start(self) -> None:
        if sys.platform != "win32":
            self.error = "BLE beacon is Windows-only for now"
            return
        try:
            self._start()
            self.ok = True
        except Exception as e:  # Bluetooth off, missing radio, WinRT quirks…
            self.error = f"{type(e).__name__}: {e}"

    def _start(self) -> None:
        from winrt.windows.devices.bluetooth.genericattributeprofile import (
            GattCharacteristicProperties,
            GattLocalCharacteristicParameters,
            GattProtectionLevel,
            GattServiceProvider,
        )
        from winrt.windows.storage.streams import DataReader, DataWriter

        self._rw = (DataReader, DataWriter)

        res = _get(GattServiceProvider.create_async(SERVICE_UUID))
        provider = getattr(res, "service_provider", res)
        self._provider = provider
        service = provider.service

        # INFO: read-only, dynamic (endpoints can change with hotspot state)
        p = GattLocalCharacteristicParameters()
        p.characteristic_properties = GattCharacteristicProperties.READ
        p.read_protection_level = GattProtectionLevel.PLAIN
        r = _get(service.create_characteristic_async(INFO_UUID, p))
        info_char = getattr(r, "characteristic", r)

        def on_read(_char, args):
            # args must be consumed on the event thread — take a deferral and
            # fetch the request synchronously, then respond.
            try:
                deferral = args.get_deferral()
                req = _get(args.get_request_async())
                payload = json.dumps(self._info_cb(), separators=(",", ":")).encode()
                w = DataWriter()
                w.write_bytes(payload)
                req.respond_with_value(w.detach_buffer())
                deferral.complete()
                print("[ble] INFO served", flush=True)
            except Exception as e:
                print(f"[ble] read handler error: {e!r}", flush=True)
                try:
                    deferral.complete()
                except Exception:
                    pass

        info_char.add_read_requested(on_read)

        # PAIR: write — phone drops {"k": key, "n": name}; nothing returned.
        p2 = GattLocalCharacteristicParameters()
        p2.characteristic_properties = (
            GattCharacteristicProperties.WRITE | GattCharacteristicProperties.WRITE_WITHOUT_RESPONSE
        )
        p2.write_protection_level = GattProtectionLevel.PLAIN
        p2.write_protection_level = GattProtectionLevel.PLAIN
        r2 = _get(service.create_characteristic_async(PAIR_UUID, p2))
        pair_char = getattr(r2, "characteristic", r2)

        def on_write(_char, args):
            try:
                deferral = args.get_deferral()
                req = _get(args.get_request_async())
                val = getattr(req, "value", None)
                if val is None:
                    req.respond()
                    deferral.complete()
                    return
                reader = DataReader.from_buffer(val)
                data = json.loads(reader.read_string(reader.unconsumed_buffer_length))
                print(f"[ble] pair request: {data.get('n')} key={data.get('k')}", flush=True)
                self._on_pair(str(data.get("n", "device"))[:64], str(data.get("k", ""))[:64])
                try:
                    req.respond()
                except OSError:
                    pass  # already committed by the stack
                try:
                    deferral.complete()
                except OSError:
                    pass
            except Exception as e:
                print(f"[ble] write handler error: {e!r}", flush=True)
                try:
                    req.respond()
                    deferral.complete()
                except Exception:
                    pass

        pair_char.add_write_requested(on_write)

        from winrt.windows.devices.bluetooth.genericattributeprofile import (
            GattServiceProviderAdvertisingParameters,
        )

        ap = GattServiceProviderAdvertisingParameters()
        ap.is_discoverable = True
        ap.is_connectable = True  # required for phones to reach GATT
        provider.start_advertising_with_parameters(ap)
        print("[ble] beacon advertising", flush=True)

    def stop(self) -> None:
        try:
            if self._provider:
                self._provider.stop_advertising()
        except Exception:
            pass
        self.ok = False
