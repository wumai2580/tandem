"""System-tray mode: keeps the hub running in the background on Windows/macOS/Linux."""

import os
import sys
import threading
import webbrowser

from .config import data_dir


def run_in_tray(server, pair_url: str, local_url: str) -> None:
    try:
        import pystray
        from PIL import Image
    except ImportError:
        print("tray mode needs: pip install pystray pillow")
        sys.exit(1)

    icon_path = os.path.join(os.path.dirname(__file__), "static", "icons", "icon-512.png")
    image = (
        Image.open(icon_path)
        if os.path.exists(icon_path)
        else Image.new("RGB", (64, 64), (79, 70, 229))
    )

    def quit_app(icon, _item):
        server.should_exit = True
        icon.stop()

    def open_ui(_icon, _item):
        webbrowser.open(local_url)

    def open_inbox(_icon, _item):
        path = str(data_dir())
        if sys.platform == "win32":
            os.startfile(path)
        elif sys.platform == "darwin":
            os.system(f'open "{path}"')
        else:
            os.system(f'xdg-open "{path}"')

    def copy_pair(_icon, _item):
        try:
            import pyperclip

            pyperclip.copy(pair_url)
        except Exception:
            pass

    def open_pair_page(_icon, _item):
        # /pair shows QRs, device list and the hotspot ("base station") toggle
        token = pair_url.split("token=", 1)[-1]
        webbrowser.open(f"{local_url.split('?')[0]}pair?token={token}")

    menu = pystray.Menu(
        pystray.MenuItem("Open Tandem", open_ui, default=True),
        pystray.MenuItem("Copy pair link", copy_pair),
        pystray.MenuItem("Pair devices / hotspot…", open_pair_page),
        pystray.MenuItem("Open inbox folder", open_inbox),
        pystray.MenuItem("Quit", quit_app),
    )
    icon = pystray.Icon("tandem", image, "Tandem", menu)

    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    icon.run()  # blocks; must be the main thread on some backends
    server.should_exit = True
    thread.join(timeout=5)
