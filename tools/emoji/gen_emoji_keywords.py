"""Build app/src/main/assets/emoji_keywords.tsv (word -> emoji for the suggestion bar).

usage: python -I tools/emoji/gen_emoji_keywords.py annotations/en.xml annotationsDerived/en.xml \
           app/src/main/assets/emoji.tsv app/src/main/assets/emoji_keywords.tsv
Sources: Unicode CLDR common/annotations/en.xml and common/annotationsDerived/en.xml
(https://github.com/unicode-org/cldr, Unicode License v3).

Each single-word keyword maps to up to 3 emoji: emoji whose name is the word first, then emoji
whose name contains it, then others annotated with it; ties go to Unicode's emoji order (which
puts common smileys first). A short hand-picked list fixes the most-typed words.
Keywords matching more than MAX_EMOJI emoji ("face", "flag") are too generic to suggest and are
dropped, as are skin-tone variants and emoji missing from emoji.tsv.
Output: "word<TAB>emoji emoji emoji" lines, sorted by word.

Filipino: pass annotations/fil.xml and annotationsDerived/fil.xml, the output
app/src/main/assets/emoji_keywords_tl.tsv and `tl` (Tagalog function words and favourites).
"""
import re
import sys
import xml.etree.ElementTree as ET

MAX_EMOJI = 40
# Function words that appear inside emoji names ("sign of the horns") but should never trigger one.
STOP = set('the and for with from that this you your are was were not but its our out off over into '
           'any all can has have had one two his her him she they them what when who how'.split())
TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}
# Filipino function words (and English loans common in the names).
STOP_TL = set('ang ang mga ng nang sa na at ay may mayroong para kay kina ni nina si sina ito iyon '
              'iyan yan yun isang dalawang hindi walang may-ari mukha'.split())
WORD = re.compile(r"^[a-zñ][a-zñ']*$")


# Most-typed words, matched to the emoji people expect first.
OVERRIDES = {
    'love': '❤️ 😍 🥰', 'heart': '❤️ 💕 💖', 'laugh': '😂 🤣 😆', 'lol': '😂 🤣 😆', 'smile': '😊 🙂 😄',
    'happy': '😊 😀 🥳', 'sad': '😢 😞 ☹️', 'cry': '😢 😭 😿', 'car': '🚗 🚙 🏎️', 'cat': '🐱 🐈 😺',
    'dog': '🐶 🐕 🦮', 'kiss': '😘 💋 😗', 'cool': '😎 🆒 🤙', 'thanks': '🙏 😊 🤗', 'yes': '👍 ✅ 👌',
    'ok': '👌 👍 🆗', 'no': '🙅 👎 ❌', 'wow': '😮 🤩 😲', 'angry': '😠 😡 🤬', 'tired': '😴 😩 🥱',
    'cake': '🎂 🍰 🧁', 'music': '🎵 🎶 🎧',
}
OVERRIDES_TL = {
    'mahal': '❤️ 😍 🥰', 'puso': '❤️ 💕 💖', 'tawa': '😂 🤣 😆', 'haha': '😂 🤣 😆', 'ngiti': '😊 🙂 😄',
    'masaya': '😊 😀 🥳', 'malungkot': '😢 😞 ☹️', 'iyak': '😢 😭 😿', 'kotse': '🚗 🚙 🏎️', 'pusa': '🐱 🐈 😺',
    'aso': '🐶 🐕 🦮', 'halik': '😘 💋 😗', 'salamat': '🙏 😊 🤗', 'oo': '👍 ✅ 👌', 'sige': '👍 👌 🆗',
    'hindi': '🙅 👎 ❌', 'galit': '😠 😡 🤬', 'pagod': '😴 😩 🥱', 'antok': '😴 🥱 💤', 'gutom': '😋 🍽️ 🍚',
    'kain': '🍽️ 😋 🍚', 'kanin': '🍚 🍛 🍙', 'cake': '🎂 🍰 🧁', 'kanta': '🎵 🎶 🎤', 'musika': '🎵 🎶 🎧',
    'ulan': '🌧️ ☔ ☂️', 'araw': '☀️ 🌞 🌅', 'gabi': '🌙 🌃 😴', 'bahay': '🏠 🏡 🏘️', 'kape': '☕ 🥤 😋',
}


def strip(s):
    return s.replace('\ufe0f', '')


def main(ann, derived, emoji_tsv, dst, lang='en'):
    stop = STOP_TL if lang == 'tl' else STOP
    overrides = OVERRIDES_TL if lang == 'tl' else OVERRIDES
    known, order = {}, {}
    for line in open(emoji_tsv, encoding='utf-8'):
        if not line.startswith('#'):
            e = line.split('\t')[1]
            known[strip(e)] = e
            order[e] = len(order)
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
    by_word = {}
    for e, kws in keywords.items():
        words = set()
        for k in kws + [names.get(e, '')]:
            words.update(w for w in re.split(r"[\s:,]+", k) if WORD.match(w) and len(w) > 2 and w not in stop)
        for w in words:
            by_word.setdefault(w, []).append(e)

    def rank(w, e):
        name = names.get(e, '')
        level = 0 if name == w else 1 if w in name.split() else 2
        return (level, order.get(e, 1 << 20))

    out = {w: sorted(es, key=lambda e: rank(w, e))[:3] for w, es in by_word.items() if len(es) <= MAX_EMOJI}
    for w, es in overrides.items():
        out[w] = [known.get(strip(e), e) for e in es.split()]
    with open(dst, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Word -> emoji suggestions from Unicode CLDR annotations (Unicode License v3). '
                'Built by tools/emoji/gen_emoji_keywords.py\n')
        for w in sorted(out):
            f.write(f'{w}\t{" ".join(out[w])}\n')
    print(f'{len(out)} keywords for {len({e for es in out.values() for e in es})} emoji')


if __name__ == '__main__':
    main(*sys.argv[1:6])
