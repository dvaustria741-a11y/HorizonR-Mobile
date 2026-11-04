#!/usr/bin/env python3
"""Zip native-mods-src/<name>/ into native-mods/<name>-<version>.hnmod.

    python3 tools/build_native_mod.py fluid-foolish
"""
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP = {"target", "Cargo.lock", ".git"}


def main(name: str) -> None:
    src = ROOT / "native-mods-src" / name
    meta = json.loads((src / "horizon-native.json").read_text())
    out = ROOT / "native-mods" / f"{name}-{meta['version'].replace('.', '_')}.hnmod"
    out.parent.mkdir(exist_ok=True)
    out.unlink(missing_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(src.rglob("*")):
            rel = path.relative_to(src)
            if path.is_dir() or rel.parts[0] == "pack" or SKIP & set(rel.parts):
                continue
            z.write(path, rel.as_posix())
    print(f"{out.relative_to(ROOT)}: {out.stat().st_size} bytes")


if __name__ == "__main__":
    main(sys.argv[1])
