"""Build app/src/main/assets/dict/en.tsv from hermitdave FrequencyWords en_50k.txt.

usage: python -I tools/dict/build_dict.py en_50k.txt app/src/main/assets/dict/en.tsv
Source: https://github.com/hermitdave/FrequencyWords (content/2018/en/en_50k.txt), word counts from
OpenSubtitles 2018. The word list content is CC BY-SA 4.0 (attribution: Hermit Dave); the output is
a filtered adaptation under the same licence.

OpenSubtitles tokenisation splits contractions ("don" + "t"), so the split fragments are dropped,
common contractions are added with tiered counts, and apostrophe-less misspellings ("dont", "im")
are removed so the corrector fixes them; real words such as "its", "were" and "well" stay.

Output: "word<TAB>count" lines in alphabetical order of the lowercase form; words that are always
capitalised in English (I, I'm, days, months) keep their capital.
"""
import re
import sys

WORD = re.compile(r"^[a-z]+(?:'[a-z]+)?$")
SINGLE_LETTERS = {'a', 'i'}
ALWAYS_CAPITAL = {
    'i', "i'm", "i've", "i'll", "i'd",
    'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday',
    'january', 'february', 'march', 'april', 'june', 'july', 'august', 'september',
    'october', 'november', 'december',  # "may" stays lowercase: it is mostly the verb
    'english', 'american', 'british', 'christmas', 'easter',
}
# Fragments that only exist because the source split contractions at the apostrophe.
FRAGMENTS = {'don', 'isn', 'doesn', 'didn', 'wouldn', 'couldn', 'shouldn', 'wasn', 'aren', 'haven',
             'hasn', 'hadn', 'weren', 'ain', 'mustn', 'needn', 'll', 've', 're'}
# Contractions with a rough frequency tier (the source has no counts for them).
CONTRACTIONS = {
    1_000_000: "i'm don't it's you're that's can't i'll i've didn't what's let's he's she's isn't "
               "there's doesn't won't we're they're i'd",
    300_000: "wasn't couldn't wouldn't haven't aren't shouldn't you'll we'll you've we've they'll "
             "you'd he'll she'll it'll who's where's here's how's that'll they've hasn't weren't "
             "hadn't he'd she'd we'd they'd",
    50_000: "ain't mustn't needn't o'clock ma'am y'all what'll who'll there'll it'd when's why's "
            "what're who've could've would've should've might've",
}
# Common misspellings that appear in subtitle text often enough to look like words.
MISSPELLINGS = set('''
alot helo hellp teh recieve wierd definately thier becuase tommorow tomorow seperate untill occured
occurence accomodate acheive adress agressive apparantly arguement beleive begining calender
cemetary collegue comming commited concious copywrite curiousity dissapear dissapoint embarass
enviroment existance familar finaly foriegn freind goverment gaurd happend harrass hight
immediatly independant interupt knowlege libary lisence maintainance mispell neccessary noticable
ocasion occassion persue posession prefered publically realy recomend refered relevent remeber
resistence rythm sieze similiar sincerly speach succesful suprise tatoo threshhold truely
tounge unfortunatly vaccum wether wich writting youve thats whats ive didnt doesnt isnt wasnt
couldnt wouldnt shouldnt havent arent werent hasnt hadnt
'''.split())
# Apostrophe-less forms that are real words and must stay in the dictionary.
REAL_WITHOUT_APOSTROPHE = {'its', 'were', 'well', 'lets', 'hell', 'shell', 'wed', 'ill', 'id', 'shed'}


def main(src, dst):
    counts = {}
    for line in open(src, encoding='utf-8'):
        parts = line.split()
        if len(parts) != 2:
            continue
        word, count = parts[0], int(parts[1])
        if not WORD.match(word) or (len(word) == 1 and word not in SINGLE_LETTERS):
            continue
        if word in FRAGMENTS or word in MISSPELLINGS:
            continue
        counts[word] = counts.get(word, 0) + count
    for tier, words in CONTRACTIONS.items():
        for c in words.split():
            counts[c] = max(counts.get(c, 0), tier)
            bare = c.replace("'", '')
            if bare not in REAL_WITHOUT_APOSTROPHE:
                counts.pop(bare, None)
    with open(dst, 'w', encoding='utf-8', newline='\n') as out:
        out.write('# English word frequencies: adapted from hermitdave/FrequencyWords en_50k '
                  '(OpenSubtitles 2018), CC BY-SA 4.0. Built by tools/dict/build_dict.py\n')
        for word in sorted(counts):
            shown = word.capitalize() if word in ALWAYS_CAPITAL else word
            out.write(f'{shown}\t{counts[word]}\n')
    print(f'{len(counts)} words')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
