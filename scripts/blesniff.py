"""Sniff raw BLE advertisements on Windows — capture a real MTA advert.

Usage:
  python scripts/blesniff.py            # dump everything advertising 0x3331
  python scripts/blesniff.py --all      # dump every BLE advert (noisy)
"""

import sys
import threading
import time

from winrt.windows.devices.bluetooth.advertisement import (
    BluetoothLEAdvertisementWatcher,
    BluetoothLEScanningMode,
)

TARGET = "00003331-0000-1000-8000-008123456789"


def dump_section(sec):
    try:
        t = sec.data_type
        buf = sec.data
        from winrt.windows.storage.streams import DataReader

        n = buf.length
        r = DataReader.from_buffer(buf)
        raw = bytes(r.read_buffer(n)) if n else b""
        return f"type=0x{t:02x} len={n} data={raw.hex()}"
    except Exception as e:
        return f"err:{e}"


def main():
    show_all = "--all" in sys.argv
    w = BluetoothLEAdvertisementWatcher()
    w.scanning_mode = BluetoothLEScanningMode.ACTIVE  # request scan response
    seen = set()

    def on_recv(_w, args):
        adv = args.advertisement
        addr = f"{args.bluetooth_address:012X}"
        addr = ":".join(addr[i:i + 2] for i in range(0, 12, 2))
        is_mta = False
        lines = []
        try:
            for sec in adv.data_sections:
                lines.append("  " + dump_section(sec))
                if TARGET.replace("-", "") in (
                    s := dump_section(sec).lower()
                ) or "3331" in s:
                    is_mta = True
        except Exception:
            pass
        try:
            for su in adv.service_uuids:
                lines.append(f"  su={su}")
                if str(su) == TARGET:
                    is_mta = True
        except Exception:
            pass
        for md in getattr(adv, "manufacturer_data_sections", []):
            try:
                n = md.data.length
                from winrt.windows.storage.streams import DataReader
                raw = bytes(DataReader.from_buffer(md.data).read_buffer(n))
                lines.append(f"  mfg=0x{md.company_id:04x} {raw.hex()}")
            except Exception:
                pass
        if not is_mta and not show_all:
            return
        key = (addr, tuple(lines))
        if key in seen:
            return
        seen.add(key)
        rssi = getattr(args, "raw_signal_strength_in_dbm", "?")
        print(f"\n=== {addr} rssi={rssi} type={args.advertisement_type} "
              f"name={adv.local_name!r} {'<MTA>' if is_mta else ''}")
        for l in lines:
            print(l)

    w.add_received(on_recv)
    w.start()
    print("sniffing… (Ctrl+C to stop)")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        w.stop()


if __name__ == "__main__":
    main()
