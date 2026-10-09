"""Self-signed local CA + server certificate for HTTPS on the LAN.

A plain http://<lan-ip> origin is not a secure context, so service workers,
PWA install and clipboard APIs are unavailable. We generate a local CA once
and re-sign a server cert whenever the LAN IP changes. Users can optionally
install the CA once to unlock full PWA/share-target support.
"""

import datetime
import ipaddress
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from platformdirs import user_config_dir

from .config import APP_NAME, lan_ip

TLS_DIR = Path(user_config_dir(APP_NAME)) / "tls"


def _new_key() -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def _write(path: Path, data: bytes) -> None:
    path.write_bytes(data)


def _load_or_create_ca() -> tuple[rsa.RSAPrivateKey, x509.Certificate]:
    key_path = TLS_DIR / "ca.key"
    crt_path = TLS_DIR / "ca.crt"
    if key_path.exists() and crt_path.exists():
        key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
        crt = x509.load_pem_x509_certificate(crt_path.read_bytes())
        return key, crt

    key = _new_key()
    now = datetime.datetime.now(datetime.timezone.utc)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Tandem Local CA")])
    crt = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(minutes=1))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .add_extension(
            x509.SubjectKeyIdentifier.from_public_key(key.public_key()),
            critical=False,
        )
        .add_extension(
            x509.KeyUsage(
                digital_signature=False,
                content_commitment=False,
                key_encipherment=False,
                data_encipherment=False,
                key_agreement=False,
                key_cert_sign=True,
                crl_sign=True,
                encipher_only=False,
                decipher_only=False,
            ),
            critical=True,
        )
        .sign(key, hashes.SHA256())
    )
    _write(key_path, key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption(),
    ))
    _write(crt_path, crt.public_bytes(serialization.Encoding.PEM))
    return key, crt


def _server_sans() -> list[x509.GeneralName]:
    sans: list[x509.GeneralName] = [
        x509.DNSName("localhost"),
        x509.DNSName("tandem.local"),
        x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
        # Windows mobile hotspot always NATs the host as 192.168.137.1 —
        # include it so the cert stays valid in "base station" mode.
        x509.IPAddress(ipaddress.ip_address("192.168.137.1")),
    ]
    try:
        sans.append(x509.IPAddress(ipaddress.ip_address(lan_ip())))
    except ValueError:
        pass
    return sans


def _sans_of(crt: x509.Certificate) -> set[str]:
    try:
        san = crt.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    except x509.ExtensionNotFound:
        return set()
    out: set[str] = set()
    for n in san:
        if isinstance(n, x509.DNSName):
            out.add("dns:" + n.value)
        elif isinstance(n, x509.IPAddress):
            out.add("ip:" + str(n.value))
    return out


def ensure_certs() -> tuple[Path, Path]:
    """Return (cert_path, key_path), (re)generating the server cert if SANs changed."""
    TLS_DIR.mkdir(parents=True, exist_ok=True)
    ca_key, ca_crt = _load_or_create_ca()
    crt_path = TLS_DIR / "server.crt"
    key_path = TLS_DIR / "server.key"

    wanted = {"dns:localhost", "dns:tandem.local", "ip:127.0.0.1", "ip:192.168.137.1", f"ip:{lan_ip()}"}
    if crt_path.exists() and key_path.exists():
        existing = x509.load_pem_x509_certificate(crt_path.read_bytes())
        if wanted <= _sans_of(existing):
            return crt_path, key_path

    key = _new_key()
    now = datetime.datetime.now(datetime.timezone.utc)
    crt = (
        x509.CertificateBuilder()
        .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "tandem.local")]))
        .issuer_name(ca_crt.subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(minutes=1))
        .not_valid_after(now + datetime.timedelta(days=825))
        .add_extension(x509.SubjectAlternativeName(_server_sans()), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.SubjectKeyIdentifier.from_public_key(key.public_key()),
            critical=False,
        )
        .add_extension(
            x509.AuthorityKeyIdentifier(
                x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()).digest,
                None,
                None,
            ),
            critical=False,
        )
        .add_extension(
            x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]),
            critical=False,
        )
        .sign(ca_key, hashes.SHA256())
    )
    _write(key_path, key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption(),
    ))
    _write(crt_path, crt.public_bytes(serialization.Encoding.PEM))
    return crt_path, key_path


def ca_cert_path() -> Path:
    return TLS_DIR / "ca.crt"
