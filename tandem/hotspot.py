"""Windows Mobile Hotspot ("base station" mode).

Uses the WinRT NetworkOperatorTetheringManager via PowerShell — modern
Windows runs hotspot on a Wi-Fi Direct virtual adapter, so the host's
existing Wi-Fi/Ethernet connection stays up (STA+AP concurrency). If the
adapter can't do that, TetheringCapability reports it and we bail out
instead of breaking the user's network.
"""

import json
import re
import shutil
import subprocess
import sys

PS_SNIPPET = r"""
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Networking.Connectivity.NetworkInformation, Windows.Networking.Connectivity, ContentType=WindowsRuntime]
$null = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager, Windows.Networking.NetworkOperators, ContentType=WindowsRuntime]

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() |
  Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
                 $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await-Op($op, $type) {
  $t = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
  $t.Wait()
  $t.Result
}

$profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
$out = [ordered]@{ supported = $false; onWlan = $false }
try {
  $out.onWlan = $profile.IsWlanConnectionProfile
  $mgr = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
  $out.supported = $true
  # instance .TetheringCapability returns $null on some systems — use the static lookup
  $cap = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::GetTetheringCapabilityFromConnectionProfile($profile)
  $out.capability = if ($null -eq $cap) { "" } else { [string]$cap }
  $out.state = [string]$mgr.TetheringOperationalState
  $out.clients = $mgr.ClientCount
  $cfg = $mgr.GetCurrentAccessPointConfiguration()
  $out.ssid = $cfg.Ssid
  $out.pass = $cfg.Passphrase
  if ("__ACTION__" -eq "start") {
    $r = Await-Op $mgr.StartTetheringAsync() ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
    $out.startStatus = [string]$r.Status
  } elseif ("__ACTION__" -eq "stop") {
    $r = Await-Op $mgr.StopTetheringAsync() ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
    $out.stopStatus = [string]$r.Status
  }
} catch {
  $out.error = [string]$_.Exception.Message
}
$out | ConvertTo-Json -Compress
"""


def _run(action: str) -> dict:
    if sys.platform != "win32":
        return {"supported": False, "error": "hotspot mode is Windows-only for now"}
    ps = shutil.which("powershell.exe") or shutil.which("powershell")
    if not ps:
        return {"supported": False, "error": "powershell not found"}
    script = PS_SNIPPET.replace("__ACTION__", action)
    try:
        out = subprocess.run(
            [ps, "-NoProfile", "-NonInteractive", "-Command", script],
            capture_output=True, text=True, timeout=30,
        )
    except (subprocess.TimeoutExpired, OSError) as e:
        return {"supported": False, "error": str(e)}
    try:
        return json.loads(out.stdout.strip())
    except json.JSONDecodeError:
        return {"supported": False, "error": (out.stderr or out.stdout or "no output").strip()[:400]}


def status() -> dict:
    return _run("status")


def start() -> dict:
    """Enable the hotspot. Refuses when the adapter can't run STA+AP."""
    st = _run("status")
    if not st.get("supported"):
        return st
    cap = st.get("capability", "")
    if cap not in ("Enabled",):
        st["error"] = f"hotspot unavailable: capability={cap}"
        return st
    if st.get("state") != "On":
        st = _run("start")
    return st


def stop() -> dict:
    return _run("stop")


def hotspot_ip() -> str | None:
    """ICS address once the hotspot is up (Windows always uses 192.168.137.1)."""
    try:
        out = subprocess.run(["ipconfig"], capture_output=True, text=True, timeout=10).stdout
    except OSError:
        return None
    m = re.search(r"IPv4[^:]*:\s*(192\.168\.137\.\d+)", out)
    return m.group(1) if m else None


def wifi_qr_text(ssid: str, password: str) -> str:
    esc = lambda s: s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace('"', '\\"').replace(":", "\\:")
    return f"WIFI:T:WPA;S:{esc(ssid)};P:{esc(password)};;"
