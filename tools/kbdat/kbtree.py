"""Decode a UIKBTree from KBLayouts_*.dat into nested dicts (work in progress).

usage: kbtree.py <dat> <layout-name> [maxdepth]
"""
import json
import sys

sys.path.insert(0, '.')
from kbdat import Dat  # noqa: E402

TYPES = {1: 'keyboard', 2: 'keyplane', 3: 'keylayout', 4: 'keyset', 5: 'geometrySet',
         6: 'attributeSet', 7: 'list', 8: 'key'}


class Tree:
    def __init__(self, path):
        self.k = Dat(path)
        self.d = self.k.d
        self.unknown = {}

    def sv(self, o):
        b = self.d[o]
        o += 1
        neg = b & 0x40
        v = b & 0x3f
        s = 6
        while b & 0x80:
            b = self.d[o]
            o += 1
            v |= (b & 0x7f) << s
            s += 7
        return (-v if neg else v), o

    def dlist_body(self, n, o):
        v, o = self.k.leb(o)
        out = [v]
        for _ in range(n - 1):
            x, o = self.sv(o)
            out.append(out[-1] + x)
        return out, o

    def raw(self, a, n=16):
        return self.d[a:a + n].hex(' ')

    def obj(self, o, depth):
        """Decode the tagged object at absolute file offset o. Returns (value, end)."""
        ty, n, q = self.k.tag(o)
        if ty == 3:
            return self.d[q:q + n].decode('utf-8', 'replace'), q + n
        if ty == 4:
            parts = []
            for _ in range(n):
                v, q = self.k.leb(q)
                parts.append(v)
            return ''.join(self.strref(p) for p in parts), q
        if ty == 1:
            v, q = self.sv(q)
            return v, q
        if ty == 0xd:
            refs, q = self.dlist_body(n, q)
            return [self.ref(r, depth) for r in refs], q
        if ty == 7:
            ktag, kn, q = self.k.tag(q)
            keys, q = self.dlist_body(kn, q)
            vtag, vn, q = self.k.tag(q)
            vals, q = self.dlist_body(vn, q)
            return {self.k.string(a) or f'@{a}': self.ref(b, depth) for a, b in zip(keys, vals)}, q
        if ty == 0xb:
            return self.node(n, q, depth)
        if ty == 0 and n == 0:
            target, q = self.k.leb(q)
            return self.ref(target, depth), q
        if ty == 9:
            # geometry/shape: groups whose tag byte holds 2 bits per field (x, y, w, h from the low bits)
            groups = []
            for _ in range(2):
                mask = self.d[q]
                q += 1
                vals = []
                for f in range(4):
                    bits = (mask >> (2 * f)) & 3
                    if bits:
                        v, q = self.sv(q)
                        vals.append((v, bits))
                    else:
                        vals.append(None)
                groups.append(vals)
            return {'geom': groups, 'n': n}, q
        self.unknown.setdefault((ty, n), self.raw(o))
        return f'<kind {ty:x}.{n} @{o - self.k.B}: {self.raw(o, 12)}>', o + 1

    def ref(self, r, depth):
        if depth <= 0:
            return f'@{r}'
        v, _ = self.obj(self.k.B + r, depth - 1)
        return v

    def node(self, mask, q, depth):
        ver, q = self.k.leb(q)
        typ, q = self.k.leb(q)
        out = {'type': TYPES.get(typ, typ)}
        for _ in range(bin(mask).count('1')):
            ty, _n, _q = self.k.tag(q)
            v, q = self.obj(q, depth)
            key = {3: 'name', 4: 'name', 7: 'props', 0xd: 'subtrees'}.get(ty, f'field{ty:x}')
            if ty == 0 and isinstance(v, dict) and 'type' not in v and 'geom' not in v:
                key = 'props'
            elif ty == 0 and isinstance(v, str):
                key = 'name'
            elif ty == 0 and isinstance(v, list):
                key = 'subtrees'
            out[key] = v
        return out, q

    def strref(self, r):
        s = self.k.string(r)
        if s is None:
            v, _ = self.obj(self.k.B + r, 0)
            s = v if isinstance(v, str) else '?'
        return s


if __name__ == '__main__':
    t = Tree(sys.argv[1])
    depth = int(sys.argv[3]) if len(sys.argv) > 3 else 6
    root, _ = t.obj(t.k.B + t.k.index[sys.argv[2]], depth)
    sys.stdout.reconfigure(encoding='utf-8')
    print(json.dumps(root, ensure_ascii=False, indent=1)[:int(sys.argv[4]) if len(sys.argv) > 4 else 6000])
    print('unknown kinds:', t.unknown, file=sys.stderr)
