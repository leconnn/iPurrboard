"""Build app/src/main/assets/emoji_search.tsv (the emoji keyboard's search index).

usage: python -I tools/emoji/gen_emoji_search.py annotations/en.xml annotationsDerived/en.xml \
           app/src/main/assets/emoji.tsv app/src/main/assets/emoji_search.tsv
Sources: Unicode CLDR common/annotations/en.xml and common/annotationsDerived/en.xml
(https://github.com/unicode-org/cldr, Unicode License v3).

One line per emoji, in Unicode's order (common smileys first): "emoji<TAB>name<TAB>kw kw kw", the
name and keywords lowercased. Skin-tone variants are left out (the panel's chosen tone applies).

Filipino: pass annotations/fil.xml, annotationsDerived/fil.xml and the output
app/src/main/assets/emoji_search_tl.tsv; the keyboard merges it into the English index when the
Filipino keyboard is on, so either language finds an emoji.
"""
import re
import sys
import xml.etree.ElementTree as ET

TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}


def strip(s):
    return s.replace('️', '')


def main(ann, derived, emoji_tsv, dst):
    known, order = {}, []
    for line in open(emoji_tsv, encoding='utf-8'):
        if line.startswith('#') or not line.strip():
            continue
        cols = line.rstrip('\n').split('\t')
        e = cols[1]
        known[strip(e)] = e
        order.append((e, cols[2].lower() if len(cols) > 2 else ''))
    names, keywords = {}, {}
    for path in (ann, derived):
        for a in ET.parse(path).getroot().iter('annotation'):
            cp = a.get('cp')
            if any(ch in TONES for ch in cp) or strip(cp) not in known:
                continue
            e = known[strip(cp)]
            if a.get('type') == 'tts':
                names[e] = a.text.strip().lower()
            else:
                keywords[e] = [k.strip().lower() for k in a.text.split('|')]
    n = 0
    with open(dst, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Emoji search index from Unicode CLDR annotations (Unicode License v3). '
                'Built by tools/emoji/gen_emoji_search.py\n')
        for e, fallback in order:
            if any(ch in TONES for ch in e):
                continue
            name = names.get(e, fallback)
            words = []
            for k in keywords.get(e, []):
                for w in re.split(r"[\s:,]+", k):
                    if w and w not in words and w not in name.split():
                        words.append(w)
            f.write(f'{e}\t{name}\t{" ".join(words)}\n')
            n += 1
    print(f'{n} emoji')


if __name__ == '__main__':
    main(*sys.argv[1:5])
