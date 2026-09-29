"""Podepsání APK schématem APK Signature Scheme v2 (bez Android SDK).

Normálně to dělá `apksigner` z Android SDK. Implementace podle specifikace:
https://source.android.com/docs/security/features/apksigning/v2

Struktura podepsaného APK:
    [záznamy ZIP] [APK Signing Block] [centrální adresář] [konec centrálního adresáře]
Podpis pokrývá všechny tři původní části, takže APK po podepsání nelze měnit.
"""

from __future__ import annotations

import hashlib
import struct
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

SIG_RSA_PKCS1_SHA256 = 0x0103
V2_BLOCK_ID = 0x7109871A
CHUNK = 1024 * 1024


def _lp(data: bytes) -> bytes:
    """Délkou prefixovaná data (uint32 little-endian + data)."""
    return struct.pack("<I", len(data)) + data


def _find_eocd(apk: bytes) -> int:
    """Najde začátek záznamu End of Central Directory (hledá od konce)."""
    for i in range(len(apk) - 22, max(-1, len(apk) - 22 - 65536), -1):
        if apk[i:i + 4] == b"PK\x05\x06":
            return i
    raise ValueError("Neplatný ZIP – chybí EOCD")


def _digest(sections: list[bytes]) -> bytes:
    """Digest v2: SHA-256 přes 1MB bloky všech sekcí."""
    chunk_digests = []
    for sec in sections:
        for off in range(0, len(sec), CHUNK):
            part = sec[off:off + CHUNK]
            chunk_digests.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(part)) + part).digest())
    return hashlib.sha256(b"\x5a" + struct.pack("<I", len(chunk_digests)) + b"".join(chunk_digests)).digest()


def load_or_create_key(key_path: Path, cert_path: Path):
    """Načte ladicí klíč a certifikát, případně je vytvoří (self-signed, 30 let)."""
    if key_path.exists() and cert_path.exists():
        key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        return key, cert
    import datetime

    from cryptography.x509.oid import NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "MotorCam debug"),
                      x509.NameAttribute(NameOID.COUNTRY_NAME, "CZ")])
    now = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now).not_valid_after(now + datetime.timedelta(days=365 * 30))
            .sign(key, hashes.SHA256()))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                           serialization.NoEncryption()))
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return key, cert


def sign_v2(unsigned: bytes, key, cert) -> bytes:
    """Vrátí podepsané APK."""
    eocd_off = _find_eocd(unsigned)
    cd_size, cd_off = struct.unpack("<II", unsigned[eocd_off + 12:eocd_off + 20])
    if cd_off + cd_size != eocd_off:
        raise ValueError("Za centrálním adresářem jsou neočekávaná data")
    entries, cd, eocd = unsigned[:cd_off], unsigned[cd_off:eocd_off], unsigned[eocd_off:]

    digest = _digest([entries, cd, eocd])
    cert_der = cert.public_bytes(serialization.Encoding.DER)
    signed_data = (
        _lp(_lp(struct.pack("<I", SIG_RSA_PKCS1_SHA256) + _lp(digest)))  # digests
        + _lp(_lp(cert_der))                                              # certificates
        + _lp(b"")                                                        # additional attributes
    )
    signature = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    pubkey = key.public_key().public_bytes(serialization.Encoding.DER,
                                           serialization.PublicFormat.SubjectPublicKeyInfo)
    signer = _lp(signed_data) + _lp(_lp(struct.pack("<I", SIG_RSA_PKCS1_SHA256) + _lp(signature))) + _lp(pubkey)
    v2_value = _lp(_lp(signer))

    pair = struct.pack("<I", V2_BLOCK_ID) + v2_value
    pairs = struct.pack("<Q", len(pair)) + pair
    block_size = len(pairs) + 8 + 16  # velikost bez úvodního pole velikosti
    block = struct.pack("<Q", block_size) + pairs + struct.pack("<Q", block_size) + b"APK Sig Block 42"

    # V EOCD posuneme offset centrálního adresáře za podpisový blok
    new_eocd = eocd[:16] + struct.pack("<I", cd_off + len(block)) + eocd[20:]
    return entries + block + cd + new_eocd
