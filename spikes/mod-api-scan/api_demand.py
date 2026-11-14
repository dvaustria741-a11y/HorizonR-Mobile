#!/usr/bin/env python3
"""Aggregate the Mojang API demand of many Fabric jars (nested jars included) and split it client / common.

    api_demand.py JAR [JAR ...]      -> Markdown on stdout

Client = net/minecraft/client/**, com/mojang/blaze3d/**, com/mojang/renderpearl/**. Everything else under
net/minecraft/ or com/mojang/ is "common" (shared with or only in the server). Reads only the given jars.
"""
import io
import sys
import zipfile
from collections import defaultdict

from mod_contract import parse_class

CLIENT_PREFIXES = ('net/minecraft/client/', 'com/mojang/blaze3d/', 'com/mojang/renderpearl/')
GAME_PREFIXES = ('net/minecraft/', 'com/mojang/')


def tier(cls):
    return 'client' if cls.startswith(CLIENT_PREFIXES) else 'common'


def jars_of(path):
    z = zipfile.ZipFile(path)
    yield path.split('/')[-1], z
    for n in z.namelist():
        if n.startswith('META-INF/jars/') and n.endswith('.jar'):
            yield n.split('/')[-1], zipfile.ZipFile(io.BytesIO(z.read(n)))


def scan(zf):
    out = {'classes': set(), 'methods': set(), 'fields': set()}
    for n in zf.namelist():
        if not n.endswith('.class'):
            continue
        try:
            refs = parse_class(zf.read(n))['refs']
        except Exception:
            continue
        out['classes'] |= {c for c in refs['classes'] if c.startswith(GAME_PREFIXES)}
        out['methods'] |= {m for m in refs['methods'] if m[0].startswith(GAME_PREFIXES)}
        out['fields'] |= {f for f in refs['fields'] if f[0].startswith(GAME_PREFIXES)}
    return out


def main(paths):
    per_jar, users = {}, defaultdict(lambda: defaultdict(set))
    for path in paths:
        label = path.split('/')[-1]
        merged = {'classes': set(), 'methods': set(), 'fields': set()}
        for _, zf in jars_of(path):
            part = scan(zf)
            for k in merged:
                merged[k] |= part[k]
        per_jar[label] = merged
        for k in merged:
            for item in merged[k]:
                users[k][item].add(label)
    print('# Mojang API demand across jars\n')
    print('Lower bounds (constant pool only). Client = net/minecraft/client, blaze3d, renderpearl.\n')
    print('| Jar | classes | methods | fields | client classes | client methods |')
    print('|---|---|---|---|---|---|')
    for label, d in per_jar.items():
        cc = sum(1 for c in d['classes'] if tier(c) == 'client')
        cm = sum(1 for m in d['methods'] if tier(m[0]) == 'client')
        print(f"| {label} | {len(d['classes'])} | {len(d['methods'])} | {len(d['fields'])} | {cc} | {cm} |")
    allc, allm, allf = users['classes'], users['methods'], users['fields']
    cc = sum(1 for c in allc if tier(c) == 'client')
    cm = sum(1 for m in allm if tier(m[0]) == 'client')
    print(f"\n**Union:** {len(allc)} classes ({cc} client, {len(allc) - cc} common), "
          f"{len(allm)} methods ({cm} client), {len(allf)} fields.\n")
    shared = lambda table, n: sum(1 for v in table.values() if len(v) >= n)
    print(f"Used by 2+ jars: {shared(allc, 2)} classes, {shared(allm, 2)} methods, {shared(allf, 2)} fields. "
          f"Used by 3+ jars: {shared(allc, 3)} classes, {shared(allm, 3)} methods.\n")
    pk = defaultdict(int)
    for c in allc:
        pk['/'.join(c.split('/')[:4])] += 1
    print('## Classes by package (top 14)\n')
    for p, n in sorted(pk.items(), key=lambda kv: -kv[1])[:14]:
        print(f'- {n}  `{p}`')
    print('\n## Most demanded classes (by number of jars)\n')
    for c, v in sorted(allc.items(), key=lambda kv: (-len(kv[1]), kv[0]))[:25]:
        print(f'- {len(v)}  `{c}`  ({tier(c)})')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr); sys.exit(2)
    main(sys.argv[1:])
