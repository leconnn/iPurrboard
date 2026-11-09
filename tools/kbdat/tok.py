import sys; sys.path.insert(0,'.')
from kbdat import Dat
def tokens(k, rel, limit):
    d=k.d; o=k.B+rel; end=o+limit; out=[]
    while o<end:
        p=o; ty,n,o=k.tag(o)
        if ty==3: out.append((p-k.B,'str',d[o:o+n].decode('utf-8','replace'))); o+=n
        elif ty in (4,0xd):
            vs=[]
            for _ in range(n): v,o=k.leb(o); vs.append(v)
            out.append((p-k.B,'cat' if ty==4 else 'D', ''.join(k.string(x) or '?' for x in vs) if ty==4 else vs))
        else: out.append((p-k.B,f'T{ty:x}',n))
    return out
if __name__=='__main__':
    k=Dat(sys.argv[1]); rel=int(sys.argv[2]) if sys.argv[2].isdigit() else k.index[sys.argv[2]]
    for t in tokens(k,rel,int(sys.argv[3])): print(*t)
