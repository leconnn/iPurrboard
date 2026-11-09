"""Build app/src/main/assets/dict/blocked_en.txt from the LDNOOBW English word list.

usage: python -I tools/dict/build_blocklist.py en app/src/main/assets/dict/blocked_en.txt
Source: https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words (file "en"),
CC BY 4.0 (attribution: Shutterstock, Inc. and contributors). Only single words are kept, since
suggestions are single words; the output is a filtered adaptation under the same licence.

Filipino (Tagalog): LDNOOBW's "fil" list is short, so common Filipino profanity is added:
    python -I tools/dict/build_blocklist.py fil app/src/main/assets/dict/blocked_tl.txt
"""
import os
import re
import sys

WORD = re.compile(r"^[a-zñ]+(?:'[a-zñ]+)?$")
# Added to LDNOOBW "fil": common Filipino profanity and its texting spellings.
EXTRA_TL = set('''
putangina putanginamo putragis punyeta punyetang leche letse gago gaga gagu gagong tarantado
tarantada inutil hinayupak pakyu pakshet pakingshet syet kupal hindot iyot kantutan kinantot
pekpek puke puki titi etits tite bilat jakulin salsal libog malibog bayot tangina tanginamo
tangna ulol ulul bobo tanga engot lintik buwisit bwisit
'''.split())


def main(src, dst):
    words = {w.strip().lower() for w in open(src, encoding='utf-8') if WORD.match(w.strip().lower())}
    tagalog = 'blocked_tl' in dst
    if tagalog:
        words |= EXTRA_TL
    words = sorted(words)
    name = os.path.basename(src)
    extra = ' plus common Filipino profanity' if tagalog else ''
    with open(dst, 'w', encoding='utf-8', newline='\n') as out:
        out.write(f'# Offensive words kept out of suggestions: adapted from LDNOOBW "{name}"{extra} (CC BY 4.0, '
                  'Shutterstock, Inc. and contributors). Built by tools/dict/build_blocklist.py\n')
        out.writelines(w + '\n' for w in words)
    print(f'{len(words)} words')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
