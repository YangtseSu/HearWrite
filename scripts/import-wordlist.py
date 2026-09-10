#!/usr/bin/env python3
"""Turn a pasted / OCR'd / hand-typed word list into a file this repo can accept.

The app's word-line format is strict (`docs/WORDLIST.md`): one entry per line,
`word | pos | meaning` or a bare word, no blanks, no duplicates. Real sources —
a textbook page, a 微信 paste, an OCR dump, a spreadsheet export — violate it in
predictable ways: full-width pipes, tabs, CRLF, a trailing BOM, bracketed
numbering ("1. apple"), OCR spaces between characters, two spaces where a pipe
belongs.

This script normalizes ONLY what is unambiguously a formatting artifact, then
writes the result to `app/src/main/assets/<category>/<label>.txt` and reports
everything else for a human to decide. It never sorts, never dedupes silently,
never translates 词性, and never invents a pinyin or 组词 column: file order IS
the dictation order and the columns ARE the spoken content.

Run:
    python3 scripts/import-wordlist.py --category 仁爱版初中 --label "七上 Unit 1" \
        --lang en raw.txt
    python3 scripts/import-wordlist.py --category X --label Y --lang zh --dry-run raw.txt

`--lang` states what the source is (en = English words, zh = 汉字 list) and
drives the checks; it does not transform the data. `--dry-run` prints the plan
without writing. The normalizations applied are listed in the report — read it.
"""

from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets"
NON_LIBRARY_DIRS = {"dict", "compounds", "audio"}

CJK_RE = re.compile(r"[\u4e00-\u9fff]")
# Pinyin letters: a–z plus ü and its tone-marked forms.
PINYIN_RE = re.compile(r"[a-züǖǘǚǜāáǎàēéěèīíǐìōóǒòūúǔù]+")



# Tone-digit pinyin ("yue4", "lv3", "hao5") — how transcribers and the
# frequency table write readings. The lists carry tone marks, so an import
# converts; the two spellings are exact inverses of each other.
TONE_DIGIT_RE = re.compile(r"([a-züv]+)([1-5])")
TONE_MARKS = {
    "a": "āáǎà", "o": "ōóǒò", "e": "ēéěè",
    "i": "īíǐì", "u": "ūúǔù", "ü": "ǖǘǚǜ",
}
MARK_ORDER = "aoe"


def marked_syllable(syllable: str) -> str | None:
    """`yue4` → `yuè`, `lv3` → `lǚ`; a digit-5 (轻声) or digit-less syllable
    stays unmarked. None when the input is not a single tone-digit syllable."""
    match = TONE_DIGIT_RE.fullmatch(syllable)
    if not match:
        return None
    base = match.group(1).replace("v", "ü")
    tone = int(match.group(2))
    if tone == 5:
        return base
    target = next((v for v in MARK_ORDER if v in base), None)
    if target is None:
        vowels = [c for c in base if c in TONE_MARKS]
        if not vowels:
            return None
        target = vowels[-1]
    index = base.rindex(target)
    return base[:index] + TONE_MARKS[target][tone - 1] + base[index + 1 :]

# Sources often carry numbering: "1. apple", "1、苹果", "(1) apple", "① apple".
LEADING_INDEX_RE = re.compile(r"^\s*(?:[（(]?\d{1,3}[）).、]|[①-⑳])\s*")
# A 词性 token as it appears in these lists ("n.", "adj.", "n. & v.", "v.aux.").
POS_TOKEN_RE = re.compile(
    r"(?:n|v|vt|vi|adj|adv|prep|conj|pron|num|art|int|interj|aux|abbr|phr|pl|a|prep|excl)\."
    r"(?:\s*[&,]\s*(?:n|v|adj|adv|prep|pron|num|art|int|aux|pl)\.?)*",
    re.IGNORECASE,
)
FIELD_SPLIT_RE = re.compile(r"\t|\s{2,}")


def recover_columns(line: str, lang: str) -> list[str] | None:
    """Recover a row whose pipe separators were lost in a copy/paste.

    Returns the columns only when the shape is unmistakable (`月  yuè  月亮`,
    `apple  n.  苹果`); anything ambiguous is left alone so the human decides.
    """
    wide = [part.strip() for part in FIELD_SPLIT_RE.split(line)]
    if len(wide) == 3 and all(wide):
        head, second, third = wide
        if lang == "zh":
            if len(head) != 1 or not CJK_RE.match(head):
                return None
            # The reading may be written either way (`yuè` or `yue4`); it is
            # converted to tone marks by the caller.
            second = marked_syllable(second) or (second if PINYIN_RE.fullmatch(second) else None)
            if not second or head not in third:
                return None
            return [head, second, third]
        if CJK_RE.search(head) or " " in head:
            return None
        if not POS_TOKEN_RE.fullmatch(second) or not CJK_RE.search(third):
            return None
        return [head, second, third]

    # A single space also separates fields in many sources (an OCR engine or a
    # table export drops the wide gap). Only two shapes are recognized, both
    # from the 生字 row: `字 拼音 组词` and the hint-only `字 拼音` — a Chinese
    # sentence cannot be mistaken for either, because the middle field must be
    # a valid reading and the head a single char.
    if lang != "zh":
        return None
    parts = line.split()
    if len(parts) == 2:
        head, second = parts
        if len(head) != 1 or not CJK_RE.match(head):
            return None
        reading = marked_syllable(second) or (second if PINYIN_RE.fullmatch(second) else None)
        return [head, reading] if reading else None
    if len(parts) == 3:
        head, second, third = parts
        if len(head) != 1 or not CJK_RE.match(head) or head not in third:
            return None
        reading = marked_syllable(second) or (second if PINYIN_RE.fullmatch(second) else None)
        return [head, reading, third] if reading else None
    return None


def normalize_lines(text: str, lang: str) -> tuple[list[str], list[str], list[str]]:
    """Return (lines, notes, warnings) — cleaned rows plus what was changed."""
    notes: list[str] = []
    warnings: list[str] = []
    if text.startswith("\ufeff"):
        notes.append("removed a leading BOM")
        text = text[1:]
    if "\r" in text:
        notes.append("converted CRLF/CR line endings to LF")
        text = text.replace("\r\n", "\n").replace("\r", "\n")

    # Full-width forms and ideographic spaces come from a Chinese IME or a PDF
    # copy; NFKC maps them to ASCII and leaves Han characters untouched. The
    # ideographic space is turned into a tab *first*: NFKC folds it to a plain
    # space, which would erase the field gap that [recover_columns] needs to
    # see (`月　yuè　月亮` would look like a sentence).
    text = text.replace("\u3000", "\t")
    folded = unicodedata.normalize("NFKC", text)
    if folded != text:
        notes.append("normalized full-width characters and ideographic spaces to ASCII")
        text = folded

    out: list[str] = []
    stripped_index = 0
    recovered = 0
    retoned = 0
    no_separator = 0
    for raw in text.split("\n"):
        line = raw.strip()
        if not line:
            continue

        if LEADING_INDEX_RE.match(line):
            line = LEADING_INDEX_RE.sub("", line).strip()
            stripped_index += 1

        if "|" not in line and "｜" not in line:
            columns = recover_columns(line, lang)
            if columns:
                line = " | ".join(columns)
                recovered += 1
            elif FIELD_SPLIT_RE.search(line):
                # Multi-word content with wide gaps: may be a lost separator or
                # may be a phrase. Left as-is, and reported.
                no_separator += 1

        if "|" in line or "｜" in line:
            columns = [part.strip() for part in re.split(r"[|｜]", line)]
            if lang == "zh" and len(columns) >= 2 and len(columns[0]) == 1:
                converted = marked_syllable(columns[1])
                if converted:
                    columns[1] = converted
                    retoned += 1
            line = " | ".join(columns)

        out.append(line)

    if stripped_index:
        notes.append(f"removed list numbering from {stripped_index} lines")
    if recovered:
        notes.append(f"rebuilt the ' | ' separators of {recovered} rows (shape was unmistakable)")
    if retoned:
        notes.append(f"converted {retoned} tone-digit 拼音 columns to tone marks (yue4 → yuè)")
    if no_separator:
        warnings.append(
            f"{no_separator} lines contain wide spaces but no pipe — left unchanged; "
            "check whether a column separator was lost"
        )
    return out, notes, warnings


def check(lines: list[str], lang: str) -> tuple[list[str], list[str]]:
    """Return (errors, warnings) for the cleaned [lines]."""
    errors: list[str] = []
    warnings: list[str] = []
    seen: dict[str, int] = {}
    for number, line in enumerate(lines, start=1):
        where = f"line {number}"
        columns = [part.strip() for part in re.split(r"[|｜]", line)]
        if len(columns) not in (1, 2, 3):
            errors.append(f"{where}: {len(columns)} columns, expected 1, 2 or 3: {line!r}")
            continue
        head = columns[0]
        if not head:
            errors.append(f"{where}: empty headword")
            continue
        if head in seen:
            errors.append(f"{where}: duplicate headword {head!r} (first at line {seen[head]})")
        else:
            seen[head] = number

        is_cjk = CJK_RE.search(head) is not None
        if lang == "en" and is_cjk:
            hint = (
                " (a bare row with Chinese text usually lost its ' | ' separators — "
                "add them and rerun)"
                if len(columns) == 1
                else ""
            )
            errors.append(f"{where}: Chinese headword in an English list: {head!r}{hint}")
        if lang == "zh" and not is_cjk:
            errors.append(f"{where}: non-Chinese headword in a 汉字 list: {head!r}")
        if lang == "zh" and is_cjk and len(head) != 1 and len(columns) != 1:
            errors.append(f"{where}: multi-char Chinese rows are bare words: {line!r}")

        pos = columns[1] if len(columns) >= 2 else None
        meaning = columns[2] if len(columns) == 3 else None
        if len(columns) == 3 and not meaning:
            errors.append(
                f"{where}: empty third column — drop it (`字 | 拼音` for a 生字 with no 组词): {line!r}"
            )
        if len(columns) >= 2 and is_cjk and len(head) == 1 and pos and not PINYIN_RE.fullmatch(pos):
            errors.append(f"{where}: pinyin column {pos!r} is not tone-marked 拼音")
        if len(columns) == 3 and is_cjk and len(head) == 1 and meaning and head not in meaning:
            errors.append(f"{where}: 组词 {meaning!r} does not contain {head!r}")
        if len(columns) == 1 and len(head) > 40:
            warnings.append(f"{where}: very long bare row ({len(head)} chars): {head!r}")
        if re.search(r"[*/\\]", head):
            warnings.append(f"{where}: headword carries a slash/asterisk (TTS reads it oddly): {head!r}")
        if re.search(r"\.\.\.|…", head):
            warnings.append(f"{where}: headword carries an ellipsis (TTS reads it oddly): {head!r}")
    return errors, warnings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", type=Path, help="the raw source file (UTF-8)")
    parser.add_argument("--category", required=True, help="target category dir under app/src/main/assets/")
    parser.add_argument("--label", required=True, help="target list label (the file name without .txt)")
    parser.add_argument("--lang", choices=("en", "zh"), required=True, help="what the source holds")
    parser.add_argument("--dry-run", action="store_true", help="print the plan without writing")
    args = parser.parse_args()

    if args.category in NON_LIBRARY_DIRS:
        print(f"refusing: {args.category!r} is a reserved asset dir (not a library category)")
        return 2
    if "/" in args.label or args.label.startswith("."):
        print(f"refusing: invalid label {args.label!r}")
        return 2

    target = ASSETS_DIR / args.category / f"{args.label}.txt"
    if target.exists():
        print(f"refusing: {target.relative_to(REPO_ROOT)} already exists — labels are stable storage keys,")
        print("          and overwriting one silently changes what students already favorited.")
        return 2

    raw = args.source.read_text(encoding="utf-8", errors="replace")
    lines, notes, cleanup_warnings = normalize_lines(raw, args.lang)
    errors, warnings = check(lines, args.lang)
    warnings = cleanup_warnings + warnings

    print(f"source: {args.source}")
    print(f"target: {target.relative_to(REPO_ROOT)}")
    print(f"rows:   {len(lines)}")
    for note in notes:
        print(f"  cleanup: {note}")
    if not notes:
        print("  cleanup: none needed")
    for error in errors:
        print(f"  error:   {error}")
    for warning in warnings:
        print(f"  warning: {warning}")

    if errors:
        print("\nrefusing to write: fix the errors above (the import tool will not guess).")
        return 1

    body = "\n".join(lines) + "\n"
    if args.dry_run:
        print("\n--dry-run: nothing written. Result would be:\n")
        print(body)
        return 0

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding="utf-8")
    print(f"\nwrote {len(lines)} rows → {target.relative_to(REPO_ROOT)}")
    print("next: python3 scripts/check-assets.py && ./gradlew :app:testDebugUnitTest")
    return 0


if __name__ == "__main__":
    sys.exit(main())
