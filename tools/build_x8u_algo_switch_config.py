#!/usr/bin/env python3
"""Rebuild the PKJ110 `oplus_camera_algo_switch_config` from the device's own stock file.

Why this file exists (POP shutter, 2026-10-14)
----------------------------------------------
`BaseMode.m8()` is the last gate before a capture. On PKJ110 it returns false for POP, at the
tail `if (Pc(gVar)) return gVar.b == getCameraId();`: `bb.g.b` (OCS preview-result key
`PREVIEW_RESULT_CAMERA_ID`) is still the constructor default -1, i.e. the APS never produced a
preview result for the mode. The cause is one layer further up: the camera asks
`AlgoSwitchConfig.getPreviewConfig("retroCamera", 0, ...)`, which looks `<mode>@<cameraId>` up in
`sPreviewConfigMap` and returns null (logging "getPreviewConfig, preview config is null"),
because that map is parsed from `oplus_camera_algo_switch_config` and neither its
`aps_preview_configs` nor its `aps_capture_configs` has a `retroCamera` entry.

On PMA110 this never shows: the X9U build of that file does carry `retroCamera` and
`module/payload/` ships it verbatim. X8U's copy is an opaque container (header `01 01`, decoded
only by the native `libOplusStringJNI.so`), so `post-fs-data.sh` leaves the stock file in place
for the PKJ110 profile -- and that is precisely the missing entry.

Rather than import somebody else's copy of that config, the pristine text was recovered from the
device itself: `UpdateHelper.getValidConfigData(Context, String)` is the consumer-SDK method that
returns the decoded JSON, and a temporary probe logged its result. It is stored as
`port-x8u/_pristine/x8u-pristine-config/oplus_camera_algo_switch_config`, and it is the only base
this tool accepts.

What it does
------------
Appends a `retroCamera` block to the end of both sections. The block is taken from the X9U
template and pruned to the key vocabulary X8U's own stock file uses, so X9U-only algorithm
switches (`aps_algo_dcg_fusion`, `aps_algo_rsp`, `aps_algo_colorsensor`, ...) are dropped instead
of being guessed at. Nothing else is touched: the stock text is spliced, never re-serialised, so
its comments and formatting survive byte for byte. Pure append is also what the project's config
rules require -- see port-x8u/README.md section 4.1.

Usage:
    python tools/build_x8u_algo_switch_config.py            # write payload + mirror
    python tools/build_x8u_algo_switch_config.py --check    # verify, write nothing
"""

import collections
import hashlib
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

STOCK = os.path.join(
    REPO, "port-x8u", "_pristine", "x8u-pristine-config", "oplus_camera_algo_switch_config"
)
TEMPLATE = os.path.join(REPO, "module", "payload", "oplus_camera_algo_switch_config")
OUTPUTS = (
    os.path.join(REPO, "module", "payload_pkj110", "oplus_camera_algo_switch_config"),
    os.path.join(REPO, "port-x8u", "payload", "oplus_camera_algo_switch_config"),
)

SECTIONS = ("aps_capture_configs", "aps_preview_configs")
# The two sections use different colon spacing in the stock file; follow each one so the appended
# block cannot be spotted from the surrounding text.
SEPARATORS = {"aps_capture_configs": " : ", "aps_preview_configs": ": "}
PREVIEW_ANCHOR = '\n    "aps_preview_configs"'
ADDED_MODE = "retroCamera"
INDENT = 4
# The array close of each section, exactly as it appears in the stock text.
CLOSE = "\n    ]"


# ---------------------------------------------------------------------------
# JSONC helpers
# ---------------------------------------------------------------------------
def strip_jsonc(text):
    """Remove // and /* */ comments outside strings, then trailing commas."""
    out = []
    at = 0
    size = len(text)
    while at < size:
        char = text[at]
        if char == '"':
            out.append(char)
            at += 1
            while at < size:
                inner = text[at]
                out.append(inner)
                at += 1
                if inner == "\\":
                    if at < size:
                        out.append(text[at])
                        at += 1
                    continue
                if inner == '"':
                    break
            continue
        if text.startswith("//", at):
            while at < size and text[at] not in "\r\n":
                at += 1
            continue
        if text.startswith("/*", at):
            end = text.find("*/", at + 2)
            at = size if end < 0 else end + 2
            continue
        out.append(char)
        at += 1
    cleaned = "".join(out)
    return re.sub(r",(\s*[}\]])", r"\1", cleaned)


def loads(text):
    return json.loads(text, object_pairs_hook=collections.OrderedDict)


def load_stock():
    with open(STOCK, "r", encoding="utf-8", newline="") as handle:
        text = handle.read()
    return text, loads(strip_jsonc(text))


def load_template():
    with open(TEMPLATE, "r", encoding="utf-8") as handle:
        return json.load(handle, object_pairs_hook=collections.OrderedDict)


# ---------------------------------------------------------------------------
# vocabulary + pruning
# ---------------------------------------------------------------------------
def vocabulary(section):
    """Every key name and every algo name that occurs anywhere in this stock section."""
    keys = set()
    names = set()

    def visit(node):
        if isinstance(node, dict):
            for key, value in node.items():
                keys.add(key)
                if key == "algo_list" and isinstance(value, list):
                    names.update(str(item) for item in value)
                visit(value)
        elif isinstance(node, list):
            for item in node:
                visit(item)

    visit(section)
    return keys, names


def prune(node, keys, names):
    """Drop dict entries whose key this section never uses, and unknown algo_list names."""
    if isinstance(node, dict):
        kept = collections.OrderedDict()
        for key, value in node.items():
            if key not in keys:
                continue
            if key == "algo_list" and isinstance(value, list):
                kept[key] = [item for item in value if item in names]
            else:
                kept[key] = prune(value, keys, names)
        return kept
    if isinstance(node, list):
        return [prune(item, keys, names) for item in node]
    return node


def removed_keys(node, kept, path="", found=None):
    """List what pruning dropped, for the run log."""
    if found is None:
        found = []
    if isinstance(node, dict) and isinstance(kept, dict):
        for key, value in node.items():
            if key not in kept:
                found.append("%s/%s" % (path, key))
            elif key == "algo_list" and isinstance(value, list):
                found.extend(
                    "%s/%s/%s" % (path, key, item) for item in value if item not in kept[key]
                )
            else:
                removed_keys(value, kept.get(key), "%s/%s" % (path, key), found)
    elif isinstance(node, list) and isinstance(kept, list):
        for index, item in enumerate(node[: len(kept)]):
            removed_keys(item, kept[index], "%s[%d]" % (path, index), found)
    return found


# ---------------------------------------------------------------------------
# stock-styled writer
# ---------------------------------------------------------------------------
def dumps(node, separator, indent):
    """Stock layout: one key per line, lists of scalars inline, `{`/`[` on their own line."""
    pad = " " * indent
    if isinstance(node, dict):
        if not node:
            return "{}"
        lines = [
            '%s"%s"%s%s' % (pad + " " * INDENT, key, separator, dumps(value, separator, indent + INDENT))
            for key, value in node.items()
        ]
        return "{\n" + ",\n".join(lines) + "\n" + pad + "}"
    if isinstance(node, list):
        if not node:
            return "[]"
        if all(not isinstance(item, (dict, list)) for item in node):
            return "[ " + ", ".join(dumps(item, separator, indent) for item in node) + " ]"
        lines = [
            pad + " " * INDENT + dumps(item, separator, indent + INDENT) for item in node
        ]
        return "[\n" + ",\n".join(lines) + "\n" + pad + "]"
    return json.dumps(node, ensure_ascii=False)


def build_entry(section_name, template, keys, names):
    entry = None
    for candidate in template[section_name]:
        if candidate.get("mode") == ADDED_MODE:
            entry = candidate
    if entry is None:
        raise SystemExit("template %s has no %s entry" % (section_name, ADDED_MODE))
    kept = prune(entry, keys, names)
    return kept, removed_keys(entry, kept, ADDED_MODE)


def splice(text, section_name, entry):
    """Insert the entry just before the array close of the named section."""
    body = dumps(entry, SEPARATORS[section_name], 0)
    block = "\n".join(" " * 8 + line for line in body.split("\n"))
    limit = text.index(PREVIEW_ANCHOR) if section_name == "aps_capture_configs" else len(text)
    position = text.rindex(CLOSE, 0, limit)
    if position < 0:
        raise SystemExit("cannot find the %s array close" % section_name)
    return text[:position] + ",\n" + block + text[position:]


# ---------------------------------------------------------------------------
# verification
# ---------------------------------------------------------------------------
def verify(stock, rebuilt):
    problems = []
    for section in SECTIONS:
        before = [entry.get("mode") for entry in stock[section]]
        after = [entry.get("mode") for entry in rebuilt[section]]
        if after != before + [ADDED_MODE]:
            problems.append("%s mode list is %s" % (section, after))
        for index, entry in enumerate(stock[section]):
            if entry != rebuilt[section][index]:
                problems.append("%s[%d] %s changed" % (section, index, entry.get("mode")))
    for key, value in stock.items():
        if key in SECTIONS:
            continue
        if rebuilt.get(key) != value:
            problems.append("top-level %s changed" % key)
    return problems


def build_text():
    """Return (payload text, verification problems, per-section notes). Never writes."""
    stock_text, stock = load_stock()
    template = load_template()
    text = stock_text
    notes = []
    for section in SECTIONS:
        keys, names = vocabulary(stock[section])
        entry, removed = build_entry(section, template, keys, names)
        notes.append(
            (section, len(entry.get("entity", [{}])[0]), sorted(set(removed)))
        )
        text = splice(text, section, entry)
    return text, verify(stock, loads(strip_jsonc(text))), notes


def main(argv):
    text, problems, notes = build_text()
    for section, kept, removed in notes:
        print("  note  %s: %d keys kept, pruned %s" % (section, kept, removed or "nothing"))
    if problems:
        for problem in problems:
            print("  FAIL  %s" % problem)
        return 1

    payload = text.encode("utf-8")
    print("  ok    stock text is spliced, never re-serialised")
    print("  ok    only the two %s appends differ from the stock config" % ADDED_MODE)
    print("  ok    %d bytes, md5 %s" % (len(payload), hashlib.md5(payload).hexdigest()))

    if "--check" in argv:
        print("PASS: --check, nothing written")
        return 0

    for path in OUTPUTS:
        with open(path, "wb") as handle:
            handle.write(payload)
        print("  wrote %s" % os.path.relpath(path, REPO))
    print("PASS: payload rebuilt")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
