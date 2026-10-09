"""End-to-end 'simulated phone' test.

Launches Edge with Pixel-device emulation, opens the Tandem PWA, and walks
the whole real-device flow: pairing by URL, secure-context + service-worker
checks, share-target POST handoff, text/file/clipboard round-trips.

Usage:  python scripts/e2e_phone.py [--headed]
Requires: pip install playwright  and a running `tandem` hub.
"""

import argparse
import json
import sys
import urllib.request
from pathlib import Path

from platformdirs import user_config_dir
from playwright.sync_api import sync_playwright

BASE = "https://localhost:9787"


def load_token() -> str:
    cfg = json.loads(
        (Path(user_config_dir("tandem")) / "config.json").read_text()
    )
    return cfg["token"]


def api_post_file(token: str, name: str, body: bytes) -> dict:
    boundary = "e2eboundary"
    payload = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
    ).encode() + body + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        f"{BASE}/api/files?token={token}",
        data=payload,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    return json.loads(urllib.request.urlopen(req).read())


def api_post_clipboard(token: str, text: str) -> None:
    req = urllib.request.Request(
        f"{BASE}/api/clipboard?token={token}",
        data=json.dumps({"text": text}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    urllib.request.urlopen(req).read()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--headed", action="store_true")
    ap.add_argument("--shot", default="")
    args = ap.parse_args()

    token = load_token()
    checks = []

    def check(name: str, ok: bool, extra: str = "") -> None:
        checks.append((name, ok, extra))
        print(f"  {'PASS' if ok else 'FAIL'}  {name} {extra}")

    with sync_playwright() as p:
        browser = p.chromium.launch(channel="msedge", headless=not args.headed)
        ctx = browser.new_context(**p.devices["Pixel 7"])
        page = ctx.new_page()

        page.goto(f"{BASE}/?token={token}")
        page.wait_for_selector("#app:not(.hidden)", timeout=8000)

        check("paired via token URL, app visible", True)
        check(
            "secure context (PWA-installable)",
            page.evaluate("window.isSecureContext"),
        )
        check(
            "websocket connected",
            page.wait_for_function(
                "document.querySelector('#status-text').textContent === 'connected'",
                timeout=5000,
            )
            is not None,
        )

        sw_scope = page.evaluate(
            "navigator.serviceWorker.ready.then(r => r.scope)"
        )
        check("service worker registered", BASE in sw_scope, sw_scope)

        manifest = page.evaluate(
            "fetch('./manifest.webmanifest').then(r => r.json())"
        )
        check("manifest valid", manifest.get("name") == "Tandem")
        check(
            "share_target declared",
            manifest.get("share_target", {}).get("action", "").endswith("share-incoming"),
        )

        # --- share sheet simulation: POST like Android's share target does ---
        page.evaluate(
            """async () => {
                const fd = new FormData();
                fd.append('title', 'Shared photo');
                fd.append('text', '');
                fd.append('url', '');
                fd.append('files', new File(['fake-jpeg-bytes'], 'shared-photo.jpg',
                    {type: 'image/jpeg'}));
                await fetch('./share-incoming', {method: 'POST', body: fd});
            }"""
        )
        page.goto(f"{BASE}/?shared=1")
        page.wait_for_selector("text=shared-photo.jpg", timeout=8000)
        check("share-sheet file lands in inbox (SW handoff)", True)

        # --- phone -> pc: text ---
        page.fill("#text-input", "hello-from-phone")
        page.click("#send-text")
        page.wait_for_selector("text=hello-from-phone", timeout=5000)
        check("text send phone->hub", True)

        # --- pc -> phone: file ---
        item = api_post_file(token, "from-pc.txt", b"pc says hi")
        page.wait_for_selector("text=from-pc.txt", timeout=5000)
        check("file broadcast hub->phone over ws", True, item["id"])

        # --- clipboard push from phone side ---
        api_post_clipboard(token, "clipboard-from-phone")
        page.wait_for_function(
            "document.querySelector('#hub-clip').textContent === 'clipboard-from-phone'",
            timeout=5000,
        )
        check("clipboard event arrives over ws", True)

        # --- download back what the hub holds ---
        dl = page.evaluate(
            f"fetch('/api/files/{item['id']}?token={token}').then(r => r.text())"
        )
        check("phone downloads pc file", dl == "pc says hi")

        if args.shot:
            page.screenshot(path=args.shot, full_page=True)
        browser.close()

    failed = [c for c in checks if not c[1]]
    print(f"\n{len(checks) - len(failed)}/{len(checks)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
