import * as fs from "fs";
import * as path from "path";

/**
 * Generates app/src/main/assets/compounds/compounds.json — per-character
 * word-compound data for single-char dictation ("生" → "生活的生").
 *
 * Port of the author's alice/scripts/generate-compounds.ts: same algorithm,
 * sources and syllable rules; alice emits src/lib/compounds.ts (RN TS source),
 * this copy emits the HearWrite APK asset JSON. Input paths resolve against
 * this repo (word lists live under app/src/main/assets/).
 *
 * Run: node scripts/generate-compounds.ts   (Node >= 23.6 built-in TS type
 * stripping — no npm dependencies; equivalent: npx tsx scripts/…)
 *
 * Data sources:
 * 1. scripts/data/xiandaihanyuchangyongcibiao.txt — 《现代汉语常用词表（草案）》
 *    (Ministry of Education; word frequency levels, 56008 words). Frequency
 *    level = commonness rank (smaller = more common). Used as the fallback
 *    common-word pool and as the canonical "is a real word" check.
 * 2. app/src/main/assets/人教版小学语文/*.txt — the built-in Chinese char
 *    lists. The meaning column of each entry is the textbook's own compound
 *    for the char ("月 | yuè | 月亮" → 月亮); these become the "already
 *    learned" pool, preferred over the common-word fallback.
 *
 * Output: app/src/main/assets/compounds/compounds.json
 *   {"compounds": {char: [[word, syllable-of-char-in-word], …]}, "learned": {…}}
 *   sorted by frequency; char keys ICU-zh sorted (pinyin, stroke tiebreak).
 *
 * Syllables come from the frequency list (tone digits, ü written as v, light
 * tone = no digit) and let runtime filter candidates by the headword's own
 * reading for polyphonic chars (长: 长期 cháng vs 增长 zhǎng).
 */

const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const FREQ_FILE = path.join(
  import.meta.dirname,
  "data/xiandaihanyuchangyongcibiao.txt",
);
const TEXTBOOK_DIR = path.join(REPO_ROOT, "app/src/main/assets/人教版小学语文");
const OUTPUT_FILE = path.join(
  REPO_ROOT,
  "app/src/main/assets/compounds/compounds.json",
);

/** Entries per char in compounds (frequency-ordered). */
const COMPOUNDS_PER_CHAR = 6;

const CJK2_RE = /^[\u4e00-\u9fff]{2}$/;

interface FreqWord {
  word: string;
  pinyin: string;
  level: number;
}

/** Parse "word\tpinyin\tlevel" lines. */
function parseFreqTable(text: string): FreqWord[] {
  const out: FreqWord[] = [];
  for (const line of text.split("\n")) {
    const [word, pinyin, level] = line.split("\t");
    if (!word || !pinyin || !level) continue;
    if (!CJK2_RE.test(word)) continue;
    out.push({ word, pinyin, level: Number(level) });
  }
  return out;
}

/** Split a textbook meaning cell into candidate senses ("月亮；月宫" → ["月亮", "月宫"]). */
function splitSenses(meaning: string): string[] {
  const out: string[] = [];
  for (const raw of meaning.split(/[；;]/)) {
    let text = raw
      .replace(/[（(][^（）()]*[）)]/g, "")
      .replace(/^[\s，,、。.：:；;]+|[\s，,、。.：:；;]+$/g, "")
      .trim();
    if (!text) continue;
    // Meaning cells may concatenate senses with commas; keep only the first
    // chunk (e.g. "天空、大地" → "天空") — shorter reads better aloud.
    text = text.split(/[，,、]/)[0] ?? text;
    text = text.replace(/^[\s，,、。.：:；;]+|[\s，,、。.：:；;]+$/g, "").trim();
    if (text) out.push(text);
  }
  return out;
}

/** Parse 人教版小学语文/*.txt ("字 | 拼音 | 释义" lines) into textbook compounds. */
function collectLearned(): Map<string, [string, string][]> {
  const learned = new Map<string, [string, string][]>();
  if (!fs.existsSync(TEXTBOOK_DIR)) return learned;

  for (const filename of fs.readdirSync(TEXTBOOK_DIR)) {
    if (!filename.endsWith(".txt")) continue;
    const content = fs.readFileSync(path.join(TEXTBOOK_DIR, filename), "utf-8");
    for (const line of content.split("\n")) {
      const parts = line.split("|").map((s) => s.trim());
      const head = parts[0];
      if (!head || head.length !== 1) continue;
      const meaning = parts[2];
      if (!meaning) continue;
      for (const sense of splitSenses(meaning)) {
        if (sense.length !== 2 || !sense.includes(head) || sense === head) continue;
        const list = learned.get(head) ?? [];
        list.push([sense, ""]); // syllable filled in later from freq table
        learned.set(head, list);
      }
    }
  }
  return learned;
}

// Frequency list file is pinyin-ordered; sort by level (stable: same-level
// ties keep the file's pinyin order).
const freqWords = parseFreqTable(fs.readFileSync(FREQ_FILE, "utf-8")).sort(
  (a, b) => a.level - b.level,
);
const freqLevel: Record<string, number> = {};
for (const w of freqWords) freqLevel[w.word] = w.level;

// Frequency-ordered index: char → [word, syllable][]
const byChar = new Map<string, [string, string][]>();
for (const w of freqWords) {
  for (let i = 0; i < w.word.length; i++) {
    const ch = w.word[i]!;
    const list = byChar.get(ch) ?? [];
    if (list.length < COMPOUNDS_PER_CHAR) {
      list.push([w.word, w.pinyin.split("'")[i] ?? ""]);
      byChar.set(ch, list);
    }
  }
}

// Textbook compounds, deduped, filtered to real words, frequency-ordered.
const learned = new Map<string, [string, string][]>();
for (const [head, senses] of collectLearned()) {
  const seen = new Set<string>();
  const list: [string, string][] = [];
  for (const [sense] of senses) {
    if (seen.has(sense)) continue;
    seen.add(sense);
    const level = freqLevel[sense];
    if (level === undefined) continue; // not a real word (e.g. "称对方")
    const wi = sense.indexOf(head);
    if (wi === -1) continue;
    const pinyin = freqWords.find((w) => w.word === sense)?.pinyin ?? "";
    list.push([sense, pinyin.split("'")[wi] ?? ""]);
  }
  list.sort((a, b) => freqLevel[a[0]]! - freqLevel[b[0]]!);
  if (list.length) learned.set(head, list);
}

// Char keys ICU-zh sorted (pinyin, stroke tiebreak) — same order as the
// alice generator's TS emission, so regeneration diffs stay clean.
const sortedByChar: Record<string, [string, string][]> = {};
for (const [ch, list] of [...byChar.entries()].sort((a, b) => a[0].localeCompare(b[0], "zh"))) {
  sortedByChar[ch] = list;
}
const sortedLearned: Record<string, [string, string][]> = {};
for (const [ch, list] of [...learned.entries()].sort((a, b) => a[0].localeCompare(b[0], "zh"))) {
  sortedLearned[ch] = list;
}

// Compact JSON (no spaces), raw UTF-8, no trailing newline — matches the
// shipped asset's format.
fs.writeFileSync(
  OUTPUT_FILE,
  JSON.stringify({ compounds: sortedByChar, learned: sortedLearned }),
  "utf-8",
);

console.log(
  `Generated ${OUTPUT_FILE}: ${byChar.size} chars in compounds, ${learned.size} chars in learned (from ${freqWords.length} two-char freq words).`,
);
