#!/usr/bin/env python3
"""Mechanical source patches that let 26.3's oracles compile against 1.21.11 (the remapped jar).

Each rule maps a 26.3 spelling onto the 1.21.11 one; a patched copy of an oracle is written under `--out` and the 26.3
source is left alone. An oracle with a hand-written copy in mods/horizon/src/test/java-1.21.11 is not patched. Only
what compiles is claimed: a patched oracle still has to be run and compared before it counts.

  port_oracles_1_21_11.py --out DIR      writes DIR/dev/horizonlab/parity/<Oracle>.java; prints the count
"""
import argparse, pathlib, re

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEST = ROOT / "mods/horizon/src/test"
PARITY = "dev/horizonlab/parity"

RULES = [
    # ServerLevel(server, storage, ..., ResourceKey, LevelStem, boolean, long, List, boolean): 1.21.11 adds RandomSequences.
    (re.compile(r"(super\(\s*(?:null\s*,\s*){6}false\s*,\s*0L\s*,\s*List\.of\(\)\s*,\s*false)\s*\)"), r"\1,null)"),
    # BlockState's codec had a separate full variant in 26.3.
    (re.compile(r"\bBlockState\.FULL_CODEC\b|\bFULL_CODEC\b"), "CODEC"),
    # Items build their components in the constructor in 1.21.11 (see Compat.bind).
    (re.compile(r"([\w.]+(?:\[(?:[^\[\]]|\[[^\]]*\])*\])?)\.builtInRegistryHolder\(\)\.bindComponents\("), r"dev.horizonlab.parity.Compat.bind(\1, "),
    # Renamed: EntityTypes -> EntityType, ChunkPos.pack -> ChunkPos.asLong.
    (re.compile(r"\bEntityTypes\b"), "EntityType"),
    (re.compile(r"\bChunkPos\.pack\("), "ChunkPos.asLong("),
]


def patch(text):
    for rule, repl in RULES:
        text = rule.sub(repl, text)
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    out = pathlib.Path(ap.parse_args().out) / PARITY
    out.mkdir(parents=True, exist_ok=True)
    written = 0
    for src in sorted((TEST / "java" / PARITY).glob("*.java")):
        if (TEST / "java-1.21.11" / PARITY / src.name).exists():
            continue
        text = src.read_text(encoding="utf-8")
        new = patch(text)
        if new != text:
            (out / src.name).write_text(new, encoding="utf-8")
            written += 1
    print(written)


if __name__ == "__main__":
    main()
