"""Assets.car rendition extractor (BOM-indexed).

Walks the RENDITIONS BOM tree to pair each rendition key with its CSI header,
decodes LZFSE bitmaps (GA8/ARGB) to PNG, writes DWAR payloads (PDF/SVG) raw,
and resolves INLK internal links by cropping the referenced packed atlas.

usage: carextract.py <Assets.car> <outdir> [name-regex]
"""
import os
import re
import struct
import sys

import liblzfse
from PIL import Image


class Bom:
    def __init__(self, data):
        assert data[:8] == b'BOMStore'
        self.d = data
        (_v, _n, idx_off, _idx_len, vars_off, _vars_len) = struct.unpack_from('>6I', data, 8)
        count = struct.unpack_from('>I', data, idx_off)[0]
        self.blocks = [struct.unpack_from('>II', data, idx_off + 4 + 8 * i) for i in range(count)]
        self.vars = {}
        n = struct.unpack_from('>I', data, vars_off)[0]
        o = vars_off + 4
        for _ in range(n):
            bi, ln = struct.unpack_from('>IB', data, o)
            self.vars[data[o + 5:o + 5 + ln].decode()] = bi
            o += 5 + ln

    def block(self, i):
        a, ln = self.blocks[i]
        return self.d[a:a + ln]

    def tree(self, name):
        t = self.block(self.vars[name])
        assert t[:4] == b'tree'
        child = struct.unpack_from('>I', t, 8)[0]
        p = self.block(child)
        while struct.unpack_from('>H', p, 0)[0] == 0:  # descend to first leaf
            p = self.block(struct.unpack_from('>I', p, 12)[0])
        while True:
            _leaf, cnt, fwd, _back = struct.unpack_from('>HHII', p, 0)
            for k in range(cnt):
                vi, ki = struct.unpack_from('>II', p, 12 + 8 * k)
                yield self.block(ki), self.block(vi)
            if not fwd:
                return
            p = self.block(fwd)


def parse_csi(v):
    assert v[:4] == b'ISTC'
    w, h, scale, pf = struct.unpack_from('<IIII', v, 12)
    layout = struct.unpack_from('<H', v, 36)[0]
    name = v[40:168].split(b'\0', 1)[0].decode('utf-8', 'replace')
    tlv_len, _a, _b, rend_len = struct.unpack_from('<IIII', v, 168)
    tlvs = {}
    t = v[184:184 + tlv_len]
    o = 0
    while o + 8 <= len(t):
        tag, ln = struct.unpack_from('<II', t, o)
        tlvs[tag] = t[o + 8:o + 8 + ln]
        o += 8 + ln
    payload = v[184 + tlv_len:184 + tlv_len + rend_len]
    return dict(w=w, h=h, scale=scale, fmt=struct.pack('>I', pf).decode('latin1'),
                layout=layout, name=name, tlvs=tlvs, payload=payload)


def decode_pixels(r):
    p = r['payload']
    if p[:4] != b'MLEC':
        return None
    _flags, comp, length = struct.unpack_from('<III', p, 4)
    try:
        raw = liblzfse.decompress(p[16:16 + length]) if comp == 11 else None
    except liblzfse.error:
        raw = None
    if raw is None:
        print('undecodable bitmap:', r['name'])
        return None
    w, h = r['w'], r['h']
    if r['fmt'] == 'GA8 ':
        row = len(raw) // h
        return Image.frombuffer('LA', (w, h), raw, 'raw', 'LA', row, 1)
    if r['fmt'] == 'ARGB':
        row = len(raw) // h
        return Image.frombuffer('RGBA', (w, h), raw, 'raw', 'BGRA', row, 1).copy()
    return None


def safe(name):
    return ''.join(c if c.isalnum() or c in '._-@~ ' else '_' for c in name)


def main(car, outdir, pattern=None):
    rx = re.compile(pattern) if pattern else None
    bom = Bom(open(car, 'rb').read())
    kf = bom.block(bom.vars['KEYFORMAT'])
    n = struct.unpack_from('<I', kf, 8)[0]
    keyformat = struct.unpack_from(f'<{n}I', kf, 12)
    by_key = {}
    entries = []
    for key, val in bom.tree('RENDITIONS'):
        r = parse_csi(val)
        by_key[key] = r
        entries.append((key, r))
    os.makedirs(outdir, exist_ok=True)
    atlas_cache = {}
    written = 0
    for key, r in entries:
        name = r['name']
        if name.startswith('ZZZZPackedAsset') or (rx and not rx.search(name)):
            continue
        stem = os.path.splitext(safe(name))[0]
        p = r['payload']
        img = None
        if p[:4] == b'DWAR':
            ln = struct.unpack_from('<I', p, 8)[0]
            body = p[12:12 + ln]
            ext = '.pdf' if body.startswith(b'%PDF') else '.svg' if r['fmt'] == 'SVG ' else '.bin'
            open(os.path.join(outdir, stem + ext), 'wb').write(body)
            written += 1
            continue
        if 1010 in r['tlvs'] and r['tlvs'][1010][:4] == b'KLNI':
            t = r['tlvs'][1010]
            x, y, w, h = struct.unpack_from('<IIII', t, 8)
            # link key is (attribute, value) u16 pairs; rebuild the fixed-order BOM key
            _lay, klen = struct.unpack_from('<HH', t, 24)
            kb = t[30:28 + klen]
            attrs = dict(struct.unpack_from('<HH', kb, o) for o in range(0, len(kb) - 3, 4))
            akey = struct.pack(f'<{len(keyformat)}H', *(attrs.get(a, 0) for a in keyformat))
            atlas = by_key.get(akey)
            if atlas is None:
                print('missing atlas for', name)
                continue
            if id(atlas) not in atlas_cache:
                atlas_cache[id(atlas)] = decode_pixels(atlas)
            a = atlas_cache[id(atlas)]
            if a is not None:
                img = a.crop((x, y, x + w, y + h))
        else:
            img = decode_pixels(r)
        if img is not None:
            path = os.path.join(outdir, stem + '.png')
            if os.path.exists(path):
                path = os.path.join(outdir, f'{stem}.{key.hex()[:12]}.png')
            img.save(path)
            written += 1
    print(f'{len(entries)} renditions, {written} written to {outdir}')


if __name__ == '__main__':
    main(*sys.argv[1:])
