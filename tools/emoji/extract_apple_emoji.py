"""Pack Apple emoji art from your own IPSW into app/src/main/assets/apple_emoji.bin.

Personal use only: Apple's emoji art is licensed for Apple devices. The output is gitignored and
must never be committed or shared. The app works without it (it falls back to system emoji).

usage: python tools/emoji/extract_apple_emoji.py AppleColorEmoji-160px.ttc \\
           app/src/main/assets/emoji.tsv app/src/main/assets/apple_emoji.bin [size] [emjc-library]
needs: pip install fonttools uharfbuzz pillow pyliblzfse
optional: a build of tools/emoji/emjc.c (much faster than the pure-Python fallback), e.g.
           zig cc -shared -O2 -o build/emjc/emjc.dll tools/emoji/emjc.c

Each emoji string from emoji.tsv (base and skin-tone variants) is shaped with HarfBuzz against the
font, so ZWJ sequences, flags and tones resolve through the font's own substitution rules. The
glyph's sbix image at [size] px (default 96) is decoded ("emjc" = LZFSE + predictive coding;
"dupe"/"flip" reference another glyph) and re-encoded as WebP.

Output format (little-endian):
  "AEMJ", u32 version (1), u32 count, u32 pixel size,
  count x (u16 key length, UTF-8 key, u32 offset from file start, u32 length),
  then the WebP images.
"""
import ctypes
import io
import struct
import sys

import liblzfse
import uharfbuzz as hb
from fontTools.ttLib import TTCollection
from PIL import Image, ImageOps


def keys_from_tsv(path):
    keys = []
    for line in open(path, encoding='utf-8'):
        if line.startswith('#'):
            continue
        f = line.rstrip('\n').split('\t')
        keys.append(f[1])
        if len(f) > 3 and f[3]:
            keys.extend(f[3].split(' '))
    return keys


class EmjcDecoder:
    """emjc -> PIL image. Uses the C build of tools/emoji/emjc.c when given, else pure Python."""

    def __init__(self, library=None):
        self.lib = None
        if library:
            self.lib = ctypes.CDLL(library)
            self.lib.emjc_pixels.argtypes = [ctypes.c_char_p, ctypes.c_size_t, ctypes.c_int, ctypes.c_int,
                                             ctypes.c_int, ctypes.c_char_p]

    def decode(self, blob):
        if blob[:4] != b'emj1':
            raise ValueError('not emj1')
        w, h, appendix = struct.unpack_from('<HHH', blob, 8)
        data = liblzfse.decompress(blob[16:])
        if self.lib:
            out = ctypes.create_string_buffer(w * h * 4)
            if self.lib.emjc_pixels(data, len(data), w, h, appendix, out) != 0:
                raise ValueError('decode failed')
            bgra = out.raw
        else:
            bgra = self._decode_py(data, w, h, appendix)
        return Image.frombuffer('RGBA', (w, h), bgra, 'raw', 'BGRA', 0, 1)

    @staticmethod
    def _decode_py(data, w, h, appendix_len):
        n = w * h
        alpha, filt, res, app = data[:n], data[n:n + h], data[n + h:n + h + n * 3], data[n + h + n * 3:]
        v = [0] * (n * 3)
        at = 0
        for k in range(appendix_len):
            at += app[k] // 4
            if at >= n * 3:
                break
            v[at] = 128 * (app[k] % 4)
            at += 1

        def half(a, b):
            s = a + b + 1
            return -((-s) // 2) if s < 0 else s // 2

        def cdiv(a):  # C-style integer division by 2 (toward zero)
            return -((-a) // 2) if a < 0 else a // 2

        out = bytearray(n * 4)
        for y in range(h):
            f = filt[y]
            for x in range(w):
                i = y * w + x
                b0 = i * 3
                for ch in range(3):
                    r = res[b0 + ch]
                    v[b0 + ch] = -(r >> 1) - v[b0 + ch] if r & 1 else (r >> 1) + v[b0 + ch]
                lo = b0 - 3 if x > 0 else None
                up = b0 - w * 3 if y > 0 else None
                pred = None
                if f == 1:
                    if lo is not None and up is not None:
                        ul = up - 3
                        pred = up if abs(v[lo] - v[ul]) < abs(v[up] - v[ul]) else lo
                    else:
                        pred = lo if lo is not None else up
                elif f == 2:
                    pred = lo
                elif f == 3:
                    pred = up
                elif f == 4:
                    if lo is not None and up is not None:
                        for ch in range(3):
                            v[b0 + ch] += half(v[lo + ch], v[up + ch])
                    else:
                        pred = lo if lo is not None else up
                if pred is not None:
                    for ch in range(3):
                        v[b0 + ch] += v[pred + ch]
                base, p, q = v[b0], v[b0 + 1], v[b0 + 2]
                ph = cdiv(p) if p < 0 else cdiv(p + 1)
                pl = cdiv(p + 1) if p < 0 else cdiv(p)
                qh = cdiv(q) if q < 0 else cdiv(q + 1)
                ql = cdiv(q + 1) if q < 0 else cdiv(q)
                rr, gg, bb = base + ph - ql, base + qh, base - pl - ql
                out[i * 4:i * 4 + 4] = bytes((bb % 257 % 256, gg % 257 % 256, rr % 257 % 256, alpha[i]))
        return bytes(out)


def glyph_image(strike, name, decoder, depth=0):
    g = strike.glyphs.get(name)
    if g is None or depth > 4:
        return None
    if g.graphicType == 'emjc':
        return decoder.decode(g.imageData)
    if g.graphicType == 'png ':
        return Image.open(io.BytesIO(g.imageData)).convert('RGBA')
    if g.graphicType in ('dupe', 'flip'):
        img = glyph_image(strike, g.referenceGlyphName, decoder, depth + 1)
        return ImageOps.mirror(img) if img is not None and g.graphicType == 'flip' else img
    return None


def best_image(font, name, size, decoder):
    """The glyph at [size] px, else from the largest strike scaled to [size] (some glyphs lack a 96 px image)."""
    strikes = font['sbix'].strikes
    img = glyph_image(strikes[size], name, decoder) if size in strikes else None
    if img is None:
        for ppem in sorted(strikes, reverse=True):
            img = glyph_image(strikes[ppem], name, decoder)
            if img is not None:
                img = img.resize((round(img.width * size / ppem), round(img.height * size / ppem)), Image.LANCZOS)
                break
    return img


def compose(parts, size):
    """Places glyph images at their shaped pen positions (Apple draws couples as two halves) and
    centres the result in a size x size square."""
    width = max(x + img.width for x, img in parts)
    height = max(img.height for _, img in parts)
    row = Image.new('RGBA', (width, height))
    for x, img in parts:
        row.alpha_composite(img, (x, height - img.height))
    k = min(size / width, size / height)
    row = row.resize((max(1, round(width * k)), max(1, round(height * k))), Image.LANCZOS)
    out = Image.new('RGBA', (size, size))
    out.alpha_composite(row, ((size - row.width) // 2, (size - row.height) // 2))
    return out


def main(ttc_path, tsv_path, out_path, size=96, library=None):
    font = TTCollection(ttc_path, lazy=False).fonts[0]  # "Apple Color Emoji"
    strike = font['sbix'].strikes[size]
    order = font.getGlyphOrder()
    upem = font['head'].unitsPerEm
    hb_font = hb.Font(hb.Face(hb.Blob.from_file_path(ttc_path), 0))
    decoder = EmjcDecoder(library)

    images, missing = [], []
    for key in keys_from_tsv(tsv_path):
        buf = hb.Buffer()
        buf.add_str(key)
        buf.guess_segment_properties()
        hb.shape(hb_font, buf)
        # Selectors and joiners shape to empty glyphs; keep the ones with art, at their pen positions.
        parts, pen = [], 0
        for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
            name = order[info.codepoint]
            if name != '.notdef' and any(name in st.glyphs and st.glyphs[name].graphicType
                                         for st in font['sbix'].strikes.values()):
                art = best_image(font, name, size, decoder)
                if art is not None:
                    parts.append((round((pen + pos.x_offset) * size / upem), art))
            pen += pos.x_advance
        img = None
        if len(parts) == 1:
            img = parts[0][1]
        elif len(parts) > 1:
            img = compose(parts, size)
        if img is None:
            missing.append(key)
            continue
        out = io.BytesIO()
        img.save(out, 'WEBP', quality=85, method=4)
        images.append((key.encode('utf-8'), out.getvalue()))

    header = b'AEMJ' + struct.pack('<III', 1, len(images), size)
    offset = len(header) + sum(2 + len(k) + 8 for k, _ in images)
    index = bytearray()
    for k, data in images:
        index += struct.pack('<H', len(k)) + k + struct.pack('<II', offset, len(data))
        offset += len(data)
    with open(out_path, 'wb') as f:
        f.write(header)
        f.write(index)
        for _, data in images:
            f.write(data)

    total = len(images) + len(missing)
    print(f'{len(images)}/{total} emoji packed ({100 * len(images) / total:.1f} %), {offset / 1e6:.1f} MB')
    if missing:
        print('missing:', ' '.join(missing[:40]).encode('unicode_escape').decode())


if __name__ == '__main__':
    a = sys.argv
    main(a[1], a[2], a[3], int(a[4]) if len(a) > 4 else 96, a[5] if len(a) > 5 else None)
