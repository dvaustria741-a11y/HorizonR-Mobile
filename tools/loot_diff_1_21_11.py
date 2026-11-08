"""Loot tables of 1.21.11 against 26.3 after mapping the two JSON shapes onto one (26.3 renamed `functions` to
`modifier`, `conditions` to `condition`, `function`/`condition` to `type`, and drops defaults).

  loot_diff_1_21_11.py DATAGEN_OUT MCMETA_26_3_CHECKOUT [table]

The 26.3 side is a checkout of misode/mcmeta branch 26.3-data. What still differs may be representation the
normaliser does not know or a real change: read the tables.
"""
import json,os,sys,collections
def num(x):
    return int(x) if isinstance(x,float) and x==int(x) else x
def canon(o,v26):
    if isinstance(o,list): return [canon(i,v26) for i in o]
    if isinstance(o,dict):
        d={}
        for k,v in o.items():
            if k=='bonus_rolls' and v in (0,0.0): continue
            if k=='add' and v is False: continue
            if k.startswith('minecraft:') and k.count(':')==1 and k.split(':')[1] in ('components','flags','equipment','vehicle','entity_type'): k=k.split(':')[1]
            if v26 and k=='entity_type': k='type'
            if v26:
                if k=='modifier': k='functions'
                elif k=='condition': k='conditions'; v=v if isinstance(v,list) else [v]
            else:
                if k in('function','condition') and isinstance(v,str): k='type'
            d[k]=canon(v,v26)
        return d
    return num(o)
def load(p,v26): return canon(json.load(open(p)),v26)
def walk(root):
    for d,_,fs in os.walk(root):
        for f in fs:
            if f.endswith('.json'): yield os.path.relpath(os.path.join(d,f),root)
A=sys.argv[1]+'/data/minecraft/loot_table';B=sys.argv[2]+'/data/minecraft/loot_table'
a=set(walk(A));b=set(walk(B))
res=collections.OrderedDict()
for k in sorted(a&b):
    x,y=load(f'{A}/{k}',False),load(f'{B}/{k}',True)
    x.pop('random_sequence',None);y.pop('random_sequence',None)
    res[k]=(x==y)
same=[k for k,v in res.items() if v];diff=[k for k,v in res.items() if not v]
print('compared',len(res),'same after normalising:',len(same),'differ:',len(diff))
for p in ('entities/','gameplay/','blocks/','chests/'):
    s=[k for k in same if k.startswith(p)];d=[k for k in diff if k.startswith(p)]
    print(p,'same',len(s),'differ',len(d))
print('differing:',*diff,sep='\n  ')
if len(sys.argv)>3:
    k=sys.argv[3]
    print(json.dumps(load(f'{A}/{k}',False),sort_keys=True)[:700]);print(json.dumps(load(f'{B}/{k}',True),sort_keys=True)[:700])
