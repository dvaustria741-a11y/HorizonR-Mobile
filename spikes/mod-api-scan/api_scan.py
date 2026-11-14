import io, re, struct, sys, zipfile, collections

def class_refs(data):
    """Distinct internal class names in a .class file's constant pool (Class entries)."""
    p = 8
    n = struct.unpack('>H', data[p:p+2])[0]; p += 2
    utf, cls = {}, []
    i = 1
    while i < n:
        t = data[p]
        if t == 1:
            l = struct.unpack('>H', data[p+1:p+3])[0]
            utf[i] = data[p+3:p+3+l].decode('utf-8', 'replace'); p += 3 + l
        elif t in (3, 4): p += 5
        elif t in (5, 6): p += 9; i += 1
        elif t == 7: cls.append(struct.unpack('>H', data[p+1:p+3])[0]); p += 3
        elif t in (8, 16, 19, 20): p += 3
        elif t in (9, 10, 11, 12, 17, 18): p += 5
        elif t == 15: p += 4
        else: raise ValueError(t)
        i += 1
    out = set()
    for c in cls:
        s = utf.get(c, '')
        s = s.lstrip('[')
        if s.startswith('L') and s.endswith(';'): s = s[1:-1]
        out.add(s)
    return out

def scan(zf, tag, acc, per):
    for name in zf.namelist():
        if name.endswith('.class'):
            try: refs = class_refs(zf.read(name))
            except Exception: continue
            mc = {r for r in refs if r.startswith('net/minecraft/')}
            acc |= mc; per[tag] |= mc

top = zipfile.ZipFile(sys.argv[1])
acc, per = set(), collections.defaultdict(set)
for name in top.namelist():
    if name.startswith('META-INF/jars/') and name.endswith('.jar'):
        scan(zipfile.ZipFile(io.BytesIO(top.read(name))), name.split('/')[-1].rsplit('+',1)[0], acc, per)
scan(top, 'fabric-api(top)', acc, per)
print('distinct net/minecraft classes referenced:', len(acc))
print('modules scanned:', len(per))
for k, v in sorted(per.items(), key=lambda kv: -len(kv[1]))[:8]: print(f'  {len(v):4d}  {k}')
pk = collections.Counter('/'.join(c.split('/')[:4]) for c in acc)
print('top packages:'); [print(f'  {n:4d}  {p}') for p, n in pk.most_common(8)]
