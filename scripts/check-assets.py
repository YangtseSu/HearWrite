#!/usr/bin/env python3
"""Validate the bundled word-list library under `app/src/main/assets/`.

The library ships verbatim into the APK and is the app's only source of truth
for word lists, so a broken row is shipped to every user at once. This script
is the machine check behind `docs/WORDLIST.md` — it runs in CI on every push
and must stay dependency-free (stdlib only, no network).

Two severities:

- **error** — the row/structure violates the contract (the app would mis-read
  or mis-speak it). Exit code 1.
- **warning** — quality smell the app tolerates (junk punctuation in a
  headword, a slash/ellipsis row a TTS reads oddly). Printed as a summary;
  exit code 1 only with `--strict`.

Usage:
    python3 scripts/check-assets.py [--strict] [--verbose]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets"

# Top-level asset dirs that are not word-list categories (AGENTS.md
# "Built-in library").
NON_LIBRARY_DIRS = {"dict", "compounds", "audio"}

PIPE_RE = re.compile(r"[|｜]")
CJK_RE = re.compile(r"[\u4e00-\u9fff]")
LATIN_RE = re.compile(r"[A-Za-z]")
# Tone-marked / toneless 拼音: a–z plus ü and its four tone-marked forms.
PINYIN_RE = re.compile(r"[a-züǖǘǚǜāáǎàēéěèīíǐìōóǒòūúǔù]+")

# The 词性 column is NOT validated against a closed vocabulary: the sources
# mix ECDICT spellings ("n.") with textbook compounds ("n. & v.", "v.aux.",
# "pl, n") that are legitimate. `normalizePos` leaves unknown input as-is and
# nothing speaks the column, so policing it would only produce noise.

# Headword shapes a TTS reads badly. Tolerated (warning) — the lists carry
# them verbatim from their sources.
JUNK_HEAD_CHARS = "*#^~`"
SLASH_OR_ELLIPSIS_RE = re.compile(r"/|\.\.\.|…")
LEADING_PAREN_RE = re.compile(r"^\(")
TRAILING_DIGIT_RE = re.compile(r"\d$")


class Report:
    """Collected findings; `error` fails the run, `warning` does not."""

    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, where: str, message: str) -> None:
        self.errors.append(f"{where}: {message}")

    def warn(self, where: str, message: str) -> None:
        self.warnings.append(f"{where}: {message}")


def split_columns(line: str) -> list[str]:
    return [part.strip() for part in PIPE_RE.split(line)]


def check_file(path: Path, label: str, report: Report) -> int:
    """Validate one `.txt` list; returns its row count."""
    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        report.error(label, "starts with a UTF-8 BOM (first headword gets an invisible prefix)")
    if b"\r" in raw:
        report.error(label, "contains CR — the file must use LF line endings")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as e:
        report.error(label, f"not valid UTF-8 ({e})")
        return 0

    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if not lines:
        report.error(label, "file is empty")
        return 0

    seen: dict[str, int] = {}
    rows = 0
    for number, line in enumerate(lines, start=1):
        where = f"{label}:{number}"
        if line.strip() == "":
            report.error(where, "blank line")
            continue
        if line != line.strip():
            report.error(where, f"leading/trailing whitespace in {line!r}")
        rows += 1

        columns = split_columns(line)
        if len(columns) not in (1, 2, 3):
            report.error(
                where,
                f"{len(columns)} columns — expected 1 (bare word), 2 (word | pinyin / pos) "
                f"or 3 (word | pos | meaning): {line!r}",
            )
            continue
        head = columns[0]
        if not head:
            report.error(where, "empty headword")
            continue
        if head in seen:
            report.error(where, f"duplicate headword {head!r} (first at line {seen[head]})")
        else:
            seen[head] = number

        pos = columns[1] if len(columns) >= 2 else None
        meaning = columns[2] if len(columns) == 3 else None
        is_cjk = CJK_RE.search(head) is not None

        if is_cjk and len(head) == 1:
            check_single_char(where, head, pos, meaning, report)
        elif is_cjk:
            if len(columns) != 1:
                report.error(where, f"multi-char Chinese rows are bare words (no 拼音/组词 columns): {line!r}")
            if LATIN_RE.search(head) or re.search(r"\d", head):
                report.warn(where, f"Chinese word mixes Latin/digits: {head!r}")
        else:
            check_english(where, head, meaning, report)

    return rows


def check_single_char(where: str, head: str, pos: str | None, meaning: str | None, report: Report) -> None:
    """A 生字 row: `字 | pinyin | 组词`, `字 | pinyin` (hint only, the shape the
    enrichment emits for a char with no derivable 组词), or a bare char."""
    if pos is None:
        return
    if not PINYIN_RE.fullmatch(pos):
        report.error(where, f"pinyin column {pos!r} is not 拼音 letters (tone marks, no tone digits)")
    if meaning == "":
        report.error(where, "3-column 生字 row with an empty 组词 column — write `字 | 拼音` instead")
    elif meaning is not None and head not in meaning:
        report.error(where, f"组词 {meaning!r} does not contain the head char {head!r} — it would be ignored at playback")


def check_english(where: str, head: str, meaning: str | None, report: Report) -> None:
    if meaning == "":
        report.error(where, "3-column row with an empty meaning — write 1 or 3 columns, not an empty column")
    for char in JUNK_HEAD_CHARS:
        if char in head:
            report.warn(where, f"headword carries {char!r}: {head!r}")
    if SLASH_OR_ELLIPSIS_RE.search(head):
        report.warn(where, f"headword needs a TTS-friendly reading check: {head!r}")
    if LEADING_PAREN_RE.match(head):
        report.warn(where, f"headword starts with a parenthesis: {head!r}")
    if TRAILING_DIGIT_RE.search(head):
        report.warn(where, f"headword ends with a digit: {head!r}")


def check_library(report: Report) -> tuple[int, int]:
    """Validate every category dir / list file; returns (files, rows)."""
    if not ASSETS_DIR.is_dir():
        report.error("assets", f"missing directory {ASSETS_DIR}")
        return 0, 0

    top = sorted(p for p in ASSETS_DIR.iterdir())
    for entry in top:
        if entry.is_file() and entry.name != ".gitkeep":
            report.error("assets", f"stray file at the assets root: {entry.name}")

    categories = [p for p in top if p.is_dir() and p.name not in NON_LIBRARY_DIRS]
    if not categories:
        report.error("assets", "no library categories found")

    files = 0
    rows = 0
    for category in categories:
        lists = sorted(category.glob("*.txt"))
        if not lists:
            report.error(category.name, "category contains no .txt list")
        for path in category.glob("*"):
            if path.is_file() and path.suffix != ".txt":
                report.error(category.name, f"non-.txt file in a category dir: {path.name}")
        for path in lists:
            files += 1
            rows += check_file(path, f"{category.name}/{path.name}", report)
    return files, rows


def check_derived_assets(report: Report) -> None:
    """The generated assets must keep the shape the app parses."""
    check_flat_map(ASSETS_DIR / "dict" / "ecdict-meta.json", report, key_check=None, value_check="pipe")
    check_flat_map(ASSETS_DIR / "dict" / "hanzi-meta.json", report, key_check="cjk1", value_check="hanzi")
    check_compounds(ASSETS_DIR / "compounds" / "compounds.json", report)


def load_json(path: Path, report: Report) -> object | None:
    label = f"{path.parent.name}/{path.name}"
    if not path.is_file():
        report.error(label, "missing generated asset (run the matching scripts/ generator)")
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        report.error(label, f"invalid JSON ({e})")
        return None


def check_flat_map(path: Path, report: Report, key_check: str | None, value_check: str | None) -> None:
    label = f"{path.parent.name}/{path.name}"
    data = load_json(path, report)
    if data is None:
        return
    if not isinstance(data, dict) or not data:
        report.error(label, "expected a non-empty JSON object")
        return
    bad = 0
    for key, value in data.items():
        if not isinstance(value, str):
            bad += 1
            continue
        if key_check == "cjk1" and not (len(key) == 1 and CJK_RE.fullmatch(key)):
            report.error(label, f"key {key!r} is not a single Chinese char")
            return
        if value_check == "pipe" and "|" not in value:
            bad += 1
        if value_check == "hanzi":
            pinyin, _, word = value.partition("|")
            if not PINYIN_RE.fullmatch(pinyin):
                bad += 1
            elif word == "":
                continue  # reading-only entry: the char has no derivable 组词
            # 2–4 chars: the textbook 组词 wins even when it is longer than a
            # 2-char word (红领巾 for 领); 组词朗读 simply falls back to a
            # 2-char pool word for the spoken call.
            elif not 2 <= len(word) <= 4 or not CJK_RE.search(word) or key not in word:
                bad += 1
    if bad:
        report.error(label, f"{bad} malformed entries")


def check_compounds(path: Path, report: Report) -> None:
    label = f"{path.parent.name}/{path.name}"
    data = load_json(path, report)
    if not isinstance(data, dict) or set(data) != {"compounds", "learned"}:
        report.error(label, "expected exactly the `compounds` and `learned` pools")
        return
    for pool in ("compounds", "learned"):
        table = data.get(pool)
        if not isinstance(table, dict):
            report.error(label, f"`{pool}` is not an object")
            continue
        for char, rows in table.items():
            if not (len(char) == 1 and CJK_RE.fullmatch(char)):
                report.error(label, f"`{pool}` key {char!r} is not a single Chinese char")
                break
            if not isinstance(rows, list) or not rows:
                report.error(label, f"`{pool}[{char}]` is not a non-empty list")
                break
            for row in rows:
                if (
                    not isinstance(row, list)
                    or len(row) != 2
                    or not all(isinstance(x, str) for x in row)
                ):
                    report.error(label, f"`{pool}[{char}]` row {row!r} is not [word, syllable]")
                    break
                word, syllable = row
                if len(word) != 2 or char not in word:
                    report.error(label, f"`{pool}[{char}]` word {word!r} must be 2 chars containing {char!r}")
                    break
                if not re.fullmatch(r"[a-zü]+[1-5]?", syllable):
                    report.error(label, f"`{pool}[{char}]` syllable {syllable!r} is not a tone-digit syllable")
                    break


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--strict", action="store_true", help="treat warnings as errors")
    parser.add_argument("--verbose", action="store_true", help="print every warning")
    args = parser.parse_args()

    report = Report()
    files, rows = check_library(report)
    check_derived_assets(report)

    for message in report.errors:
        print(f"error   {message}")
    shown = report.warnings if args.verbose else report.warnings[:20]
    for message in shown:
        print(f"warning {message}")
    if not args.verbose and len(report.warnings) > len(shown):
        print(f"warning … and {len(report.warnings) - len(shown)} more (rerun with --verbose)")

    print(
        f"\n{files} lists, {rows} rows — "
        f"{len(report.errors)} errors, {len(report.warnings)} warnings"
    )
    if report.errors or (args.strict and report.warnings):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
