"""Build app/src/main/assets/dict/tl.tsv, the Filipino (Tagalog) word list.

usage: python -I tools/dict/build_dict_tl.py tl_full.txt tgl_sentences.tsv.bz2 tgl_wikipedia_2021_100K-words.txt

No single free Tagalog source is big enough, so three are combined (each counted per million
words, then weighted):
- hermitdave/FrequencyWords content/2018/tl/tl_full.txt (OpenSubtitles 2018), CC BY-SA 4.0
  (attribution: Hermit Dave): everyday spoken Tagalog, but only ~10,000 words.
- Tatoeba Project Tagalog sentences (https://downloads.tatoeba.org/exports/per_language/tgl/
  tgl_sentences.tsv.bz2), CC BY 2.0 FR: conversational sentences.
- Leipzig Corpora Collection tgl_wikipedia_2021_100K (https://wortschatz.uni-leipzig.de),
  CC BY 4.0: breadth of vocabulary, weighted lower (encyclopaedic, with Spanish and English).
The output is an adaptation under CC BY-SA 4.0.

Hyphenated words are counted as their parts ("mag-aral" -> "mag", "aral"): the keyboard ends a
word at a hyphen, so the parts must be known words (else "mag" would be corrected to "may").
Names (capitalised mid-sentence in most uses) are dropped, except days, months and a few words
that are always written with a capital. Counts are scaled so the most common word matches the
English list's, so the corrector's thresholds mean the same in both languages.

Output: "word<TAB>count" lines in alphabetical order of the lowercase form.
"""
import bz2
import collections
import math
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
OUT = os.path.join(ROOT, 'app/src/main/assets/dict/tl.tsv')
EN = os.path.join(ROOT, 'app/src/main/assets/dict/en.tsv')

TOKEN = re.compile(r"[A-Za-zÑñ]+(?:'[A-Za-zÑñ]+)?")
WORD = re.compile(r"^[a-zñ]+(?:'[a-zñ]+)?$")
SENTENCE_END = re.compile(r'[.!?…:;"“”«»()\[\]]+')
WEIGHTS = {'subs': 1.0, 'tatoeba': 1.0, 'wiki': 0.5}
MAX_WORDS = 40_000
NAME_SHARE = 0.8
SINGLE_LETTERS = {'o', 'e'}
# Words written with a capital even mid-sentence.
ALWAYS_CAPITAL = {
    'lunes', 'martes', 'miyerkules', 'huwebes', 'biyernes', 'sabado',
    'enero', 'pebrero', 'marso', 'abril', 'mayo', 'hunyo', 'hulyo', 'agosto', 'setyembre',
    'oktubre', 'nobyembre', 'disyembre',
    'pilipinas', 'pilipino', 'filipino', 'tagalog', 'ingles', 'diyos', 'panginoon', 'pasko',
}
# Lowercase words that the name test would drop ("Linggo" is also Sunday; "linggo" is week).
NOT_NAMES = {'linggo', 'po', 'opo'}
# Spanish function words common in Wikipedia's place and people names, not in Tagalog typing.
SPANISH = set('de del la las los el y en por con que san santa'.split())
ROMAN = re.compile(r'^(?=[ivxlc]{2,}$)m*(c[md]|d?c{0,3})(x[cl]|l?x{0,3})(i[xv]|v?i{0,3})$')


def parts(token):
    return token.replace('’', "'").split('-')


def main(subs_path, tatoeba_path, wiki_path):
    counts = {k: collections.Counter() for k in WEIGHTS}
    mid, capital = collections.Counter(), collections.Counter()

    for line in open(subs_path, encoding='utf-8'):
        p = line.split()
        if len(p) == 2:
            for w in p[0].split('-'):
                counts['subs'][w.lower()] += int(p[1])

    with bz2.open(tatoeba_path, 'rt', encoding='utf-8') as f:
        for line in f:
            cols = line.rstrip('\n').split('\t')
            if len(cols) < 3:
                continue
            # One line can hold several sentences ("Okay. Sige."): each starts with a capital.
            for sentence in SENTENCE_END.split(cols[2].replace('’', "'")):
                for i, t in enumerate(TOKEN.findall(sentence)):
                    lw = t.lower()
                    counts['tatoeba'][lw] += 1
                    if i > 0:
                        mid[lw] += 1
                        if t[0].isupper():
                            capital[lw] += 1

    wiki_cased = collections.Counter()
    for line in open(wiki_path, encoding='utf-8'):
        cols = line.rstrip('\n').split('\t')
        if len(cols) < 3 or not cols[2].isdigit():
            continue
        n = int(cols[2])
        for t in cols[1].split('-'):
            if TOKEN.fullmatch(t):
                counts['wiki'][t.lower()] += n
                if t[0].isupper():
                    wiki_cased[t.lower()] += n

    totals = {k: sum(c.values()) or 1 for k, c in counts.items()}
    words = set().union(*counts.values())

    def is_name(w):
        if w in NOT_NAMES or w in ALWAYS_CAPITAL:
            return False
        if mid[w] >= 3:
            return capital[w] >= NAME_SHARE * mid[w]
        # Wikipedia counts include sentence starts, so require a stronger majority.
        return counts['wiki'][w] >= 3 and wiki_cased[w] >= 0.95 * counts['wiki'][w] and counts['subs'][w] == 0

    scored = {}
    for w in words:
        if not WORD.match(w) or w in SPANISH or ROMAN.match(w):
            continue
        if len(w) == 1 and w not in SINGLE_LETTERS:
            continue
        s, t, k = counts['subs'][w], counts['tatoeba'][w], counts['wiki'][w]
        if not (s >= 2 or t >= 2 or k >= 6 or (s and t)):
            continue
        if is_name(w):
            continue
        ppm = sum(WEIGHTS[src] * counts[src][w] * 1e6 / totals[src] for src in WEIGHTS) / sum(WEIGHTS.values())
        scored[w] = ppm

    top = sorted(scored, key=scored.get, reverse=True)[:MAX_WORDS]
    en_top = max(int(l.split('\t')[1]) for l in open(EN, encoding='utf-8') if not l.startswith('#'))
    scale = en_top / scored[top[0]]
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Filipino (Tagalog) word frequencies: adapted from hermitdave/FrequencyWords tl (OpenSubtitles 2018, '
                'CC BY-SA 4.0), Tatoeba (CC BY 2.0 FR) and Leipzig tgl_wikipedia_2021 (CC BY 4.0); CC BY-SA 4.0. '
                'Built by tools/dict/build_dict_tl.py\n')
        for w in sorted(top):
            shown = w.capitalize() if w in ALWAYS_CAPITAL else w
            f.write(f'{shown}\t{max(1, math.ceil(scored[w] * scale))}\n')
    print(f'{len(top)} words; top: {" ".join(top[:30])}')


if __name__ == '__main__':
    main(*sys.argv[1:4])
