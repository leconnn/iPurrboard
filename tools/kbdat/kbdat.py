"""Exploratory reader for KBLayouts_*.dat (UIKBGraphSerialization).

Layout so far:
  [0, plist)            header listing screen families
  [plist, B)            bplist00: layout name -> record offset (relative to B)
  [B, ...)              pool; offsets everywhere are relative to B
  pool tag byte: low nibble = type, high nibble = small count (0xF => varint follows)
    type 3: UTF-8 string of <count> bytes
    type 4: string concatenated from <count> varint pool offsets
"""
import plistlib
import re
import struct
import sys


class Dat:
    def __init__(self, path):
        self.d = open(path, 'rb').read()
        m = re.search(rb'bplist00', self.d)
        self.p0 = m.start()
        self.B = self._find_plist_end()
        self.index = plistlib.loads(self.d[self.p0:self.B])
        self.strings = {}
        self._scan_strings()

    def _find_plist_end(self):
        d = self.d
        for i in range(self.p0, len(d) - 31):
            if d[i:i + 6] != b'\0' * 6:
                continue
            osz, rsz = d[i + 6], d[i + 7]
            if osz not in (1, 2, 3, 4, 8) or rsz not in (1, 2, 3, 4):
                continue
            no, top, ot = struct.unpack_from('>QQQ', d, i + 8)
            if 0 < no < 10 ** 7 and top < no and 8 < ot < i - self.p0:
                return i + 32
        raise ValueError('no plist trailer')

    def leb(self, o):
        v = s = 0
        while True:
            b = self.d[o]
            o += 1
            v |= (b & 0x7f) << s
            s += 7
            if b < 0x80:
                return v, o

    def tag(self, o):
        t = self.d[o]
        ty, n = t & 0xf, t >> 4
        o += 1
        if n == 0xf:
            n, o = self.leb(o)
        return ty, n, o

    def _scan_strings(self):
        o = self.B
        raw = {}
        while o < len(self.d):
            ty, n, q = self.tag(o)
            if ty == 3:
                raw[o - self.B] = self.d[q:q + n].decode('utf-8', 'replace')
                o = q + n
            elif ty == 4:
                parts = []
                for _ in range(n):
                    v, q = self.leb(q)
                    parts.append(v)
                raw[o - self.B] = parts
                o = q
            else:
                break
        self.strings_end = o - self.B

        def res(r):
            v = raw[r]
            return v if isinstance(v, str) else ''.join(res(x) for x in v)
        self.strings = {r: res(r) for r in raw}

    def string(self, r):
        return self.strings.get(r)


if __name__ == '__main__':
    k = Dat(sys.argv[1])
    print('plist', k.p0, 'B', k.B, 'strings end (rel)', k.strings_end, 'n', len(k.strings))
