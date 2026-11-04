#!/usr/bin/env python3
"""Merge the Fluid & Foolish pack's animation files into the engine's compiled-in clips.json.

    python3 tools/build_fluid_foolish_clips.py Fluid___Foolish_1_0_2.zip

Also copies the pack's licence text and compat defaults next to it for reference.
"""
import json
import sys
import zipfile
from pathlib import Path

DEST = Path(__file__).resolve().parent.parent / "native-mods-src/fluid-foolish"


def main(zip_path: str) -> None:
    clips = {}
    with zipfile.ZipFile(zip_path) as z:
        names = sorted(n for n in z.namelist() if n.endswith(".animation.json"))
        for name in names:
            data = json.loads(z.read(name))
            for clip, value in data.get("animations", {}).items():
                clips[clip] = value
        out = DEST / "mixins/fluid-foolish/engine/data/clips.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({"format_version": "1.8.0", "animations": clips}, separators=(",", ":")))
        pack = DEST / "pack"
        pack.mkdir(parents=True, exist_ok=True)
        (pack / "README.txt").write_bytes(z.read("README.txt"))
        compat = json.loads(z.read("assets/punchy_moves/compat/develop.json"))
        (out.parent / "compat.json").write_text(json.dumps(
            {"empty_hand": compat["empty_hand"], "item_groups": compat["item_groups"],
             "defaults": compat["defaults"]["animations"]}, separators=(",", ":")))
        for extra in ("assets/punchy_moves/compat/develop.json", "assets/punchy_moves/compat.schema.json"):
            if extra in z.namelist():
                (pack / Path(extra).name).write_bytes(z.read(extra))
    print(f"{len(clips)} clips from {len(names)} files -> {out}")


if __name__ == "__main__":
    main(sys.argv[1])
