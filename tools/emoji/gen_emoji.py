"""Build app/src/main/assets/emoji.tsv from Unicode's emoji-test.txt.

usage: python -I tools/emoji/gen_emoji.py emoji-test.txt app/src/main/assets/emoji.tsv
Source: https://unicode.org/Public/emoji/latest/emoji-test.txt (Unicode terms of use).

Output columns: category, emoji, name, skin-tone variants (space-separated, may be empty).
Categories follow the iOS emoji keyboard; Unicode's "Component" group is dropped and
single-tone variants are attached to their base emoji instead of listed on their own.
"""
import re
import sys

CATEGORY = {
    'Smileys & Emotion': 'Smileys & People',
    'People & Body': 'Smileys & People',
    'Animals & Nature': 'Animals & Nature',
    'Food & Drink': 'Food & Drink',
    'Activities': 'Activity',
    'Travel & Places': 'Travel & Places',
    'Objects': 'Objects',
    'Symbols': 'Symbols',
    'Flags': 'Flags',
}
TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}
LINE = re.compile(r'^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*(\S+)\s+E[\d.]+\s+(.*)$')


def key(s):
    """Match toned forms to their base: drop tone modifiers and variation selector 16."""
    return ''.join(ch for ch in s if ch not in TONES and ch != '️')


def main(src, dst):
    group = version = None
    entries = []  # (category, emoji, name)
    for raw in open(src, encoding='utf-8'):
        line = raw.rstrip('\n')
        if line.startswith('# group:'):
            group = CATEGORY.get(line.split(':', 1)[1].strip())
        elif line.startswith('# Version:'):
            version = line.split(':', 1)[1].strip()
        elif group and (m := LINE.match(line)):
            cps = m.group(1).split()
            entries.append((group, ''.join(chr(int(c, 16)) for c in cps), m.group(3)))

    bases, variants = [], {}
    for cat, emoji, name in entries:
        tones = [ch for ch in emoji if ch in TONES]
        if not tones:
            bases.append((cat, emoji, name))
        elif len(set(tones)) == 1:  # one tone throughout (multi-person forms with mixed tones are skipped)
            variants.setdefault(key(emoji), []).append(emoji)

    with open(dst, 'w', encoding='utf-8', newline='\n') as out:
        out.write(f'# Generated from Unicode emoji-test.txt version {version} by tools/emoji/gen_emoji.py\n')
        for cat, emoji, name in bases:
            v = variants.get(key(emoji), [])
            out.write(f'{cat}\t{emoji}\t{name}\t{" ".join(v[:5])}\n')
    print(f'{len(bases)} emoji, {sum(1 for c, e, n in bases if variants.get(key(e)))} with skin tones, version {version}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
