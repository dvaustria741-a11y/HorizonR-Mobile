#!/usr/bin/env python3
"""Assemble a generator-ready worldgen directory for Minecraft 1.21.11 (what `GeneratorData::load` reads).

  assemble_worldgen_1_21_11.py --upgraded UP --datagen DATAGEN --exports EXPORTS --client-jar JAR --out OUT

UP is 1.21.11's data pack after the worldgen upgrade (`HORIZON_UPGRADE_OUT` of
`horizon-port-pack/tests/upgrade_1_21_11.rs`, which rewrites it into the formats the engine reads); DATA is the data
generator's output folder (`data` and `reports`; tags are read from it as they are), EXPORTS the output of
`tools/export_1_21_11.py`, JAR the client jar (structure templates). Mojang's files are not part of this repository.
"""
import argparse
import json
import pathlib
import shutil
import sys
import zipfile

# Pack folder (under data/<namespace>/) -> bundle name: horizon-world's `KINDS` and `TAG_KINDS`.
KINDS = {
    "dimension": "dimensions",
    "dimension_type": "dimension_types",
    "worldgen/biome": "biomes",
    "worldgen/block_state_provider": "state_providers",
    "worldgen/carver": "carvers",
    "worldgen/feature": "features",
    "worldgen/density_function": "densities",
    "worldgen/material_condition": "conditions",
    "worldgen/material_rule": "rules",
    "worldgen/noise": "noises",
    "worldgen/noise_settings": "settings",
    "worldgen/placed_feature": "placed_features",
    "worldgen/processor_list": "processor_lists",
    "worldgen/structure": "structures",
    "worldgen/structure_set": "structure_sets",
    "worldgen/template_pool": "template_pools",
    "worldgen/world_preset": "world_presets",
}
TAG_KINDS = {"tags/block": "block_tags", "tags/fluid": "fluid_tags", "tags/worldgen/biome": "biome_tags"}


def bundle(root, kind):
    """`{ns:path: JSON text}` for every `data/<ns>/<kind>/**/*.json` under root."""
    out = {}
    for ns in sorted((root / "data").glob("*")):
        base = ns / kind
        for f in sorted(base.rglob("*.json")):
            out[f"{ns.name}:{f.relative_to(base).with_suffix('').as_posix()}"] = f.read_text(encoding="utf-8")
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("upgraded", "datagen", "exports", "client-jar", "out"):
        p.add_argument(f"--{name}", required=True)
    a = p.parse_args()
    up, datagen, exports, out = (pathlib.Path(x) for x in (a.upgraded, a.datagen, a.exports, a.out))
    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    write = lambda name, obj: (out / name).write_text(json.dumps(obj, separators=(",", ":")), encoding="utf-8")
    counts = {}
    for kind, name in KINDS.items():
        counts[name] = len(b := bundle(up, kind))
        # `conditions` is a required bundle; 1.21.11 writes material conditions inline, so it may be empty.
        if b or name == "conditions":
            write(f"{name}.json", b)
    for kind, name in TAG_KINDS.items():
        counts[name] = len(b := bundle(datagen, kind))
        write(f"{name}.json", b)
    # 1.21.11 has no `blocks_motion_in_heightmap` tags (it asks `BlockState.blocksMotion()`); the exporter wrote them.
    motion = exports / "motion-tags.json"
    if motion.exists():
        tags = json.loads((out / "block_tags.json").read_text(encoding="utf-8"))
        exported = json.loads(motion.read_text(encoding="utf-8"))
        for name in ("blocks_motion_in_heightmap", "blocks_motion_in_heightmap_no_leaves"):
            tags[f"minecraft:{name}"] = json.dumps({"values": exported[name]}, separators=(",", ":"))
        write("block_tags.json", tags)
        counts["motion tags"] = {k: len(v) for k, v in exported.items()}
    # 26.3 has one `uncarvable` tag (bedrock); 1.21.11 gives each carver a `replaceable` list, and the upgrade drops
    # it. The Overworld's carvers all use `overworld_carver_replaceables`, so its `uncarvable` is every other block.
    # Nether and End carving need a table of their own per dimension, which the generator does not have yet:
    # this directory is right for the Overworld only.
    def expand(tag, seen=()):
        f = datagen / "data/minecraft/tags/block" / f"{tag}.json"
        names = set()
        for v in json.loads(f.read_text(encoding="utf-8"))["values"]:
            v = v["id"] if isinstance(v, dict) else v
            names |= expand(v[len("#minecraft:"):], seen + (tag,)) if v.startswith("#") else {v}
        return names

    tags = json.loads((out / "block_tags.json").read_text(encoding="utf-8"))
    all_blocks = set(json.loads((datagen / "reports/blocks.json").read_text(encoding="utf-8")))
    uncarvable = sorted(all_blocks - expand("overworld_carver_replaceables"))
    tags["minecraft:uncarvable"] = json.dumps({"values": uncarvable}, separators=(",", ":"))
    write("block_tags.json", tags)
    counts["uncarvable (Overworld only)"] = len(uncarvable)
    # [is_air, has_fluid] per registry state, from the registry facts export.
    facts = json.loads((exports / "facts.json").read_text(encoding="utf-8"))
    write("block_flags.json", [[row[1], row[2]] for row in facts["states"]])
    shutil.copy(exports / "facts.json", out / "facts.json")
    shutil.copytree(datagen / "reports" / "biome_parameters" / "minecraft", out / "biome_parameters", dirs_exist_ok=True)
    (out / "blocks").mkdir(exist_ok=True)
    for f in ("block-behaviours.json", "gameplay-facts.json", "structure-blocks.json", "entity-types.json"):
        shutil.copy(exports / f, out / "blocks" / f)
    with zipfile.ZipFile(a.client_jar) as z:
        n = 0
        for info in z.infolist():
            if info.filename.startswith("data/minecraft/structure/") and info.filename.endswith(".nbt"):
                target = out / "structure" / info.filename.removeprefix("data/minecraft/structure/")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(z.read(info))
                n += 1
    counts["structure templates"] = n
    print(json.dumps(counts, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
