"""Převod textového AndroidManifest.xml do binárního formátu Androidu (AXML).

Normálně to dělá nástroj aapt2 z Android SDK. Ten tady nepoužíváme (build funguje
jen s JDK a Pythonem), a protože aplikace nemá žádné zdroje (layouty jsou v kódu),
stačí nám převést jen manifest.

Formát: ResXMLTree = hlavička + string pool + mapa ID atributů + uzly XML.
Atributy s ID (android:*) musí být v elementu seřazené podle ID – framework je
prochází sloučením dvou seřazených seznamů.
"""

from __future__ import annotations

import struct
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# Typy hodnot (Res_value.dataType)
T_REFERENCE, T_STRING, T_INT_DEC, T_INT_HEX, T_BOOLEAN = 0x01, 0x03, 0x10, 0x11, 0x12

# android:atribut -> (ID zdroje, typ). ID jsou z android.R.attr (API 34).
ATTRS = {
    "theme": (0x01010000, T_REFERENCE),
    "label": (0x01010001, T_STRING),
    "icon": (0x01010002, T_REFERENCE),
    "name": (0x01010003, T_STRING),
    "exported": (0x01010010, T_BOOLEAN),
    "screenOrientation": (0x0101001E, T_INT_DEC),
    "configChanges": (0x0101001F, T_INT_HEX),
    "minSdkVersion": (0x0101020C, T_INT_DEC),
    "versionCode": (0x0101021B, T_INT_DEC),
    "versionName": (0x0101021C, T_STRING),
    "targetSdkVersion": (0x01010270, T_INT_DEC),
    "allowBackup": (0x01010280, T_BOOLEAN),
    "required": (0x0101028E, T_BOOLEAN),
    "hardwareAccelerated": (0x010102D3, T_BOOLEAN),
    "extractNativeLibs": (0x010104EA, T_BOOLEAN),
}

# Symbolické hodnoty pro výčtové atributy
ENUMS = {
    "screenOrientation": {"unspecified": -1, "landscape": 0, "portrait": 1, "sensorLandscape": 6,
                          "fullSensor": 10},
    "configChanges": {"keyboard": 0x10, "keyboardHidden": 0x20, "orientation": 0x80,
                      "screenLayout": 0x100, "uiMode": 0x200, "screenSize": 0x400,
                      "smallestScreenSize": 0x800},
}

# Odkazy na systémové zdroje, které v manifestu používáme (android.R.*)
FRAMEWORK_REFS = {
    "@android:mipmap/sym_def_app_icon": 0x010D0000,
    "@android:drawable/ic_menu_camera": 0x01080037,
    "@android:style/Theme.DeviceDefault.NoActionBar.Fullscreen": 0x0103012A,
}


def _encode_value(attr: str, text: str) -> tuple[int, int]:
    """Vrátí (typ, data) pro hodnotu atributu."""
    _, typ = ATTRS[attr]
    if typ == T_STRING:
        return T_STRING, 0  # data = index v string poolu, doplní volající
    if typ == T_BOOLEAN:
        return T_BOOLEAN, 0xFFFFFFFF if text == "true" else 0
    if typ == T_REFERENCE:
        return T_REFERENCE, FRAMEWORK_REFS[text]
    if typ in (T_INT_DEC, T_INT_HEX):
        if attr in ENUMS:
            value = 0
            for part in text.split("|"):
                value |= ENUMS[attr][part] if part in ENUMS[attr] else int(part, 0)
            return typ, value & 0xFFFFFFFF
        return typ, int(text, 0) & 0xFFFFFFFF
    raise ValueError(attr)


class _StringPool:
    """String pool v UTF-16. Názvy atributů s ID musí být na začátku (kvůli mapě ID)."""

    def __init__(self, first: list[str]):
        self.strings: list[str] = []
        self.index: dict[str, int] = {}
        for s in first:
            self.add(s)

    def add(self, s: str) -> int:
        if s not in self.index:
            self.index[s] = len(self.strings)
            self.strings.append(s)
        return self.index[s]

    def encode(self) -> bytes:
        data, offsets = b"", []
        for s in self.strings:
            offsets.append(len(data))
            u = s.encode("utf-16-le")
            data += struct.pack("<H", len(s)) + u + b"\0\0"
        data += b"\0" * (-len(data) % 4)
        header_size = 28
        strings_start = header_size + 4 * len(offsets)
        body = b"".join(struct.pack("<I", o) for o in offsets) + data
        return struct.pack("<HHIIIIII", 0x0001, header_size, header_size + len(body),
                           len(self.strings), 0, 0, strings_start, 0) + body


def compile_manifest(xml_text: str) -> bytes:
    """Hlavní funkce: textový manifest -> binární AXML."""
    root = ET.fromstring(xml_text)

    # 1) Posbírat použité android:* atributy a seřadit podle ID
    used = set()
    for el in root.iter():
        for key in el.attrib:
            if key.startswith("{" + ANDROID_NS + "}"):
                used.add(key.split("}")[1])
    res_attrs = sorted(used, key=lambda a: ATTRS[a][0])
    pool = _StringPool(res_attrs)
    pool.add("android")
    pool.add(ANDROID_NS)

    nodes = []

    def attr_sort_key(item):
        key = item[0]
        if key.startswith("{" + ANDROID_NS + "}"):
            return (0, ATTRS[key.split("}")[1]][0])
        return (1, 0)

    def walk(el, line):
        attrs = b""
        items = sorted(el.attrib.items(), key=attr_sort_key)
        for key, text in items:
            if key.startswith("{" + ANDROID_NS + "}"):
                name = key.split("}")[1]
                ns = pool.index[ANDROID_NS]
                name_idx = pool.index[name]
                typ, data = _encode_value(name, text)
                raw = pool.add(text) if typ == T_STRING else 0xFFFFFFFF
                if typ == T_STRING:
                    data = raw
            else:  # atribut bez namespace (package)
                ns, name_idx = 0xFFFFFFFF, pool.add(key)
                raw = pool.add(text)
                typ, data = T_STRING, raw
            attrs += struct.pack("<IIIHBBI", ns, name_idx, raw, 8, 0, typ, data)
        tag = pool.add(el.tag)
        start = struct.pack("<IIHHHHHH", 0xFFFFFFFF, tag, 20, 20, len(items), 0, 0, 0) + attrs
        nodes.append(struct.pack("<HHIII", 0x0102, 16, 16 + len(start), line, 0xFFFFFFFF) + start)
        for child in el:
            line = walk(child, line + 1)
        nodes.append(struct.pack("<HHIIIII", 0x0103, 16, 24, line, 0xFFFFFFFF, 0xFFFFFFFF, tag))
        return line + 1

    ns_body = struct.pack("<II", pool.index["android"], pool.index[ANDROID_NS])
    ns_start = struct.pack("<HHIII", 0x0100, 16, 24, 1, 0xFFFFFFFF) + ns_body
    walk(root, 2)
    ns_end = struct.pack("<HHIII", 0x0101, 16, 24, 1, 0xFFFFFFFF) + ns_body

    res_map_body = b"".join(struct.pack("<I", ATTRS[a][0]) for a in res_attrs)
    res_map = struct.pack("<HHI", 0x0180, 8, 8 + len(res_map_body)) + res_map_body

    body = pool.encode() + res_map + ns_start + b"".join(nodes) + ns_end
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body
