"""mDNS advertisement so the companion app (and future peers) can find the hub."""

import socket

from zeroconf import ServiceInfo, Zeroconf

from . import __version__
from .config import lan_ip

SERVICE_TYPE = "_tandem._tcp.local."


class Announcer:
    def __init__(self) -> None:
        self._zc = Zeroconf()
        self._info: ServiceInfo | None = None

    def start(self, name: str, port: int) -> None:
        props = {"name": name, "ver": __version__}
        self._info = ServiceInfo(
            SERVICE_TYPE,
            f"{name}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(lan_ip())],
            port=port,
            properties=props,
            server=f"{socket.gethostname()}.local.",
        )
        self._zc.register_service(self._info)

    def stop(self) -> None:
        if self._info:
            self._zc.unregister_service(self._info)
        self._zc.close()
