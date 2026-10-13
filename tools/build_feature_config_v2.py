# -*- coding: utf-8 -*-
"""Build the OCS "version 2" camera feature config for PKJ110 (Find X8 Ultra).

Why this file exists
--------------------
Every ``com.oplus.feature.*`` project-support key is resolved through

    Lut3dDataManager.paletteMode(scene)
        -> CameraConfig.d(key, def, ...)
        -> CameraConfig.w(key, def) -> CameraConfig.b(key, def)
        -> CameraConfig.y(key, def)
        -> FeatureApi.getProjectSupportFeatureValue(key)
        -> CameraConfigure.getProjectSupportFeatureValue(key)
        -> CameraConfigure.sVendorTagMap.get(key)

``CameraConfigure.getProjectSupportFeatureValue`` returns **null for every key** unless
``ProtobufConfigureHelper.getConfigParseVersion()`` is 2, and that in turn is

    getParseVersion() = isFileNotEmpty("<cfgdir>/etc/camera/config/oplus_camera_feature_config") ? 2 : 1

with ``cfgdir`` = ``/apex/com.oplus.camera.aps`` when it exists, else ``/odm`` (see
``ApsUtils.getConfigJsonDirectory``).  X8U's ODM ships only
``camera_unit_feature_config.protobuf`` (version 1), so ``getParseVersion()`` is 1 and
``paletteMode("common")`` is permanently false -- which is why the palette UI never appears on
PKJ110 even though the whole ipuapi palette stack and this module's hooks are present.

Container format (``CameraConfigure.decodeConfigFileVersion2``), all ints big-endian
(``Util.byteArrayToInt``):

    int32 odmFileVersion
    int32 entryCount
    entryCount * { int32 tagId ; int32 length ; byte[length] payload }

    tagId == 1 -> payload is UTF-8 text "key:value|key:value" -> CameraConfigure.generateVendorTagMap
    tagId == 2 -> payload is a raw ProtobufFeatureConfig.FeatureTable -> CameraConfigure.parseProtobufFeature

We keep the device's own FeatureTable **byte-identical** as tag 2 (so every mode/menu feature table
is built exactly as before) and add only the palette key as tag 1.  Keys that are absent from the
vendor-tag map still resolve to null, i.e. every other ``com.oplus.feature.*`` lookup behaves
exactly as it did on the version-1 path.
"""
import argparse
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_SRC = os.path.join(ROOT, "module", "payload_pkj110", "camera_unit_feature_config.protobuf")
DEFAULT_OUT = os.path.join(ROOT, "module", "payload_pkj110", "oplus_camera_feature_config")

# Project-support keys the vendor-tag map declares.  Only the palette switch: it is the one
# the port needs, and leaving everything else absent keeps the version-1 behaviour for them.
VENDOR_TAGS = [
    ("com.oplus.feature.color.palette.support", "true"),
]

TAG_VENDOR_MAP = 1
TAG_FEATURE_TABLE = 2


def build(pb_bytes):
    vendor = "|".join("%s:%s" % (k, v) for k, v in VENDOR_TAGS).encode("utf-8")
    entries = [
        (TAG_FEATURE_TABLE, pb_bytes),
        (TAG_VENDOR_MAP, vendor),
    ]
    out = bytearray()
    out += struct.pack(">i", 2)             # odmFileVersion
    out += struct.pack(">i", len(entries))  # entryCount
    for tag, payload in entries:
        out += struct.pack(">i", tag)
        out += struct.pack(">i", len(payload))
        out += payload
    return bytes(out), vendor


def check(blob, pb_len):
    """Re-read what we wrote the same way CameraConfigure does; exit non-zero on any mismatch."""
    off = 0

    def take(n):
        nonlocal off
        if off + n > len(blob):
            raise SystemExit("CHECK FAIL: truncated at %d" % off)
        chunk = blob[off:off + n]
        off += n
        return chunk

    def take_int():
        val = struct.unpack(">i", take(4))[0]
        if val < 0:
            raise SystemExit("CHECK FAIL: negative int")
        return val

    version = take_int()
    count = take_int()
    seen = {}
    for _ in range(count):
        tag = take_int()
        length = take_int()
        seen[tag] = take(length)
    if off != len(blob):
        raise SystemExit("CHECK FAIL: %d trailing bytes" % (len(blob) - off))
    if version != 2:
        raise SystemExit("CHECK FAIL: odmFileVersion %d" % version)
    if seen.get(TAG_FEATURE_TABLE) is None or len(seen[TAG_FEATURE_TABLE]) != pb_len:
        raise SystemExit("CHECK FAIL: tag2 is not the source FeatureTable")
    vendor = seen.get(TAG_VENDOR_MAP, b"").decode("utf-8")
    pairs = [p.split(":", 1) for p in vendor.split("|") if p]
    if any(len(p) != 2 for p in pairs):
        raise SystemExit("CHECK FAIL: vendor tag map is not key:value")
    return {"version": version, "count": count,
            "vendor": {k: v for k, v in pairs},
            "table_bytes": len(seen[TAG_FEATURE_TABLE])}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=DEFAULT_SRC)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--check-only", action="store_true")
    args = ap.parse_args()

    pb = open(args.src, "rb").read()
    if pb[:15] == b"pb_file_version:":
        raise SystemExit("source already carries a pb_file_version header; strip it first")

    if args.check_only:
        info = check(open(args.out, "rb").read(), len(pb))
        print("OK %s: odmFileVersion=%d, %d entries, FeatureTable=%d bytes (source %d)"
              % (args.out, info["version"], info["count"], info["table_bytes"], len(pb)))
        print("   vendor tags: %s" % info["vendor"])
        return 0

    blob, vendor = build(pb)
    info = check(blob, len(pb))
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "wb") as fh:
        fh.write(blob)
    print("wrote %s: %d bytes (source FeatureTable %d bytes)"
          % (args.out, len(blob), info["table_bytes"]))
    print("   odmFileVersion=%d entryCount=%d" % (info["version"], info["count"]))
    print("   vendor tags: %s" % info["vendor"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
