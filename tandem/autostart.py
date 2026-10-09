"""Windows autostart via HKCU Run registry key (no admin needed)."""

import sys

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
NAME = "Tandem"


def _launch_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}" --tray'
    # python.exe flashes a console window; pythonw.exe is silent
    pyw = sys.executable.replace("python.exe", "pythonw.exe")
    return f'"{pyw}" -m tandem --tray'


def set_autostart(enable: bool) -> str:
    if sys.platform != "win32":
        return "autostart is only implemented on Windows for now"
    import winreg

    try:
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE
        ) as key:
            if enable:
                winreg.SetValueEx(key, NAME, 0, winreg.REG_SZ, _launch_command())
                return f"autostart enabled: {_launch_command()}"
            winreg.DeleteValue(key, NAME)
            return "autostart disabled"
    except FileNotFoundError:
        return "autostart was not set"
    except OSError as e:
        return f"autostart failed: {e}"
