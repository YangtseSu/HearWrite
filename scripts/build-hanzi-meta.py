#!/usr/bin/env python3
"""Build app/src/main/assets/dict/hanzi-meta.json — per-char default reading
and compound for bare Chinese characters.

A bare char row (`月`, no pinyin/组词 columns) is a first-class input: pasted
char lists, OCR results (the Chinese extractor keeps 汉字 only, AGENTS.md) and
the 课标 字表 all arrive as one char per line. Without this asset the dictation
dial can only show that one character — no pinyin hint, no reading anchor for
组词朗读. This script derives both from data already in the repo, exactly the
way `scripts/build-ecdict-meta.py` derives English meta for bare English words.

Run: python3 scripts/build-hanzi-meta.py

Output: app/src/main/assets/dict/hanzi-meta.json
    {"月": "yuè|岁月", "行": "xíng|进行", …}
  The same flat `key → "col2|col3"` shape as ecdict-meta.json, so the app
  decodes both with one code path (DictionaryRepository). pinyin is stored
  tone-marked (rows carry `yuè`, never `yue4`).

Reading/compound precedence (first hit wins), per char:

1. `scripts/data/hanzi-meta-overrides.tsv` — hand-curated exceptions (轻声
   虚词 the table ranks by their literary reading: 的 dì, 着 zhuó, 了 liǎo).
2. Textbook rows: every built-in list row shaped `字 | pinyin | 组词`. The
   textbook's own reading and compound are authoritative for the classroom
   (same precedence `cjkWordSpeech` gives an entry's own meaning column).
3. scripts/data/xiandaihanyuchangyongcibiao.txt — 《现代汉语常用词表（草案）》
   (56008 words + per-reading single-char entries, each with a frequency
   level; smaller = more common), the table the compounds generator consumes.

Rule 3, in detail:

- **Reading**: the char's syllable in its most common **tone-bearing**
  context of any word length. The table's single-char entries are per-reading
  frequency rows (吓 he4 3896 vs 吓 xia4 8339), so a char's own entry usually
  wins outright; a tone-bearing context beats a 轻声 one because the 轻声
  syllable of a compound says nothing about the char's own reading (份 fèn
  from 份, not the 轻声 of 月份). Only a char with no tone-bearing context at
  all keeps a toneless reading — the true 轻声 chars (们/么/啦).
- **组词**: the most common **2-char** word whose syllable for that char
  equals the chosen reading — the pairing must agree, or `cjkWordSpeech`'s
  tier 1 (the entry's own 释义 column, unfiltered) would speak a compound
  with a different reading of the head char. No matching 2-char word → a
  reading-only entry (`"hěn|"`), and the app shows the 拼音 hint alone.

Chars with no evidence at all get no entry and stay bare in the app; nothing
is invented. Key order: pinyin reading, then code point — deterministic, so a
regeneration is a byte-identical no-op unless a source changed.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = Path(__file__).resolve().parent
FREQ_FILE = SCRIPTS_DIR / "data" / "xiandaihanyuchangyongcibiao.txt"
OVERRIDE_FILE = SCRIPTS_DIR / "data" / "hanzi-meta-overrides.tsv"
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets"
OUTPUT_FILE = ASSETS_DIR / "dict" / "hanzi-meta.json"

# Asset dirs that are not word-list categories (AGENTS.md "Built-in library").
NON_LIBRARY_DIRS = {"dict", "compounds", "audio"}

CJK_RE = re.compile(r"^[\u4e00-\u9fff]+$")
CJK2_RE = re.compile(r"^[\u4e00-\u9fff]{2}$")
PIPE_RE = re.compile(r"[|｜]")

# Tone-marked vowels by base vowel, index = tone - 1 (tone 5 = neutral = plain).
TONE_MARKS: dict[str, str] = {
    "a": "āáǎà",
    "o": "ōóǒò",
    "e": "ēéěè",
    "i": "īíǐì",
    "u": "ūúǔù",
    "ü": "ǖǘǚǜ",
}
MARKED_CHARS = frozenset("".join(TONE_MARKS.values()))

# Two accepted pinyin spellings: tone digits (`yue4`, the frequency table) and
# tone marks (`yuè`, every word-list row — the shape the asset stores).
SYLLABLE_RE = re.compile(r"^([a-zü]+)([1-5])?$")
MARKED_SYLLABLE_RE = re.compile(r"^[a-zü" + "".join(sorted(MARKED_CHARS)) + r"]+$")

# Mark-placement vowels, in the standard 汉语拼音 priority: a > o > e, else the
# last vowel (covers iu → iū where the mark sits on the u, and ui → uī on the i).
MARK_ORDER = "aoe"


def parse_freq_table(text: str) -> list[tuple[str, str, int]]:
    """Parse "word\\tpinyin\\tlevel" lines into (word, pinyin, level).

    Every CJK entry is kept regardless of length: the table lists both words
    and **single chars per reading** (吓 he4 3896 / 吓 xia4 8339), and those
    single-char rows are the best available evidence of a char's own reading.
    Rows whose pinyin does not split into one syllable per char are skipped —
    a mismatch means the row cannot be indexed by char position.
    """
    out: list[tuple[str, str, int]] = []
    for line in text.split("\n"):
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        word, pinyin, level = parts[0], parts[1], parts[2]
        if not word or not pinyin or not level:
            continue
        if not CJK_RE.fullmatch(word):
            continue
        if len(pinyin.split("'")) != len(word):
            continue
        try:
            out.append((word, pinyin, int(level)))
        except ValueError:
            continue
    return out


def syllable_at(pinyin: str, index: int) -> str:
    """Syllable of the char at [index] inside a word's pinyin ("sui4'yue4" → "yue4")."""
    parts = pinyin.split("'")
    return parts[index] if index < len(parts) else ""


def is_toned(syllable: str) -> bool:
    """True when the spelling carries a tone — tone digit 1–4 or a tone mark."""
    match = SYLLABLE_RE.match(syllable)
    if match:
        return bool(match.group(2) and match.group(2) != "5")
    return any(char in MARKED_CHARS for char in syllable)


def normalize(syllable: str) -> str | None:
    """Any accepted spelling → tone-marked pinyin ("hao3" → "hǎo"; "hǎo" → "hǎo").

    A digit-less syllable is neutral and stays unmarked. Returns None when the
    input is not a syllable in either spelling.
    """
    match = SYLLABLE_RE.match(syllable)
    if not match:
        return syllable if MARKED_SYLLABLE_RE.match(syllable) else None
    base = match.group(1).replace("v", "ü")
    digit = match.group(2)
    if digit is None or digit == "5":
        return base

    tone = int(digit)
    # a > o > e, else the last vowel (covers iu → iū and ui → uī).
    target = next((v for v in MARK_ORDER if v in base), None)
    if target is None:
        vowels = [c for c in base if c in TONE_MARKS]
        if not vowels:
            return None
        target = vowels[-1]
    index = base.rindex(target)
    return base[:index] + TONE_MARKS[target][tone - 1] + base[index + 1 :]


def load_frequency_candidates() -> dict[str, list[tuple[str, str]]]:
    """char → [(reading, word)] from the frequency table, commonness-ordered.

    Every entry containing the char contributes one candidate: the char's
    syllable in that entry. Two-char words come first (all of them, ordered by
    level), then the table's single-char rows (see [parse_freq_table]) — a
    char's reading is best evidenced by a real word it appears in, and only
    falls back to its own row when no word of the table contains it (很/您/刘).
    """
    entries = sorted(parse_freq_table(FREQ_FILE.read_text(encoding="utf-8")), key=lambda e: e[2])
    candidates: dict[str, list[tuple[str, str]]] = {}
    for words_only in (True, False):
        for word, pinyin, _level in entries:
            if words_only and len(word) != 2:
                continue
            for index, char in enumerate(word):
                candidates.setdefault(char, []).append((syllable_at(pinyin, index), word))
    return candidates


def pick_reading(candidates: list[tuple[str, str]]) -> str | None:
    """The char's reading: its most common tone-bearing candidate, else toneless.

    [candidates] is evidence-ordered (words first, then the char's own rows;
    each block commonness-ordered), so the first tone-bearing row wins. A 轻声
    syllable of some compound says nothing about the char's own reading
    (份 fèn, not the 轻声 of 月份), so toneless rows only answer for chars with
    no tone-bearing context at all (们/么/啦).
    """
    readings = [reading for reading, _word in candidates]
    best = next((reading for reading in readings if is_toned(reading)), None)
    if best is None and not readings:
        return None
    return normalize(best if best is not None else readings[0])


def pick_compound(candidates: list[tuple[str, str]], reading: str) -> str:
    """The most common 2-char word reading the head char as [reading].

    The two halves of an entry must agree: `cjkWordSpeech` speaks an entry's
    own 组词 column without a reading filter (the textbook gloss is
    authoritative), so a compound carrying a *different* reading of the head
    char would be spoken as-is under this entry's 拼音 hint. No such word →
    "" and the entry carries the reading alone (`很 | hěn`).
    """
    for syllable, word in candidates:
        if len(word) == 2 and normalize(syllable) == reading:
            return word
    return ""


def load_textbook_rows() -> dict[str, list[tuple[str, str]]]:
    """char → [(pinyin, 组词)] from every built-in `字 | 拼音 | 组词` list row.

    Files scan in sorted order so the result is deterministic; a char may
    appear in several lists (and with several readings — 得 dé/de), which the
    tone-bearing rule then resolves.
    """
    rows: dict[str, list[tuple[str, str]]] = {}
    if not ASSETS_DIR.is_dir():
        return rows
    for path in sorted(ASSETS_DIR.rglob("*.txt")):
        relative = path.relative_to(ASSETS_DIR)
        if relative.parts[0] in NON_LIBRARY_DIRS:
            continue
        for line in path.read_text(encoding="utf-8").split("\n"):
            columns = [c.strip() for c in PIPE_RE.split(line)]
            if len(columns) != 3:
                continue
            char, pinyin, compound = columns
            if len(char) != 1 or not normalize(pinyin):
                continue
            if not compound or char not in compound:
                continue
            rows.setdefault(char, []).append((pinyin, compound))
    return rows


def load_overrides() -> dict[str, tuple[str, str]]:
    """char → (tone-marked pinyin, 组词) from the hand-curated override table."""
    overrides: dict[str, tuple[str, str]] = {}
    if not OVERRIDE_FILE.is_file():
        return overrides
    for number, line in enumerate(OVERRIDE_FILE.read_text(encoding="utf-8").split("\n"), 1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            raise SystemExit(f"{OVERRIDE_FILE}:{number}: expected char<TAB>pinyin<TAB>compound")
        char, pinyin, compound = (p.strip() for p in parts[:3])
        overrides[char] = (pinyin, compound)
    return overrides


def pick(rows: list[tuple[str, str]]) -> tuple[str, str] | None:
    """First row whose reading carries a tone, else the first row."""
    for row in rows:
        if is_toned(row[0]):
            return row
    return rows[0] if rows else None


def build_meta() -> dict[str, str]:
    overrides = load_overrides()
    textbook = load_textbook_rows()
    candidates = load_frequency_candidates()

    meta: dict[str, str] = {}
    for char, (pinyin, compound) in overrides.items():
        reading = normalize(pinyin)
        if not reading:
            raise SystemExit(f"override for {char!r}: {pinyin!r} is not pinyin")
        meta[char] = f"{reading}|{compound}"

    # Textbook rows win (the classroom reading is authoritative), the frequency
    # table answers for every char no built-in list covers yet.
    for char in sorted(set(textbook) | set(candidates)):
        if char in meta:
            continue
        row = pick(textbook.get(char, []))
        if row is not None:
            reading = normalize(row[0])
            if reading:
                meta[char] = f"{reading}|{row[1]}"
            continue
        reading = pick_reading(candidates[char])
        if reading:
            meta[char] = f"{reading}|{pick_compound(candidates[char], reading)}"
    return meta


def check_consistency(meta: dict[str, str], candidates: dict[str, list[tuple[str, str]]]) -> list[str]:
    """Note where a 2-char compound's own syllable contradicts the entry reading.

    Nearly all hits are the 轻声 cases the textbook marks with a full tone
    (服 fú | 衣服, where 衣服 is yīfu) — the textbook's reading is the answer
    and stays; a handful are noise in the frequency table itself (弹琴 is listed
    as dànqín). Both are known and reviewed, so this only ever reports.
    """
    notes: list[str] = []
    for char, value in sorted(meta.items()):
        reading, _, compound = value.partition("|")
        if len(compound) != 2:
            continue
        syllables = [syllable for syllable, word in candidates.get(char, []) if word == compound]
        if not syllables:
            continue
        if all(normalize(syllable) != reading for syllable in syllables):
            notes.append(f"{char}: {reading}|{compound} — the word reads {syllables[0]}")
    return notes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--notes", action="store_true", help="print the reading/compound notes")
    args = parser.parse_args()

    meta = build_meta()
    notes = check_consistency(meta, load_frequency_candidates())
    if args.notes:
        for note in notes:
            print(f"note {note}")

    def sort_key(char: str) -> tuple[str, int]:
        return meta[char].partition("|")[0], ord(char)

    ordered = {char: meta[char] for char in sorted(meta, key=sort_key)}

    # Compact JSON (no spaces), raw UTF-8, no trailing newline — matches the
    # shipped ecdict-meta.json format.
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_FILE.write_text(
        json.dumps(ordered, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    size_kb = OUTPUT_FILE.stat().st_size / 1024
    print(
        f"wrote {len(ordered)} entries → {OUTPUT_FILE} ({size_kb:.0f} KB)"
        + (f" — {len(notes)} reading/compound notes (--notes)" if notes else "")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
