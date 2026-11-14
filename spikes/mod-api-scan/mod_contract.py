#!/usr/bin/env python3
"""Extract the Mojang/Fabric contract a Fabric mod jar needs, as Markdown.

    mod_contract.py MOD.jar > contract.md

Lists, from the jar's own bytes only (no Minecraft files are read): entrypoints, Fabric API classes used, the
Mojang members referenced (with descriptors), and for every Mixin class its targets and each injector with its
method strings and @At specs. This is the exact surface a replacement class set must provide, and the injection
points it must contain. Counts are lower bounds: only what is written in the jar is seen.
"""
import json
import re
import struct
import sys
import zipfile
from collections import Counter, defaultdict


class Reader:
    def __init__(self, data):
        self.d, self.p = data, 0

    def u1(self):
        v = self.d[self.p]; self.p += 1; return v

    def u2(self):
        v = struct.unpack('>H', self.d[self.p:self.p + 2])[0]; self.p += 2; return v

    def u4(self):
        v = struct.unpack('>I', self.d[self.p:self.p + 4])[0]; self.p += 4; return v

    def skip(self, n):
        self.p += n


def parse_class(data):
    r = Reader(data)
    assert r.u4() == 0xCAFEBABE
    r.skip(4)
    n = r.u2()
    cp = [None] * n
    i = 1
    while i < n:
        t = r.u1()
        if t == 1:
            l = r.u2(); cp[i] = (1, data[r.p:r.p + l].decode('utf-8', 'replace')); r.skip(l)
        elif t == 3: cp[i] = (3, struct.unpack('>i', data[r.p:r.p + 4])[0]); r.skip(4)
        elif t == 4: cp[i] = (4, struct.unpack('>f', data[r.p:r.p + 4])[0]); r.skip(4)
        elif t == 5: cp[i] = (5, struct.unpack('>q', data[r.p:r.p + 8])[0]); r.skip(8); i += 1
        elif t == 6: cp[i] = (6, struct.unpack('>d', data[r.p:r.p + 8])[0]); r.skip(8); i += 1
        elif t in (7, 8, 16, 19, 20): cp[i] = (t, r.u2())
        elif t in (9, 10, 11, 12, 17, 18): cp[i] = (t, (r.u2(), r.u2()))
        elif t == 15: r.skip(3)
        else: raise ValueError(t)
        i += 1

    def utf(k): return cp[k][1]
    def cls(k): return utf(cp[k][1]).lstrip('[') if k else None

    r.u2()
    this = cls(r.u2()); sup = r.u2()
    supername = cls(sup) if sup else None
    for _ in range(r.u2()): r.u2()

    def element():
        tag = chr(r.u1())
        if tag == 's': return utf(r.u2())
        if tag in 'BCDFIJSZ': return cp[r.u2()][1]
        if tag == 'e': r.u2(); return utf(r.u2())
        if tag == 'c': return 'class ' + utf(r.u2())
        if tag == '@': return annotation()
        if tag == '[': return [element() for _ in range(r.u2())]
        raise ValueError(tag)

    def annotation():
        t = utf(r.u2())
        return {'type': t, 'values': {utf(r.u2()): element() for _ in range(r.u2())}}

    def attrs(collect_annotations):
        found = []
        for _ in range(r.u2()):
            name = utf(r.u2()); length = r.u4(); end = r.p + length
            if collect_annotations and name in ('RuntimeVisibleAnnotations', 'RuntimeInvisibleAnnotations'):
                found += [annotation() for _ in range(r.u2())]
            r.p = end
        return found

    fields = []
    for _ in range(r.u2()):
        r.u2(); name = utf(r.u2()); desc = utf(r.u2()); fields.append((name, desc, attrs(True)))
    methods = []
    for _ in range(r.u2()):
        r.u2(); name = utf(r.u2()); desc = utf(r.u2()); methods.append((name, desc, attrs(True)))
    class_annotations = attrs(True)

    refs = {'classes': set(), 'methods': set(), 'fields': set()}
    for k, e in enumerate(cp):
        if not e: continue
        if e[0] == 7:
            refs['classes'].add(cls(k))
        elif e[0] in (9, 10, 11):
            nt = cp[e[1][1]][1]
            refs['fields' if e[0] == 9 else 'methods'].add((cls(e[1][0]), utf(nt[0]), utf(nt[1])))
    return {'name': this, 'super': supername, 'fields': fields, 'methods': methods,
            'annotations': class_annotations, 'refs': refs}


def short(t):
    return t.rstrip(';').split('/')[-1]


def fmt(v):
    if isinstance(v, dict):
        inner = ', '.join(f'{k}={fmt(x)}' for k, x in v['values'].items())
        return f"@{short(v['type'])}({inner})"
    if isinstance(v, list):
        return '[' + ', '.join(fmt(x) for x in v) + ']'
    return str(v)


def main(path):
    z = zipfile.ZipFile(path)
    fm = json.loads(z.read('fabric.mod.json'))
    classes = {}
    for n in z.namelist():
        if n.endswith('.class'):
            try: classes[n[:-6]] = parse_class(z.read(n))
            except Exception as e: print(f'<!-- unparsed {n}: {e} -->', file=sys.stderr)
    mixin_names = set()
    for m in fm.get('mixins', []):
        cfg = m if isinstance(m, str) else m['config']
        j = json.loads(z.read(cfg).decode('utf-8-sig'))
        pkg = j.get('package', '').replace('.', '/')
        for key in ('mixins', 'client', 'server'):
            mixin_names |= {f"{pkg}/{c.replace('.', '/')}" for c in j.get(key, [])}

    mc_classes, mc_methods, mc_fields, fabric_api = set(), set(), set(), set()
    for c in classes.values():
        mc_classes |= {x for x in c['refs']['classes'] if x.startswith('net/minecraft/')}
        mc_methods |= {x for x in c['refs']['methods'] if x[0].startswith('net/minecraft/')}
        mc_fields |= {x for x in c['refs']['fields'] if x[0].startswith('net/minecraft/')}
        fabric_api |= {x for x in c['refs']['classes'] if x.startswith('net/fabricmc/fabric/api/')}

    print(f"# Contract: {fm.get('id')} {fm.get('version')}\n")
    print(f"Source: `{path.split('/')[-1]}`. Environment `{fm.get('environment', '*')}`. Depends: `{fm.get('depends')}`.\n")
    print('## Entrypoints\n')
    for kind, items in fm.get('entrypoints', {}).items():
        for it in items:
            print(f"- {kind}: `{it if isinstance(it, str) else it.get('value')}`")
    print(f"\n## Totals (lower bounds)\n\n- Mojang classes referenced: {len(mc_classes)}\n- Mojang methods referenced: {len(mc_methods)}\n- Mojang fields referenced: {len(mc_fields)}\n- Fabric API classes referenced: {len(fabric_api)}\n- Mixin classes: {len(mixin_names & set(classes))}\n")
    print('## Fabric API classes used\n')
    for x in sorted(fabric_api): print(f'- `{x}`')

    ann = Counter()
    print('\n## Mixins\n')
    for name in sorted(mixin_names & set(classes)):
        c = classes[name]
        targets = []
        for a in c['annotations']:
            if short(a['type']) == 'Mixin':
                v = a['values']
                targets += v.get('value', []) if isinstance(v.get('value', []), list) else [v['value']]
                targets += [f'string:{t}' for t in (v.get('targets', []) if isinstance(v.get('targets', []), list) else [v['targets']])]
        print(f"### `{name}`\n\nTargets: {', '.join(f'`{t}`' for t in targets) or '(none found)'}\n")
        for mname, mdesc, anns in c['methods']:
            for a in anns:
                kind = short(a['type'])
                if a['type'].startswith('Lorg/spongepowered/asm/mixin/') or a['type'].startswith('Lcom/llamalad7/'):
                    ann[kind] += 1
                    print(f"- `{mname}{mdesc}`: {fmt(a)}")
        for fname, fdesc, anns in c['fields']:
            for a in anns:
                if a['type'].startswith('Lorg/spongepowered/asm/mixin/'):
                    ann[short(a['type'])] += 1
                    print(f"- field `{fname}` `{fdesc}`: {fmt(a)}")
        print()
    print('## Injector and annotation counts\n')
    for k, v in ann.most_common(): print(f'- {k}: {v}')
    print('\n## Mojang methods referenced\n')
    for cl, n, d in sorted(mc_methods): print(f'- `{cl}.{n}{d}`')
    print('\n## Mojang fields referenced\n')
    for cl, n, d in sorted(mc_fields): print(f'- `{cl}.{n}:{d}`')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr); sys.exit(2)
    main(sys.argv[1])
