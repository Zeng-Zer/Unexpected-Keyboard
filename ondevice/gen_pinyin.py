#!/usr/bin/env python3
"""Generate the pinyin dictionary asset from rime-ice (GPL-3.0,
https://github.com/iDvel/rime-ice).

  gen_pinyin.py <src_dir> <out_file>

<src_dir> contains 8105.dict.yaml (characters) and base.dict.yaml (words).
Output format, read by juloo.keyboard2.pinyin.PinyinDict:
  line 1: every syllable, sorted, separated by spaces
  then:   "<syllables separated by spaces>\t<word>\t<weight>"
          sorted by syllable sequence then decreasing weight.
"""
import sys

# Words below this weight are dropped, keeping about half of base.dict.yaml.
MIN_WORD_WEIGHT = 400

def read_rime_dict(path):
    body = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not body:
                body = line == "..."
                continue
            if not line or line.startswith("#"):
                continue
            cols = line.split("\t")
            if len(cols) < 2:
                continue
            weight = int(cols[2]) if len(cols) > 2 and cols[2].isdigit() else 0
            yield cols[0], cols[1].strip().lower(), weight

def main(src, out):
    entries = {}
    def add(word, pinyin, weight):
        syls = tuple(pinyin.split())
        if not syls or not all(s.isalpha() and s.isascii() for s in syls):
            return
        key = (syls, word)
        entries[key] = max(entries.get(key, 0), weight)
    for word, pinyin, weight in read_rime_dict(src + "/8105.dict.yaml"):
        add(word, pinyin, weight)
    for word, pinyin, weight in read_rime_dict(src + "/base.dict.yaml"):
        if weight >= MIN_WORD_WEIGHT:
            add(word, pinyin, weight)
    syllables = sorted({s for (syls, _) in entries for s in syls})
    rows = sorted(entries.items(), key=lambda e: (e[0][0], -e[1]))
    with open(out, "w", encoding="utf-8") as f:
        f.write(" ".join(syllables) + "\n")
        for (syls, word), weight in rows:
            f.write("%s\t%s\t%d\n" % (" ".join(syls), word, weight))
    print("%d entries, %d syllables" % (len(rows), len(syllables)))

main(sys.argv[1], sys.argv[2])
