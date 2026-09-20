#!/usr/bin/env python3
"""Unit tests for `scripts/extract-renai-ipa.py`'s `parse_line`.

Locks the group-splitting rule the 仁爱 import depends on. The textbook prints
both accents `;`-separated inside one group (`clothes /kləʊdz; kləʊz/`), and
`;` is not in its IPA repertoire — so validating the group whole, before the
split, silently dropped exactly the readings whose two accents differ most
(review §4.2). Each case below is a real OCR line shape or the extractor's own
documented one.

Run:
    python3 scripts/test_extract_renai_ipa.py
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


def _load():
    """Import `extract-renai-ipa.py` by path — its name is not an identifier."""
    path = Path(__file__).resolve().parent / "extract-renai-ipa.py"
    spec = importlib.util.spec_from_file_location("extract_renai_ipa", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["extract_renai_ipa"] = module
    spec.loader.exec_module(module)
    return module


renai = _load()


class ParseLineTest(unittest.TestCase):
    def test_semicolon_group_keeps_both_readings_in_printed_order(self) -> None:
        # 七上 Unit 4: `clothes /kləʊdz; kləʊz/ n. 衣服; 服装` — British first,
        # then US (§3.1: `uk = readings[0]`, `us = readings[1]`).
        self.assertEqual(
            [("clothes", ["kləʊdz", "kləʊz"])],
            renai.parse_line("clothes /kləʊdz; kləʊz/ n. 衣服; 服装"),
        )

    def test_two_heads_on_one_ocr_line_stay_two_entries(self) -> None:
        self.assertEqual(
            [("hot", ["hɒt"]), ("cold", ["kəʊld"])],
            renai.parse_line("hot /hɒt/ adj. 热的 cold /kəʊld/ adj. 冷的"),
        )

    def test_separator_run_on_groups_still_extend_the_previous_head(self) -> None:
        # 七下: `mum /mʌm/ (mom /mɒm/; /mɑːm/ AmE)` — `mom` starts its own
        # entry, and the `;`-separated second group belongs to it. This is the
        # SEPARATOR_RE path the split must not regress: `before` is `; `, not
        # a headword.
        self.assertEqual(
            [("mum", ["mʌm"]), ("mom", ["mɒm", "mɑːm"])],
            renai.parse_line("mum /mʌm/ (mom /mɒm/; /mɑːm/ AmE)"),
        )

    def test_headless_group_is_dropped_without_losing_its_neighbours(self) -> None:
        # A continuation line — `/ˌɪnˈlænd/ adv. …` — or a truncated `T-shirt /`
        # group carries no head of its own (the text before it is 释义, and
        # `head_before` cuts at the last 汉字), so it is dropped alone and the
        # valid entries sharing the OCR line survive.
        line = "stone /stəʊn/ n. 石头 /ˌɪnˈlænd/ adv. 内陆的"
        self.assertEqual([("stone", ["stəʊn"])], renai.parse_line(line))
        self.assertEqual(
            [("stone", ["stəʊn"])],
            renai.parse_line("stone /stəʊn/ n. 石头 T-shirt / n. T恤衫"),
        )

    def test_out_of_repertoire_part_drops_only_its_own_reading(self) -> None:
        # 七上: `he /hi; hi:/ pron.他` — the pre-2015 `hi` (no length mark) is
        # valid, the `hi:` is not; the group keeps what parsed.
        self.assertEqual([("he", ["hi"])], renai.parse_line("he /hi; hi:/ pron.他"))

    def test_a_wholly_invalid_group_still_yields_no_entry(self) -> None:
        # A continuation line: `/ˌɪnˈlænd/ adv. …` — no head to attach it to.
        self.assertEqual([], renai.parse_line("/ˌɪnˈlænd/ adv. 内陆的"))


if __name__ == "__main__":
    unittest.main()
