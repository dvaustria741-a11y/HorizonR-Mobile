#!/usr/bin/env python3
"""Download the exact mod files of one config from mods.json, found on Modrinth by hash.

    fetch_mods.py CONFIG OUT_DIR

A file is accepted only if its SHA-512 equals the one in mods.json. Nothing is guessed: a mod that
Modrinth does not know by hash is an error. Mod jars are written to OUT_DIR and never committed.
"""
import hashlib
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
VERSION_FILE = "https://api.modrinth.com/v2/version_file/{sha1}?algorithm=sha1"
CDN_HOST = "cdn.modrinth.com"
USER_AGENT = "HorizonR-Mobile jvm-server-smoke (github.com/dvaustria741-a11y/HorizonR-Mobile)"


def select(manifest, config):
    """The mod entries of `config`, in order. Unknown config or mod name is an error."""
    configs = manifest["configs"]
    if config not in configs:
        raise KeyError(f"unknown config {config!r}; known: {', '.join(sorted(configs))}")
    out = []
    for name in configs[config]:
        if name not in manifest["mods"]:
            raise KeyError(f"config {config!r} names {name!r}, which is not in mods")
        out.append((name, manifest["mods"][name]))
    return out


def pick_file(version, sha512):
    """The file object of a Modrinth version JSON whose SHA-512 matches, else None."""
    for entry in version.get("files", []):
        if entry.get("hashes", {}).get("sha512") == sha512:
            return entry
    return None


def cdn_url_ok(url):
    parts = urllib.parse.urlsplit(url)
    return parts.scheme == "https" and parts.hostname == CDN_HOST


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    config, out_dir = argv[1], Path(argv[2])
    manifest = json.loads((HERE / "mods.json").read_text())
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, mod in select(manifest, config):
        version = json.loads(fetch(VERSION_FILE.format(sha1=mod["sha1"])))
        entry = pick_file(version, mod["sha512"])
        if entry is None:
            print(f"{name}: Modrinth has no file with the pinned SHA-512", file=sys.stderr)
            return 1
        if not cdn_url_ok(entry["url"]):
            print(f"{name}: refusing download URL {entry['url']}", file=sys.stderr)
            return 1
        data = fetch(entry["url"])
        if hashlib.sha512(data).hexdigest() != mod["sha512"]:
            print(f"{name}: downloaded bytes do not match the pinned SHA-512", file=sys.stderr)
            return 1
        (out_dir / mod["file"]).write_bytes(data)
        print(f"{name}: {mod['file']} ({len(data)} bytes) verified")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
