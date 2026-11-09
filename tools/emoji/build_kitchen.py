"""Build app/src/main/assets/emoji_kitchen.dat: which emoji pairs have an Emoji Kitchen sticker.

usage: python -I tools/emoji/build_kitchen.py path/to/metadata.json
Source: the community index of Gboard's Emoji Kitchen combinations,
https://raw.githubusercontent.com/xsalazar/emoji-kitchen-backend/main/app/metadata.json
The stickers themselves are Google's artwork on gstatic.com; only which pairs exist (and the
date folder of each picture) is stored here, and the keyboard loads a picture when it is shown.
Picture address: https://www.gstatic.com/android/keyboard/emojikitchen/DATE/uLEFT/uLEFT_uRIGHT.png
where LEFT / RIGHT are the code points in hex joined with "-u".

Binary format (big-endian, read by EmojiKitchen.kt):
  "EKIT", u16 version (1)
  u8 dates, then each date as 8 ASCII digits
  u16 emoji (most popular first, Gboard's order), then each as u8 length + ASCII code points ("1f602")
  u32 pairs, then each as u16 left, u16 right, u8 date
"""
import json
import os
import struct
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
OUT = os.path.join(ROOT, 'app/src/main/assets/emoji_kitchen.dat')


def main(path):
    d = json.load(open(path, encoding='utf-8'))['data']
    emoji = sorted(d.keys(), key=lambda k: d[k].get('gBoardOrder', 1 << 20))
    index = {e: i for i, e in enumerate(emoji)}
    pairs = {}
    dates = {}
    for e in emoji:
        for items in d[e]['combinations'].values():
            for c in items:
                if not c.get('isLatest'):
                    continue
                l, r = c['leftEmojiCodepoint'], c['rightEmojiCodepoint']
                if l not in index or r not in index:
                    continue
                key = (index[l], index[r])
                if key in pairs or (key[1], key[0]) in pairs:
                    continue
                pairs[key] = dates.setdefault(c['date'], len(dates))
    date_list = sorted(dates, key=dates.get)
    with open(OUT, 'wb') as f:
        f.write(b'EKIT' + struct.pack('>H', 1))
        f.write(struct.pack('>B', len(date_list)))
        for dt in date_list:
            f.write(dt.encode('ascii'))
        f.write(struct.pack('>H', len(emoji)))
        for e in emoji:
            b = e.encode('ascii')
            f.write(struct.pack('>B', len(b)) + b)
        f.write(struct.pack('>I', len(pairs)))
        for (l, r), di in pairs.items():
            f.write(struct.pack('>HHB', l, r, di))
    print(f'{len(emoji)} emoji, {len(pairs)} pairs, {len(date_list)} dates, {os.path.getsize(OUT) // 1024} KB -> {OUT}')


if __name__ == '__main__':
    main(sys.argv[1])
