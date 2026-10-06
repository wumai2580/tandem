# iOS / iPadOS setup

Tandem has no iOS app — the share-sheet integration is a Shortcut that POSTs to your hub.

## 1. Trust the local CA (once, required for Shortcuts)

iOS Shortcuts refuses untrusted HTTPS. Install the hub's CA:

1. In Safari open `https://<pc-ip>:9787/ca.crt` → **Allow** the profile download.
2. **Settings → Profile Downloaded → Install**.
3. **Settings → General → About → Certificate Trust Settings** → enable the *Tandem Local CA* toggle.

(Plain browsing works without this — the cert step is only needed so *Shortcuts* can call the API.)

## 2. Create the Shortcut

New Shortcut named **Tandem**, set **"Receive … input from Share Sheet"** (accept everything), then:

**For files & photos** — action *Get Contents of URL*:
- URL: `https://<pc-ip>:9787/api/files?token=<your-token>`
- Method: POST
- Request Body: Form → add field `file` (type: File) = *Shortcut Input*

**For links & text** — action *Get Contents of URL*:
- URL: `https://<pc-ip>:9787/api/text?token=<your-token>`
- Method: POST
- Request Body: Form → add field `text` (type: Text) = *Shortcut Input*

> Tip: make two Shortcuts (*Tandem File*, *Tandem Link*), or one Shortcut with an `If` on the input type. The token is in the pair URL printed by the hub.

Links shared this way open directly in your PC's default browser — that's the "handoff".

## 3. Receiving on iOS

Bookmark `https://<pc-ip>:9787/?token=<token>` to your Home Screen. The Inbox shows everything sent to the hub — tap **Save** to download a file, or **Copy** for text/clipboard.
