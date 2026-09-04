#!/usr/bin/env python3
"""Regenerate app/src/main/assets/compounds/compounds.json — per-character
word-compound data for single-char dictation ("生" → "生活的生").

Python rewrite of the author's scripts/generate-compounds.ts (retired):
same algorithm, sources and syllable rules, stock Python stdlib only.

Run: python3 scripts/generate-compounds.py

Data sources:
1. scripts/data/xiandaihanyuchangyongcibiao.txt — 《现代汉语常用词表（草案）》
   (Ministry of Education; word frequency levels, 56008 words). Frequency
   level = commonness rank (smaller = more common). Used as the fallback
   common-word pool and as the canonical "is a real word" check.
2. app/src/main/assets/人教版小学语文/*.txt — the built-in Chinese char
   lists. The meaning column of each entry is the textbook's own compound
   for the char ("月 | yuè | 月亮" → 月亮); these become the "already
   learned" pool, preferred over the common-word fallback.

Output: app/src/main/assets/compounds/compounds.json
  {"compounds": {char: [[word, syllable-of-char-in-word], …]}, "learned": {…}}
  Frequency-ordered per char (stable: same-level ties keep file order).

Char key order: JSON object order is semantically irrelevant (maps at
runtime), so a no-op regeneration preserves the previous file's key order
byte for byte (historically ICU-zh from the predecessor generator); keys new
to the file sort by pinyin reading (from the char's most common compound),
then code point.

Syllables come from the frequency list (tone digits, ü written as v, light
tone = no digit) and let runtime filter candidates by the headword's own
reading for polyphonic chars (长: 长期 cháng vs 增长 zhǎng).
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
FREQ_FILE = Path(__file__).resolve().parent / "data" / "xiandaihanyuchangyongcibiao.txt"
TEXTBOOK_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "人教版小学语文"
OUTPUT_FILE = REPO_ROOT / "app" / "src" / "main" / "assets" / "compounds" / "compounds.json"

# Entries per char in "compounds" (frequency-ordered).
COMPOUNDS_PER_CHAR = 6

CJK2_RE = re.compile(r"^[\u4e00-\u9fff]{2}$")

EDGE_PUNCT_RE = re.compile(r"^[\s，,、。.：:；;]+|[\s，,、。.：:；;]+$")
PAREN_RE = re.compile(r"[（(][^（）()]*[）)]")


def parse_freq_table(text: str) -> list[tuple[str, str, int]]:
    """Parse "word\\tpinyin\\tlevel" lines; keep 2-char CJK words only."""
    out: list[tuple[str, str, int]] = []
    for line in text.split("\n"):
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        word, pinyin, level = parts[0], parts[1], parts[2]
        if not word or not pinyin or not level:
            continue
        if not CJK2_RE.match(word):
            continue
        try:
            out.append((word, pinyin, int(level)))
        except ValueError:
            continue
    return out


def split_senses(meaning: str) -> list[str]:
    """Split a textbook meaning cell into candidate senses ("月亮；月宫" → ["月亮", "月宫"])."""
    out: list[str] = []
    for raw in re.split(r"[；;]", meaning):
        text = PAREN_RE.sub("", raw)
        text = EDGE_PUNCT_RE.sub("", text).strip()
        if not text:
            continue
        # Meaning cells may concatenate senses with commas; keep only the first
        # chunk (e.g. "天空、大地" → "天空") — shorter reads better aloud.
        text = re.split(r"[，,、]", text, maxsplit=1)[0]
        text = EDGE_PUNCT_RE.sub("", text).strip()
        if text:
            out.append(text)
    return out


def collect_learned() -> dict[str, list[list[str]]]:
    """Parse 人教版小学语文/*.txt ("字 | 拼音 | 释义" lines) into textbook compounds."""
    learned: dict[str, list[list[str]]] = {}
    if not TEXTBOOK_DIR.is_dir():
        return learned

    # Sorted for determinism (ties inside a frequency level keep file order).
    for filename in sorted(TEXTBOOK_DIR.iterdir(), key=lambda p: p.name):
        if not filename.name.endswith(".txt"):
            continue
        content = filename.read_text(encoding="utf-8")
        for line in content.split("\n"):
            parts = [p.strip() for p in line.split("|")]
            if not parts:
                continue
            head = parts[0]
            if len(head) != 1:  # empty or multi-char heads skipped
                continue
            meaning = parts[2] if len(parts) > 2 else ""
            if not meaning:
                continue
            for sense in split_senses(meaning):
                if len(sense) != 2 or head not in sense or sense == head:
                    continue
                learned.setdefault(head, []).append([sense, ""])  # syllable filled later
    return learned


def syllable_at(pinyin: str, index: int) -> str:
    """Syllable of char at index inside a word's pinyin ("sui4'yue4", 1 → "yue4")."""
    parts = pinyin.split("'")
    return parts[index] if index < len(parts) else ""


def main() -> None:
    # Frequency list file is pinyin-ordered; stable sort by level keeps the
    # file's pinyin order for same-level ties.
    freq_words = parse_freq_table(FREQ_FILE.read_text(encoding="utf-8"))
    freq_words.sort(key=lambda w: w[2])
    freq_level = {w[0]: w[2] for w in freq_words}

    # Frequency-ordered index: char → [word, syllable][]
    by_char: dict[str, list[list[str]]] = {}
    for word, pinyin, _level in freq_words:
        for i, ch in enumerate(word):
            lst = by_char.setdefault(ch, [])
            if len(lst) < COMPOUNDS_PER_CHAR:
                lst.append([word, syllable_at(pinyin, i)])

    # Textbook compounds, deduped, filtered to real words, frequency-ordered.
    learned: dict[str, list[list[str]]] = {}
    for head, senses in collect_learned().items():
        seen: set[str] = set()
        lst: list[list[str]] = []
        for sense, _syl in senses:
            if sense in seen:
                continue
            seen.add(sense)
            level = freq_level.get(sense)
            if level is None:
                continue  # not a real word (e.g. "称对方")
            wi = sense.index(head)  # head ∈ sense guaranteed by collect_learned
            pinyin = next((w[1] for w in freq_words if w[0] == sense), "")
            lst.append([sense, syllable_at(pinyin, wi)])
        lst.sort(key=lambda row: freq_level[row[0]])  # stable → input order on ties
        if lst:
            learned[head] = lst

    # ---- ordering -------------------------------------------------------
    def reading(ch: str) -> str:
        """Most common compound's syllable for the char (pinyin sort key)."""
        rows = by_char.get(ch)
        if rows:
            return rows[0][1]
        rows = learned.get(ch)
        return rows[0][1] if rows else ""

    def new_key_sort_key(ch: str) -> tuple[str, int]:
        return reading(ch), ord(ch)

    # Preserve the previous file's char order (byte-stable no-op reruns);
    # chars new to the file append in reading/code-point order.
    previous_order: list[str] = []
    try:
        prev = json.loads(OUTPUT_FILE.read_text(encoding="utf-8"))
        seen_prev: set[str] = set()
        for table in ("compounds", "learned"):
            for ch in prev.get(table, {}):
                if ch not in seen_prev:
                    seen_prev.add(ch)
                    previous_order.append(ch)
    except Exception:
        pass  # missing/corrupt output → fall back to pure sorted order

    known = set(previous_order)
    new_keys = sorted(
        (set(by_char) | set(learned)) - known,
        key=new_key_sort_key,
    )
    ordered = previous_order + new_keys

    compounds_obj = {ch: by_char[ch] for ch in ordered if ch in by_char}
    learned_obj = {ch: learned[ch] for ch in ordered if ch in learned}

    # Compact JSON (no spaces), raw UTF-8, no trailing newline — matches the
    # shipped asset's format.
    OUTPUT_FILE.write_text(
        json.dumps(
            {"compounds": compounds_obj, "learned": learned_obj},
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        encoding="utf-8",
    )
    print(
        f"Generated {OUTPUT_FILE}: {len(by_char)} chars in compounds, "
        f"{len(learned)} chars in learned (from {len(freq_words)} two-char freq words)."
    )


if __name__ == "__main__":
    sys.exit(main())
