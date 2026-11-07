#!/usr/bin/env python3
"""Diff two Minecraft releases' generated registries and blocks from the public misode/mcmeta mirror.

  mcmeta_diff.py OLD NEW [--top N] [--json OUT]

A network-only stand-in for the data half of `version_port.py diff` when Mojang's download hosts are not
reachable (it needs only raw.githubusercontent.com). It compares the registry summaries (every registry's entry
names) and the block summaries (block names with their properties and default states). It says nothing about
code behaviour, packets or worldgen logic: those need the jars.
"""
import argparse
import json
import sys
import urllib.request

BASE = "https://raw.githubusercontent.com/misode/mcmeta/{v}-summary/{kind}/data.min.json"


def fetch(version, kind):
    with urllib.request.urlopen(BASE.format(v=version, kind=kind), timeout=60) as r:
        return json.load(r)


def diff_registries(old, new):
    out = {"only_new": sorted(set(new) - set(old)), "only_old": sorted(set(old) - set(new)), "changed": []}
    for name in sorted(set(old) & set(new)):
        added = [x for x in new[name] if x not in old[name]]
        removed = [x for x in old[name] if x not in new[name]]
        if added or removed:
            out["changed"].append({"registry": name, "old": len(old[name]), "new": len(new[name]),
                                   "added": len(added), "removed": len(removed)})
    return out


def diff_blocks(old, new):
    shared = set(old) & set(new)
    changed = [b for b in sorted(shared) if json.dumps(old[b], sort_keys=True) != json.dumps(new[b], sort_keys=True)]
    return {"old": len(old), "new": len(new), "added": sorted(set(new) - set(old)),
            "removed": sorted(set(old) - set(new)), "changed": changed}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("old")
    p.add_argument("new")
    p.add_argument("--top", type=int, default=15)
    p.add_argument("--json", help="write the full diff here")
    a = p.parse_args()
    reg = diff_registries(fetch(a.old, "registries"), fetch(a.new, "registries"))
    blk = diff_blocks(fetch(a.old, "blocks"), fetch(a.new, "blocks"))
    print(f"registries only in {a.new}: {len(reg['only_new'])}; only in {a.old}: {len(reg['only_old'])}; "
          f"shared registries that differ: {len(reg['changed'])}")
    print(f"  only in {a.new}: {', '.join(reg['only_new'])}")
    print(f"  only in {a.old}: {', '.join(reg['only_old'])}")
    for row in sorted(reg["changed"], key=lambda r: -(r["added"] + r["removed"]))[: a.top]:
        print(f"  {row['registry']}: {row['old']} -> {row['new']} (+{row['added']} -{row['removed']})")
    print(f"blocks {blk['old']} -> {blk['new']}: +{len(blk['added'])} -{len(blk['removed'])}, "
          f"{len(blk['changed'])} shared blocks changed properties or states")
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"registries": reg, "blocks": blk}, f, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
