#!/usr/bin/env python3
"""Extract the 仁爱 textbook IPA into `scripts/data/renai-ipa.tsv`.

One-off extraction tool (not part of the asset pipeline): it reads the OCR'd
text of the 仁爱版英语 七上 / 七下 / 八上 unit word lists (one `.txt` per scanned
page) and writes the committed `scripts/data/renai-ipa.tsv`
(`headword<TAB>us<TAB>uk`), which `scripts/build-lexicon.py` merges into
`dict/lexicon-en.json`. The tsv ships in the repository so regenerating the
lexicon never depends on the OCR directory (docs/WORDLIST.md §6/§9).

Source shape — the textbook prints one IPA per entry, or two when the accents
differ, British first (`/dɑːns/; /dæns/`, `hot /hɒt; hɑt/`):

    friend /freɪnd/ n. 朋友 (1)
    hot /hɒt; hɑt/ adj. 热的
    mum /mʌm/ (mom /mɒm/; /mɑːm/ AmE)

Rules (each one exists because a real page needs it):

- Every `/…/` group is an entry; a group separated from the previous one by
  nothing but `;` is that entry's second (US) reading; a group with its own
  headword — a parenthesised variant like `mom`, a plural like `pl. men`, or a
  second entry packed onto the same OCR line — starts its own entry. A head is
  the text between the previous group and this one, cut at the last 汉字 and
  stripped of the OCR's leading junk (`(85)`, `*`, `pl.`, a POS token); a
  trailing `(to)` — `according (to)` — is dropped, the list headword is
  `according`.
- One group may carry both accents, printed `;`-separated inside the slashes
  (`clothes /kləʊdz; kləʊz/`, `hot /hɒt; hɑt/`): the group is split on `;`
  first, and the valid parts become that head's readings in printed order —
  British first, so `uk = readings[0]`, `us = readings[1]`.
- One printed reading means the textbook considers both accents the same
  (`mall /mɔːl/`): `us` and `uk` are written identically.
- A reading whose symbols leave the IPA repertoire (the appendix page prints
  pre-2015 notation: `hi:`, `'veri`) or a group whose head cannot be read (a
  continuation line, `/ˌɪnˈlænd/ adv. …`) is dropped on its own — one mangled
  reading must not cost the valid readings sharing its group, nor the valid
  entries sharing its OCR line. The word then falls back to ipa-dict in
  `build-lexicon.py`.
- `**` marks OCR contamination of a second reading (`artist /ˈɑːtɪst/;
  **ˈɑːtɪst/`); an unterminated `/` does the same for `be /biː/; biː/`. Both
  drop that reading, keeping the group that parsed.
- Order is the textbook's (七上 → 七下 → 八上, page order). A headword with two
  spellings in the book keeps a row each (`people`); the lexicon takes the
  first.

Usage:
    python3 scripts/extract-renai-ipa.py [--source ~/Downloads] [--out …]
"""

from __future__ import annotations

import argparse
import collections
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = REPO_ROOT / "scripts" / "data" / "renai-ipa.tsv"
DEFAULT_SOURCE = Path.home() / "Downloads"
BOOK_DIRS = ("仁爱版英语七上", "仁爱版英语七下", "仁爱版英语八上")
PAGE_FILE_RE = re.compile(r"^\d+\.txt$")

CJK_RE = re.compile(r"[\u4e00-\u9fff]")
GROUP_RE = re.compile(r"/([^/]{1,40})/")
# The IPA repertoire the textbook uses (docs/WORDLIST.md §9).
IPA_CHARS = set("abcdefghijklmnopqrstuvwxyzɪʊʌɒɔəæɑɜɛʃʒθðŋɡɹɫˈˌː() -")
# `word`, `hard-working`, `a.m.`, `ice cream` — what a headword looks like.
HEAD_RE = re.compile(r"^[A-Za-z][A-Za-z'’\-]*(?:[ .][A-Za-z'’\-]+)*\.?$")
# OCR junk in front of a head: `(85)`, `pl.`, `adj.`, `*`, a stray bracket.
PAGE_MARK_RE = re.compile(r"^\s*(?:\(\s*\d+\s*\)\s*)+")
LEADING_JUNK_RE = re.compile(r"^[\s(;*†~`]+")
POS_PREFIX_RE = re.compile(r"^(?:(?:[a-z]{1,3}\.|pl|AmE|BrE)(?:[\s,.]+|$))+")
LABEL_SUFFIX_RE = re.compile(r"\s*\([^)]*\)\s*$")
SEPARATOR_RE = re.compile(r"\s*;?\s*")


def head_before(before: str) -> str:
    """The headword printed between two IPA groups ("" when there is none)."""
    index = max((i for i, c in enumerate(before) if CJK_RE.match(c)), default=-1)
    text = before[index + 1 :]
    text = PAGE_MARK_RE.sub("", text)
    text = LEADING_JUNK_RE.sub("", text)
    text = POS_PREFIX_RE.sub("", text)
    text = LABEL_SUFFIX_RE.sub("", text)  # `according (to)` → `according`
    return re.sub(r"\s+", " ", text.rstrip(" ,()"))


def parse_line(line: str) -> list[tuple[str, list[str]]]:
    """`(head, [readings])` entries of one OCR line, damaged readings dropped."""
    entries: list[list] = []
    previous_end = 0
    for index, match in enumerate(GROUP_RE.finditer(line)):
        before = line[previous_end : match.start()]
        previous_end = match.end()
        # One group may print both accents — `clothes /kləʊdz; kləʊz/` — so
        # split on `;` *before* validating: `;` is not in IPA_CHARS, and
        # checking the whole group first rejected the very words whose two
        # accents differ most. Each part is validated on its own, so a mangled
        # part costs only its own reading.
        readings = [
            part for part in (p.strip() for p in match.group(1).split(";"))
            if part and not set(part) - IPA_CHARS
        ]
        if not readings:
            continue
        if index > 0 and entries and SEPARATOR_RE.fullmatch(before):
            entries[-1][1].extend(readings)
            continue
        head = head_before(before)
        if not HEAD_RE.match(head):
            continue
        entries.append([head, readings])
    return [(head, readings) for head, readings in entries]


def page_files(source: Path) -> list[Path]:
    files: list[Path] = []
    for book in BOOK_DIRS:
        directory = source / book
        if not directory.is_dir():
            print(f"error  missing source directory: {directory}", file=sys.stderr)
            sys.exit(1)
        files.extend(sorted(p for p in directory.glob("*.txt") if PAGE_FILE_RE.match(p.name)))
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="directory holding the 仁爱版英语* OCR folders")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="tsv to write")
    args = parser.parse_args()

    files = page_files(args.source)
    rows: list[tuple[str, str, str]] = []
    with_ipa = 0
    dropped_lines: list[str] = []
    for path in files:
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            if not GROUP_RE.search(line):
                continue
            with_ipa += 1
            entries = parse_line(line)
            if not entries:
                dropped_lines.append(line)
            for head, readings in entries:
                if len(readings) > 2:
                    # `a; b; c` — an OCR run-on; the first two are the accents.
                    readings = readings[:2]
                uk = readings[0]
                us = readings[1] if len(readings) > 1 else readings[0]
                rows.append((head, us, uk))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        "".join(f"{head}\t{us}\t{uk}\n" for head, us, uk in rows),
        encoding="utf-8",
    )
    heads = collections.Counter(head.lower() for head, _, _ in rows)
    print(f"pages parsed:   {len(files)}")
    print(f"lines with IPA: {with_ipa}")
    print(f"rows written:   {len(rows)} ({len(heads)} headwords, {sum(1 for c in heads.values() if c > 1)} repeated)")
    print(f"lines dropped:  {len(dropped_lines)} (no readable headword / no valid symbol)")
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
