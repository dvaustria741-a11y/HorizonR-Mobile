import io, json, re, struct, sys, zipfile, collections

def parse(data):
    n = struct.unpack('>H', data[8:10])[0]; p = 10; e = [None]*n; i = 1
    while i < n:
        t = data[p]
        if t == 1:
            l = struct.unpack('>H', data[p+1:p+3])[0]; e[i] = (1, data[p+3:p+3+l].decode('utf-8','replace')); p += 3+l
        elif t in (3,4): p += 5
        elif t in (5,6): p += 9; i += 1
        elif t in (7,8,16,19,20): e[i] = (t, struct.unpack('>H', data[p+1:p+3])[0]); p += 3
        elif t in (9,10,11,12,17,18): e[i] = (t, struct.unpack('>HH', data[p+1:p+5])); p += 5
        elif t == 15: p += 4
        else: raise ValueError(t)
        i += 1
    return e

def utf(e, i): return e[i][1] if e[i] and e[i][0] == 1 else ''
def cname(e, i):
    s = utf(e, e[i][1]); return s.lstrip('[')

def analyse(data, mixin=False, st=None):
    e = parse(data)
    for i, x in enumerate(e):
        if not x: continue
        t = x[0]
        if t == 7:
            c = cname(e, i)
            if c.startswith('L') and c.endswith(';'): c = c[1:-1]
            if c.startswith('net/minecraft/'): st['classes'].add(c)
        elif t in (9, 10, 11):
            c = cname(e, x[1][0]); nt = e[x[1][1]][1]
            if c.startswith('net/minecraft/'):
                st['fields' if t == 9 else 'methods'].add((c, utf(e, nt[0]), utf(e, nt[1])))
        elif t == 1 and mixin:
            s = x[1]
            for m in re.findall(r'Lnet/minecraft/[\w/$]+;', s): st['mixin_refs'].add(m[1:-1])
            if s.startswith('Lorg/spongepowered/asm/mixin/') or s.startswith('Lcom/llamalad7/mixinextras/'):
                st['ann'][s.split('/')[-1].rstrip(';')] += 1

def scan_jar(zf, st, mixin_classes):
    for name in zf.namelist():
        if name.endswith('.class'):
            internal = name[:-6]
            try: analyse(zf.read(name), mixin=internal in mixin_classes, st=st)
            except Exception: st['bad'] += 1

def run(path):
    z = zipfile.ZipFile(path)
    fm = json.loads(z.read('fabric.mod.json'))
    st = dict(classes=set(), fields=set(), methods=set(), mixin_refs=set(), ann=collections.Counter(), bad=0)
    mixins = set()
    cfgs = []
    for m in fm.get('mixins', []):
        cfgs.append(m if isinstance(m, str) else m.get('config'))
    for cfg in cfgs:
        j = json.loads(z.read(cfg).decode('utf-8-sig'))
        pkg = j.get('package', '').replace('.', '/')
        for k in ('mixins', 'client', 'server'):
            for c in j.get(k, []): mixins.add(f'{pkg}/{c.replace(".", "/")}')
    scan_jar(z, st, mixins)
    nested = [n for n in z.namelist() if n.startswith('META-INF/jars/') and n.endswith('.jar')]
    deps = fm.get('depends', {})
    print(f"== {path.split('/')[-1]}")
    print(f"id={fm.get('id')} version={fm.get('version')} env={fm.get('environment','*')} entrypoints={ {k:len(v) for k,v in fm.get('entrypoints',{}).items()} }")
    print(f"depends={deps}")
    print(f"mixin configs={cfgs} mixin classes={len(mixins)} nested jars={len(nested)} unparsed classes={st['bad']}")
    print(f"MC classes referenced={len(st['classes'])} distinct MC methods={len(st['methods'])} fields={len(st['fields'])}")
    print(f"mixin annotations={dict(st['ann'])}")
    print(f"MC classes named in mixin classes={len(st['mixin_refs'])}")
    return st

tot = dict(classes=set(), methods=set(), fields=set())
for p in sys.argv[1:]:
    s = run(p)
    for k in tot: tot[k] |= s[k]
    print()
print(f"UNION of the {len(sys.argv)-1} mods: classes={len(tot['classes'])} methods={len(tot['methods'])} fields={len(tot['fields'])}")
