#!/usr/bin/env python3
"""Check that the built APK holds every file of assets/jre25/manifest.txt, the probe jar and the Rust library.

    verify_apk.py <apk> <manifest.txt>

AAPT drops some names (for example files starting with a dot); a missing one would only show up on the phone.
"""
import sys
import zipfile

ASSET_ROOT = "assets/jre25/"
EXTRA = ("assets/probe.jar", "lib/arm64-v8a/libjvm_boot_spike.so", "assets/jre25/manifest.txt")


def parse_manifest(text):
    out = {}
    for line in text.splitlines():
        if line.strip():
            size, path = line.split("\t", 1)
            out[path] = int(size)
    return out


def check(zip_names, sizes, manifest):
    """Returns (missing entries, entries whose uncompressed size differs from the manifest)."""
    missing = [p for p in manifest if ASSET_ROOT + p not in zip_names]
    missing += [p for p in EXTRA if p not in zip_names]
    wrong = [p for p, size in manifest.items() if ASSET_ROOT + p in sizes and sizes[ASSET_ROOT + p] != size]
    return missing, wrong


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    apk, manifest_path = argv[1:]
    with open(manifest_path, encoding="utf-8") as handle:
        manifest = parse_manifest(handle.read())
    with zipfile.ZipFile(apk) as archive:
        infos = archive.infolist()
        names = {i.filename for i in infos}
        sizes = {i.filename: i.file_size for i in infos}
        stored = sum(i.compress_size for i in infos)
    missing, wrong = check(names, sizes, manifest)
    if missing or wrong:
        print(f"::error title=APK content::missing {len(missing)}: {missing[:10]}; wrong size {len(wrong)}: {wrong[:10]}")
        return 1
    print(f"::notice title=APK content::all {len(manifest)} runtime files present; entries compressed to {stored} bytes in total")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
