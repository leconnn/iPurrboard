"""
Builds app/src/main/assets/dict/bigrams_en.tsv: for each common word, the words most often typed
after it, for next-word predictions (and as context for corrections).

Source: the Tatoeba Project's English sentences (https://tatoeba.org), CC BY 2.0 FR. Download
https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2 and pass its path:

    python tools/dict/build_bigrams.py path/to/eng_sentences.tsv.bz2

Filipino (Tagalog): pass Tatoeba's tgl_sentences.tsv.bz2 and `tl`; it reads dict/tl.tsv and
blocked_tl.txt and writes dict/bigrams_tl.tsv:

    python tools/dict/build_bigrams.py path/to/tgl_sentences.tsv.bz2 tl

Output lines: `prev<TAB>next1 next2 ...` (best first, up to 6), using the keyboard dictionary's
spelling ("I", "don't"). The row for `<s>` lists likely first words of a sentence. Only words in
the keyboard dictionary and not in the blocked-words list are kept, and no proper nouns: Tatoeba's
example sentences use a few names (Tom, Mary, Sami...) far more than real typing does.
"""
import bz2
import collections
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
LANG = sys.argv[2] if len(sys.argv) > 2 else 'en'
LANG_NAME = {'en': 'English', 'tl': 'Tagalog'}[LANG]
DICT = os.path.join(ROOT, f'app/src/main/assets/dict/{LANG}.tsv')
BLOCKED = os.path.join(ROOT, f'app/src/main/assets/dict/blocked_{LANG}.txt')
OUT = os.path.join(ROOT, f'app/src/main/assets/dict/bigrams_{LANG}.tsv')

TOP = 6            # next words kept per word
MIN_PAIR = 3       # a pair must occur at least this often
MIN_PREV = 20      # and the first word this often overall
START = '<s>'

WORD = re.compile(r"[a-zñ]+(?:'[a-zñ]+)?")
CASED_WORD = re.compile(r"[A-Za-zÑñ]+(?:'[A-Za-zÑñ]+)?")
# A word capitalised this often when not starting a sentence is a name.
NAME_SHARE = 0.8
# Bigrams don't cross punctuation that breaks a phrase.
SEGMENTS = re.compile(r"[.,;:!?()\[\]\"“”…–—]+")


def load_dictionary():
    display = {}
    with open(DICT, encoding='utf-8') as f:
        for line in f:
            if not line.strip() or line.startswith('#'):
                continue
            w = line.split('\t', 1)[0]
            display.setdefault(w.lower(), w)
    return display


def load_blocked():
    with open(BLOCKED, encoding='utf-8') as f:
        return {l.strip().lower() for l in f if l.strip() and not l.startswith('#')}


def main(path):
    display = load_dictionary()
    blocked = load_blocked()
    pairs = collections.defaultdict(collections.Counter)
    totals = collections.Counter()
    capitalised = collections.Counter()
    mid_sentence = collections.Counter()
    with bz2.open(path, 'rt', encoding='utf-8') as f:
        for line in f:
            parts = line.rstrip('\n').split('\t')
            if len(parts) < 3:
                continue
            original = parts[2].replace('’', "'")
            for i, w in enumerate(CASED_WORD.findall(original)):
                if i == 0:
                    continue
                lw = w.lower()
                mid_sentence[lw] += 1
                if w[0].isupper():
                    capitalised[lw] += 1
            text = original.lower()
            first = True
            for seg in SEGMENTS.split(text):
                words = [w for w in WORD.findall(seg)]
                if not words:
                    continue
                prev = START if first else None
                first = False
                for w in words:
                    if w not in display or w in blocked:
                        prev = None
                        continue
                    if prev is not None:
                        pairs[prev][w] += 1
                        totals[prev] += 1
                    prev = w
    lines = []
    def proper(w):
        d = display[w]
        if d == 'I' or d.startswith("I'"):
            return False
        return d[0].isupper() or capitalised[w] >= NAME_SHARE * max(1, mid_sentence[w])

    for prev in sorted(pairs):
        if prev != START and proper(prev):
            continue
        if totals[prev] < MIN_PREV:
            continue
        best = [w for w, n in pairs[prev].most_common(TOP * 3) if n >= MIN_PAIR and w != prev and not proper(w)][:TOP]
        if not best:
            continue
        key = prev if prev == START else display[prev]
        lines.append(key + '\t' + ' '.join(display[w] for w in best))
    with open(OUT, 'w', encoding='utf-8', newline='\n') as out:
        out.write(f'# Next words: adapted from the Tatoeba Project (tatoeba.org) {LANG_NAME} sentences, CC BY 2.0 FR. '
                  'Built by tools/dict/build_bigrams.py\n')
        out.write('\n'.join(lines) + '\n')
    print(f'{len(lines)} words, {os.path.getsize(OUT) // 1024} KB -> {OUT}')


if __name__ == '__main__':
    main(sys.argv[1])
