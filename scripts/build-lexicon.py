#!/usr/bin/env python3
"""Build `app/src/main/assets/dict/lexicon-en.json` — the English lexicon.

Replaces `build-ecdict-meta.py` (schema: docs/WORDLIST.md §9). The
dictionary is a shared, read-time lookup table; word-list rows stay author
data and are never rewritten, so this file carries everything the app needs
to render a row's 词性/释义 and 音标:

    {"v": 2, "entries": {
       "let":  {"s": [{"p": "v.", "g": "让；允许"}], "i": ["let", "let"]},
       "fine": {"s": [{"p": "adj.", "g": "身体好的，健康的"},
                      {"p": "v.", "g": "对……处以罚款"}],
                "i": ["faɪn", "faɪn"]}}}

- `s` — 义项, one entry per ECDICT sense line, each keeping its own POS
  (the old flat `"pos|gloss"` had to fold every internal `;` into `，` to keep
  `；` as the sense separator). Omitted when the word has no senses.
- `i` — `[us, uk]`, the two accent readings, omitted when neither source has
  one. A source that prints only one accent writes `null` for the other, and
  the app prints only the accents that exist: copying the one side onto the
  other would fabricate a second accent for the 11,438 ipa-dict words that
  have exactly one (docs/implemented/2026-09-20-REVIEW-0.9.0.md §4.1; display rules: AGENTS.md *Built-in library*). 仁爱
  textbook first, ipa-dict otherwise: a headword the textbook prints keeps the
  textbook's symbols, both sides exactly as the tsv carries them (a single
  printed reading means the textbook considers them equal), because the
  student compares the app against the printed page (docs/WORDLIST.md §6/§9).

Sources:
- [ECDICT](https://github.com/skywind3000/ECDICT) (MIT) — 词性 + 中文释义;
- [ipa-dict](https://github.com/open-dict-data/ipa-dict) (MIT; the en_UK data
  derives from GPL-3.0 ipacards, the en_US from MIT cmudict-ipa) — 音标;
- `scripts/data/renai-ipa.tsv` — the 仁爱版英语 textbook's own IPA, extracted
  by `scripts/extract-renai-ipa.py`.

Entries kept: every ECDICT word the old filter kept, every headword that
appears in a shipped word list (so a list row always has a lookup), and every
仁爱 headword. A word with neither senses nor IPA is dropped, so the asset
stays bounded (~55k entries) instead of pulling in ipa-dict's 125k.

Run:    python3 scripts/build-lexicon.py
Downloads ecdict.csv and both ipa-dict files into `.cache/` on first run
(network required once). Regenerate only when refreshing a source.
"""

from __future__ import annotations

import csv
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE_DIR = ROOT / ".cache"
CACHE_CSV = CACHE_DIR / "ecdict.csv"
ASSETS_DIR = ROOT / "app" / "src" / "main" / "assets"
OUT_JSON = ASSETS_DIR / "dict" / "lexicon-en.json"
RENAI_TSV = ROOT / "scripts" / "data" / "renai-ipa.tsv"
ECDICT_URL = "https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv"
IPA_DICT_URLS = {
    "us": ("ipa-en_US.txt", "https://raw.githubusercontent.com/open-dict-data/ipa-dict/master/data/en_US.txt"),
    "uk": ("ipa-en_UK.txt", "https://raw.githubusercontent.com/open-dict-data/ipa-dict/master/data/en_UK.txt"),
}
SCHEMA_VERSION = 2

EXAM_TAG_RE = re.compile(r"\b(zk|gk|cet4|cet6|ky|toefl|ielts|gre)\b", re.I)
POS_PREFIX_RE = re.compile(
    r"^(n\.|v\.|vt\.|vi\.|adj\.|adv\.|prep\.|conj\.|pron\.|num\.|art\.|"
    r"int\.|interj\.|aux\.|abbr\.|contr\.|pl\.|a\.|na\.|un\.|vbl\.|pp\.|"
    r"pn\.|exclam\.|pref\.|suf\.|suff\.|comb\.|quant\.|phr\.|ph\.|st\.|"
    r"pr\.|ind\.|pers\.|col\.|ing\.|pla\.|stuff\.)\s*",
    re.I,
)

WEAK_FORM_RE = re.compile(
    r"^[A-Za-z][A-Za-z\s'\-]*的"
    r"(过去式|过去分词|现在分词|第三人称单数|复数|比较级|最高级)"
)
LETTER_NAME_RE = re.compile(r"^第.+字母")
DOMAIN_TAG_RE = re.compile(r"^\[([^\]]+)\]\s*")
# exchange keys that are surface forms of the headword
FORM_KEYS = {"p", "d", "i", "3", "r", "t", "s"}

# ECDICT's entries for a few ultra-common function words are noisy.
OVERRIDES: dict[str, tuple[str, str]] = {
    "a": ("art.", "一个"),
    "an": ("art.", "一个"),
    "the": ("art.", "这；那"),
}


def ensure_downloads() -> tuple[Path, dict[str, Path]]:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    if not (CACHE_CSV.exists() and CACHE_CSV.stat().st_size > 1_000_000):
        print(f"Downloading ECDICT → {CACHE_CSV} …")
        urllib.request.urlretrieve(ECDICT_URL, CACHE_CSV)
        print(f"Downloaded {CACHE_CSV.stat().st_size / 1e6:.1f} MB")
    ipa_paths: dict[str, Path] = {}
    for accent, (name, url) in IPA_DICT_URLS.items():
        path = CACHE_DIR / name
        if not (path.exists() and path.stat().st_size > 100_000):
            print(f"Downloading ipa-dict ({accent}) → {path} …")
            urllib.request.urlretrieve(url, path)
            print(f"Downloaded {path.stat().st_size / 1e6:.1f} MB")
        ipa_paths[accent] = path
    return CACHE_CSV, ipa_paths


def load_renai_ipa() -> dict[str, tuple[str, str]]:
    """仁爱 textbook IPA: lowercased headword → (us, uk). First row wins."""
    out: dict[str, tuple[str, str]] = {}
    if not RENAI_TSV.is_file():
        print(f"error  missing {RENAI_TSV} (run scripts/extract-renai-ipa.py)", file=sys.stderr)
        sys.exit(1)
    for line in RENAI_TSV.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        head, us, uk = (p.strip() for p in parts)
        if head and (us or uk):
            out.setdefault(head.lower(), (us, uk))
    return out


def load_ipa_dict(path: Path) -> dict[str, str]:
    """ipa-dict file → lowercased word → first reading, slashes stripped."""
    table: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        word, tab, readings = line.partition("\t")
        if not tab or not word.strip():
            continue
        first = readings.split(",")[0].strip()
        if first.startswith("/") and first.endswith("/") and len(first) > 2:
            table.setdefault(word.strip().lower(), first[1:-1].strip())
    return table


def speakable(line: str) -> str | None:
    text = line.strip()
    if not text:
        return None
    for pipe in ("|", "｜"):
        if pipe in text:
            text = text.split(pipe, 1)[0].strip()
    if "=" in text or "＝" in text:
        text = re.split(r"[=＝]", text, maxsplit=1)[0].strip()
    return text.lower() if text else None


def load_data_words() -> set[str]:
    words: set[str] = set()
    if not ASSETS_DIR.is_dir():
        return words
    for path in ASSETS_DIR.rglob("*.txt"):
        for line in path.read_text(encoding="utf-8").splitlines():
            w = speakable(line)
            if not w:
                continue
            words.add(w)
            if "/" in w:
                for part in w.split("/"):
                    part = part.strip()
                    if part:
                        words.add(part)
    return words


def normalize_gloss(raw: str) -> str:
    """One sense's gloss: single line, whitespace collapsed, `；` between its
    sub-senses (`;`/`；` inside a sense are the sense's own separators — the
    structured format no longer has to fold them away)."""
    text = (
        raw.replace("\\n", " ")
        .replace("\\r", " ")
        .replace("\n", " ")
        .replace("\r", " ")
        .replace(";", "；")
        .strip()
    )
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) > 80:  # 宽松上限，仅防异常超长条目
        text = text[:80].strip()
    return text


def parse_sense_line(line: str) -> tuple[str | None, str | None, int] | None:
    """Return (pos, gloss, priority). Higher priority is better."""
    line = line.strip()
    if not line:
        return None
    if line.startswith("【") or line.startswith("[网络]"):
        return None

    domain = DOMAIN_TAG_RE.match(line)
    if domain:
        line = line[domain.end() :].strip()
        if not line:
            return None
        priority = 0  # domain-tagged senses are noisy
    else:
        priority = 1

    pos: str | None = None
    m = POS_PREFIX_RE.match(line)
    if m:
        pos = m.group(1).lower()
        if pos == "a.":
            pos = "adj."
        if pos == "pl.":
            pos = "n."
        # ECDICT 用到的非标准缩写，归一为项目统一词性
        if pos in ("interj.", "exclam."):
            pos = "int."
        if pos in ("na.", "un.", "pla.", "pn."):
            pos = "n."
        if pos in ("vbl.", "pp."):
            pos = "v."
        if pos in ("pref.", "suf.", "suff.", "comb.", "stuff."):
            pos = "abbr."
        line = line[m.end() :].strip()
        # POS + another domain tag, e.g. "art. [计] 累加器"
        domain2 = DOMAIN_TAG_RE.match(line)
        if domain2:
            line = line[domain2.end() :].strip()
            priority = 0
        else:
            priority = 2

    gloss = normalize_gloss(line)
    if WEAK_FORM_RE.match(gloss or ""):
        return None
    if gloss and LETTER_NAME_RE.match(gloss):
        return None
    if not pos and not gloss:
        return None
    return pos, gloss or None, priority


def parse_senses(raw: str) -> list[dict[str, str]]:
    """Structured senses from an ECDICT translation field, best priority first."""
    # ECDICT mixes real newlines with literal "\n" / "\r" separators.
    text = (
        (raw or "")
        .replace("\\n", "\n")
        .replace("\\r", "\n")
        .replace("\r", "\n")
        .strip()
    )
    if not text:
        return []

    parsed = [sense for sense in (parse_sense_line(line) for line in text.split("\n")) if sense]
    parsed.sort(key=lambda s: s[2], reverse=True)
    senses: list[dict[str, str]] = []
    for pos, gloss, _ in parsed:
        if not gloss:
            continue
        senses.append({"p": pos, "g": gloss} if pos else {"g": gloss})
    return senses


def is_weak(senses: list[dict[str, str]]) -> bool:
    if not senses:
        return True
    if len(senses) == 1 and WEAK_FORM_RE.match(senses[0].get("g") or ""):
        return True
    return False


def parse_exchange(raw: str) -> dict[str, str]:
    """Parse `d:done/p:did/0:do` → {d: done, p: did, 0: do}."""
    out: dict[str, str] = {}
    for part in (raw or "").split("/"):
        part = part.strip()
        if not part or ":" not in part:
            continue
        key, val = part.split(":", 1)
        key = key.strip()
        val = val.strip().lower()
        if key and val:
            out[key] = val
    return out


def should_keep(
    word: str,
    tag: str,
    collins: int,
    oxford: int,
    frq: int,
    bnc: int,
) -> bool:
    if EXAM_TAG_RE.search(tag or ""):
        return True
    if oxford:
        return True
    if collins >= 1:
        return True
    if 0 < frq <= 20000 or 0 < bnc <= 20000:
        return True
    return False


def to_int(value: str | None) -> int:
    try:
        return int((value or "0").strip() or "0")
    except ValueError:
        return 0


def main() -> None:
    csv_path, ipa_paths = ensure_downloads()
    renai = load_renai_ipa()
    ipa_us = load_ipa_dict(ipa_paths["us"])
    ipa_uk = load_ipa_dict(ipa_paths["uk"])
    data_words = load_data_words()
    print(f"data words: {len(data_words)} · renai IPA: {len(renai)} · ipa-dict: {len(ipa_us)}/{len(ipa_uk)}")

    # word(lower) → {senses, exchange}
    entries: dict[str, dict] = {}
    # 词库词兜底候选:常规过滤未保留的 key → 首个条目的解析结果
    fallback: dict[str, dict] = {}

    with csv_path.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            word = (row.get("word") or "").strip()
            if not word:
                continue
            translation = (row.get("translation") or "").strip()
            if not translation:
                continue
            key = word.lower()

            senses = parse_senses(translation)
            if key in OVERRIDES:
                pos, gloss = OVERRIDES[key]
                senses = [{"p": pos, "g": gloss}]

            # 词库词兜底:记录首个条目(无论常规过滤是否通过)
            if key in data_words and key not in fallback:
                fallback[key] = {
                    "senses": senses,
                    "exchange": parse_exchange(row.get("exchange") or ""),
                }

            if not should_keep(
                word,
                row.get("tag") or "",
                to_int(row.get("collins")),
                to_int(row.get("oxford")),
                to_int(row.get("frq")),
                to_int(row.get("bnc")),
            ):
                continue
            # Prefer first occurrence; ECDICT is mostly unique by word
            if key in entries and not is_weak(entries[key]["senses"]):
                continue
            entries[key] = {
                "senses": senses,
                "exchange": parse_exchange(row.get("exchange") or ""),
            }
            fallback.pop(key, None)

    # 词库词兜底:常规过滤未保留的词库词,保留首个条目
    fallback_keys: set[str] = set()
    for key, meta in fallback.items():
        if key not in entries:
            entries[key] = meta
            fallback_keys.add(key)

    # Resolve weak form senses via lemma (exchange 0:)
    for key, meta in list(entries.items()):
        if not is_weak(meta["senses"]):
            continue
        lemma = meta["exchange"].get("0")
        if not lemma or lemma not in entries:
            continue
        src = entries[lemma]
        if is_weak(src["senses"]):
            continue
        meta["senses"] = src["senses"]

    # Propagate headword senses to surface forms from exchange
    for head, meta in list(entries.items()):
        if is_weak(meta["senses"]):
            continue
        for ek, form in meta["exchange"].items():
            if ek not in FORM_KEYS:
                continue
            if form == head:
                continue
            existing = entries.get(form)
            # 词库保底条目(fallback)不阻止词根释义覆盖,避免噪声条目占位
            if (
                existing
                and not is_weak(existing["senses"])
                and form not in fallback_keys
            ):
                continue
            entries[form] = {
                "senses": meta["senses"],
                "exchange": existing["exchange"] if existing else {},
            }
            # 已被词根释义覆盖的保底条目视为常规条目,避免后续传播再次覆盖
            # (leaves 同时是 leaf/leave 的表层形式,先到先得稳定)
            fallback_keys.discard(form)

    # Every headword that can appear in a list must have a lookup row, even
    # when ECDICT has nothing for it (modern textbook words: taikonaut, …).
    for word in data_words | set(renai):
        entries.setdefault(word, {"senses": [], "exchange": {}})

    out_entries: dict[str, dict] = {}
    ipa_from_renai = 0
    ipa_from_dict = 0
    us_only = 0
    uk_only = 0
    for key in entries:
        senses = entries[key]["senses"]
        if key in renai:
            us, uk = renai[key]
            ipa_from_renai += 1
        else:
            us, uk = ipa_us.get(key), ipa_uk.get(key)
            if us or uk:
                ipa_from_dict += 1
        # A source that prints only one accent leaves the other side absent:
        # the asset writes JSON `null` and the app prints the accents that
        # exist. Copying the one side onto the other fabricated a second
        # accent for 11,438 words that have exactly one (docs/implemented/2026-09-20-REVIEW-0.9.0.md §4.1).
        us = us or None
        uk = uk or None
        entry: dict[str, object] = {}
        if senses:
            entry["s"] = senses
        if us or uk:
            entry["i"] = [us, uk]
            if not us:
                uk_only += 1
            elif not uk:
                us_only += 1
        if entry:
            out_entries[key] = entry

    payload = {"v": SCHEMA_VERSION, "entries": out_entries}
    OUT_JSON.parent.mkdir(parents=True, exist_ok=True)
    OUT_JSON.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    size_kb = OUT_JSON.stat().st_size / 1024
    with_ipa = ipa_from_renai + ipa_from_dict
    equal = sum(1 for e in out_entries.values() if "i" in e and e["i"][0] == e["i"][1])
    print(
        f"wrote {len(out_entries)} entries ({with_ipa} with IPA: "
        f"{ipa_from_renai} 仁爱 + {ipa_from_dict} ipa-dict; "
        f"{us_only} US-only + {uk_only} UK-only, {equal} with equal accents) "
        f"→ {OUT_JSON} ({size_kb:.0f} KB)"
    )


if __name__ == "__main__":
    main()
