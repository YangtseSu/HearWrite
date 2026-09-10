#!/usr/bin/env python3
"""Cross-check the shipped 课标 字表 against the official scanned PDF.

The MOE PDF (`http://www.moe.gov.cn/.../W020220420582344386456.pdf`) is an
image-only scan: `pdftotext` yields nothing, so character-level OCR is the only
automated reading, and at 300 dpi it misreads a share of cells. What OCR *is*
trustworthy for is the **printed index** next to each character: the scan prints
`index char` cells in pinyin order, so the pairs are checkable against the
shipped transcription.

What this establishes:

- **Order and completeness.** A transcription that dropped or inserted one
  character shifts every later index, so its cells disagree from that point on
  and no long run of consecutive exact matches can exist. The reported longest
  run is the evidence against it.
- **Table boundaries.** 表一's printed #2500 and 表二's #1000 must land on the
  transcription's last characters (做 / 佐).
- **Residual mismatch rate** — an OCR-quality number, not a data number; the
  leftovers are single-cell misreadings of similar glyphs (比 → 止).

Usage: python3 scripts/verify-kebiao-scan.py [--dpi 300] [--verbose]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
PDF = REPO_ROOT / ".cache" / "yuwen-kebiao-2022.pdf"
CACHE = REPO_ROOT / ".cache" / "kb"
ASSETS = REPO_ROOT / "app" / "src" / "main" / "assets" / "义务教育语文课程标准"

NUMBER = re.compile(r"\d{1,4}")
HAN = re.compile(r"[\u4e00-\u9fff]")

# Printed index ranges per table. Both tables number from 1 and run on their own
# pages; the page bounds were found by locating the appendix headings.
TABLES = (
    ("常用字表1 2500.txt", 77, 99),
    ("常用字表2 1000.txt", 100, 109),
)


def load(name: str) -> list[str]:
    text = (ASSETS / name).read_text(encoding="utf-8")
    return [line.strip() for line in text.split("\n") if line.strip()]


def ocr(page: Path, dpi: int) -> str:
    if not page.is_file():
        number = page.stem.split("-")[1]
        subprocess.run(
            ["pdftoppm", "-r", str(dpi), "-f", number, "-l", number, "-png",
             "-singlefile", str(PDF), str(page.with_suffix(""))],
            check=True, capture_output=True,
        )
    return subprocess.run(
        ["tesseract", str(page), "-", "-l", "chi_sim", "--psm", "6"],
        capture_output=True, text=True,
    ).stdout


def cells_on(line: str) -> list[tuple[int, str]]:
    """One printed table row → `[(79, 被), (107, 辨), …]`.

    tesseract keeps each cell's index and character on one line, so the line's
    numbers and chars correspond positionally. They are paired as whole lists
    rather than by regex adjacency: when OCR drops a character, a
    `number char number char` regex glues the orphan number to the next char
    and every later cell of that line shifts by one. Lines whose counts do not
    match are dropped entirely, and a row must carry at least two cells — that
    skips the running header, which holds prose plus the year.
    """
    numbers = [int(n) for n in NUMBER.findall(line)]
    chars = HAN.findall(line)
    if len(numbers) < 2 or len(chars) != len(numbers):
        return []
    return list(zip(numbers, chars))


def check(name: str, first_page: int, last_page: int, dpi: int, verbose: bool) -> None:
    table = load(name)
    cells: list[tuple[int, str]] = []
    for number in range(first_page, last_page + 1):
        text = ocr(CACHE / f"t-{number}.png", dpi)
        for line in text.split("\n"):
            cells += [cell for cell in cells_on(line) if 1 <= cell[0] <= len(table)]

    exact = 0
    run = best_run = 0
    best_run_at = 0
    mismatches: list[tuple[int, str, str]] = []
    for index, char in sorted(cells):
        if table[index - 1] == char:
            exact += 1
            run += 1
            if run > best_run:
                best_run, best_run_at = run, index
        else:
            run = 0
            mismatches.append((index, char, table[index - 1]))

    print(f"== {name} ({len(table)} chars, pages {first_page}–{last_page})")
    print(f"   cells read: {len(cells)}   exact: {exact} ({100 * exact / len(cells):.0f}%)"
          f"   mismatched: {len(mismatches)}")
    print(f"   longest run of consecutive exact cells: {best_run} (ending at #{best_run_at})")

    printed = {index for index, _ in cells}
    for boundary in (1, len(table)):
        match = next((char for index, char in cells if index == boundary), None)
        if match is not None:
            expected = table[boundary - 1]
            verdict = "matches" if match == expected else f"MISMATCH (shipped {expected!r})"
            print(f"   boundary #{boundary}: scan {match!r} {verdict}")
    gaps = [index for index in range(1, len(table) + 1) if index not in printed]
    print(f"   printed indices never read: {len(gaps)}"
          + (f" (first {gaps[:8]})" if gaps else ""))

    if verbose:
        for index, scan, shipped in mismatches:
            print(f"   mismatch #{index}: scan {scan!r} shipped {shipped!r}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dpi", type=int, default=300, help="render dpi for pages not yet cached")
    parser.add_argument("--verbose", action="store_true", help="list every mismatched cell")
    args = parser.parse_args()

    if not PDF.is_file():
        print(f"missing scan: {PDF} — download it first (see docs/WORDLIST.md §7)")
        return 2
    CACHE.mkdir(parents=True, exist_ok=True)
    for name, first_page, last_page in TABLES:
        check(name, first_page, last_page, args.dpi, args.verbose)
    return 0


if __name__ == "__main__":
    sys.exit(main())
