import argparse
import io
import socket
import sys

import qrcode
import uvicorn

from . import __version__
from .autostart import set_autostart
from .certs import ensure_certs
from .config import DEFAULT_PORT, lan_ip, load_config
from .hotspot import hotspot_ip, start as hotspot_start, status as hotspot_status, wifi_qr_text
from .server import create_app


def free_port(preferred: int) -> int:
    with socket.socket() as s:
        try:
            s.bind(("0.0.0.0", preferred))
            return preferred
        except OSError:
            s.bind(("0.0.0.0", 0))
            return s.getsockname()[1]


def show_qr(text: str) -> None:
    qr = qrcode.QRCode(border=1)
    qr.add_data(text)
    try:
        qr.print_ascii(invert=True)
    except Exception:
        buf = io.StringIO()
        qr.print_ascii(out=buf, invert=True)
        print(buf.getvalue().encode("ascii", "replace").decode())


def print_banner(cfg: dict, url: str, hotspot: dict | None = None) -> None:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    print(f"\n  tandem v{__version__} — {cfg['device_name']}")
    print(f"  Local:   https://localhost:{cfg['port']}/?token={cfg['token']}")
    print(f"  Pair:    {url}\n")
    if hotspot and hotspot.get("state") == "On":
        print("  Base-station mode ON — this PC is the hotspot.")
        print(f"  Network: {hotspot.get('ssid')}  (key: {hotspot.get('pass')})\n")
        print("  Scan to join this PC's Wi-Fi (your Wi-Fi stays connected):\n")
        show_qr(wifi_qr_text(hotspot["ssid"], hotspot.get("pass") or ""))
        print("\n  …then scan the pair code below (or auto-discover in the app):\n")
        show_qr(url)
    else:
        print("  Scan with your phone to pair:\n")
        show_qr(url)
    print("\n  Pair page (devices & hotspot):  " +
          f"https://localhost:{cfg['port']}/pair?token={cfg['token']}")
    print("  If Windows asks about network access, click Allow —")
    print("  otherwise phones on your Wi-Fi can't reach the hub.")
    print("  Ctrl+C to stop.\n")


def main() -> None:
    ap = argparse.ArgumentParser(prog="tandem", description="AirDrop for every device")
    ap.add_argument("--tray", action="store_true", help="run in system tray")
    ap.add_argument("--autostart", choices=["on", "off"], help="start with Windows")
    ap.add_argument("--hotspot", choices=["on", "off"], help="base-station mode: this PC becomes the Wi-Fi")
    ap.add_argument("--port", type=int, help="port to listen on")
    args = ap.parse_args()

    if args.autostart:
        print(set_autostart(args.autostart == "on"))
        return

    hs = None
    if args.hotspot:
        from .hotspot import stop as hotspot_stop

        st = hotspot_start() if args.hotspot == "on" else hotspot_stop()
        if st.get("error"):
            print(f"hotspot: {st['error']}")
        hs = st

    cfg = load_config()
    if args.port:
        cfg["port"] = args.port
    cfg["port"] = free_port(cfg.get("port", DEFAULT_PORT))
    url = f"https://{hotspot_ip() or lan_ip()}:{cfg['port']}/?token={cfg['token']}"
    local_url = f"https://localhost:{cfg['port']}/?token={cfg['token']}"

    cert, key = ensure_certs()
    app = create_app()
    server = uvicorn.Server(
        uvicorn.Config(
            app,
            host="0.0.0.0",
            port=cfg["port"],
            log_level="warning",
            ssl_certfile=str(cert),
            ssl_keyfile=str(key),
        )
    )

    if args.tray or getattr(sys, "frozen", False):
        from .tray import run_in_tray

        run_in_tray(server, url, local_url)
    else:
        print_banner(cfg, url, hs or (hotspot_status() if hotspot_ip() else None))
        server.run()


if __name__ == "__main__":
    main()
