"""In-process semantic code index (BM25, zero-dep).

Phase 2.1 of the unified chat plan. Designed as a thin, swappable surface:

    BaseIndexer (ABC)
        └── BM25Indexer  — Okapi BM25, pure stdlib, in-memory
            (future:    — Qdrant / sqlite-vec / on-disk inverted index)

The concrete indexer is owned by the `/index/build` + `/index/search` routes
as a process-level singleton. The plugin pushes the corpus on project open
(and on debounced file-change), then the chat web UI searches it through the
`searchSemantic` bridge. Nothing is persisted — rebuilt on backend restart.

Why BM25 instead of embeddings for v1:
    - Zero extra deps, runs on loopback, no model download, no API key.
    - "@authenticate" finds files that mention the *word* authenticate,
      which is exactly what a developer expects when filtering by topic.
    - Good enough for a 2k-file project in well under a second; the
      ABC leaves a clean escape hatch for a real semantic index later.

Tokenizer:
    Code identifiers dominate the corpus, so we split each identifier into
    camel/snake subwords. `getUserById` becomes
    `getuserbyid`, `get`, `user`, `by`, `id` — that way `@user` and
    `@byid` both rank the function highly. Lowercased throughout; we don't
    stem (too code-aggressive and costs a dep).
"""

from __future__ import annotations

import math
import re
from abc import ABC, abstractmethod
from collections import Counter
from dataclasses import dataclass, field
from typing import Iterable

# Identifier-shaped tokens only. Numeric-only words get dropped — they make
# poor search signals for code (line counts, hex dumps, etc.).
_TOKEN_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
# Split camel/Pascal/snake subwords: "HTTPServer" -> ["HTTP", "Server"],
# "parse_json_response" -> ["parse", "json", "response"]. Runs of uppercase
# are treated as a single subword ("HTTP") so acronyms stay addressable.
_CAMEL_RE = re.compile(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|\d+")


def tokenize(text: str) -> list[str]:
    """Split `text` into lowercased identifier tokens + camel/snake subwords."""

    if not text:
        return []
    out: list[str] = []
    for m in _TOKEN_RE.finditer(text):
        word = m.group(0)
        lower = word.lower()
        out.append(lower)
        # Emit subwords only when they differ from the full word — avoids
        # double-counting single-word identifiers.
        subs = _CAMEL_RE.findall(word)
        if len(subs) > 1:
            for sub in subs:
                s = sub.lower()
                if s and s != lower:
                    out.append(s)
    return out


@dataclass
class Document:
    """One file's path + contents. Tokens are cached on first build."""

    path: str
    content: str
    tokens: list[str] = field(default_factory=list)


@dataclass
class Hit:
    """One ranked search match."""

    path: str
    score: float
    snippet: str = ""


class BaseIndexer(ABC):
    """Interface every future index backend must satisfy."""

    @abstractmethod
    def build(self, documents: Iterable[Document]) -> None:
        """Replace the corpus with `documents` wholesale."""

    @abstractmethod
    def search(self, query: str, k: int = 5) -> list[Hit]:
        """Return at most `k` best-matching hits, highest score first."""

    @abstractmethod
    def document_count(self) -> int: ...


class BM25Indexer(BaseIndexer):
    """Okapi BM25 (k1=1.5, b=0.75). Pure stdlib, in-memory, rebuild-wholesale."""

    # Canonical BM25 tuning parameters — documented in Robertson & Zaragoza
    # (2009). We don't tune these: for code-search on a small corpus the
    # defaults out-rank any naive TF-IDF variant.
    _K1 = 1.5
    _B = 0.75

    def __init__(self) -> None:
        self._docs: list[Document] = []
        self._df: Counter[str] = Counter()
        self._tf: list[Counter[str]] = []
        self._doc_len: list[int] = []
        self._avg_len: float = 0.0

    # --------------------------------------------------------------- build

    def build(self, documents: Iterable[Document]) -> None:
        # Reset everything — this is intentionally destructive. Callers that
        # want incremental updates should subclass (out of scope for v1).
        self._docs = []
        self._df = Counter()
        self._tf = []
        self._doc_len = []
        for d in documents:
            tokens = d.tokens if d.tokens else tokenize(d.content)
            tf: Counter[str] = Counter(tokens)
            self._tf.append(tf)
            self._doc_len.append(len(tokens))
            for term in tf:
                self._df[term] += 1
            self._docs.append(Document(path=d.path, content=d.content, tokens=tokens))
        n = len(self._docs)
        self._avg_len = (sum(self._doc_len) / n) if n else 0.0

    # --------------------------------------------------------------- query

    def document_count(self) -> int:
        return len(self._docs)

    def search(self, query: str, k: int = 5) -> list[Hit]:
        if not self._docs or k <= 0:
            return []
        q_terms = tokenize(query)
        if not q_terms:
            return []
        n = len(self._docs)
        scores = [0.0] * n
        # Accumulate BM25 term-at-a-time — cheaper than doc-at-a-time when the
        # query is short (usually 1-3 words).
        for term in q_terms:
            df = self._df.get(term, 0)
            if df == 0:
                continue
            idf = math.log(1.0 + (n - df + 0.5) / (df + 0.5))
            for i, tf in enumerate(self._tf):
                tf_t = tf.get(term, 0)
                if tf_t == 0:
                    continue
                dl = self._doc_len[i]
                norm = 1 - self._B + self._B * (dl / self._avg_len if self._avg_len else 1.0)
                scores[i] += idf * (tf_t * (self._K1 + 1)) / (tf_t + self._K1 * norm)
        ranked = sorted(enumerate(scores), key=lambda x: x[1], reverse=True)
        q_set = set(q_terms)
        hits: list[Hit] = []
        for i, s in ranked:
            if s <= 0 or len(hits) >= k:
                break
            d = self._docs[i]
            hits.append(Hit(path=d.path, score=round(s, 4), snippet=_pick_snippet(d.content, q_set)))
        return hits


def _pick_snippet(content: str, terms: set[str], radius: int = 80, cap: int = 240) -> str:
    """Return a short preview surrounding the first matching term.

    Falls back to the first non-empty line when no term matches (shouldn't
    normally happen — we only build snippets for hits that scored > 0 — but
    tokenization splits subwords while the original content keeps the full
    identifier, so a subword term can score a hit without literal match).
    """

    if not content:
        return ""
    lower = content.lower()
    best_idx = -1
    best_term_len = 0
    for t in terms:
        if not t:
            continue
        idx = lower.find(t)
        if idx != -1 and (best_idx == -1 or idx < best_idx):
            best_idx = idx
            best_term_len = len(t)
    if best_idx == -1:
        for line in content.splitlines():
            line = line.strip()
            if line:
                return line[:cap]
        return content[:cap]
    start = max(0, best_idx - radius)
    end = min(len(content), best_idx + best_term_len + radius)
    snippet = content[start:end].replace("\n", " ").strip()
    if start > 0:
        snippet = "… " + snippet
    if end < len(content):
        snippet = snippet + " …"
    return snippet[:cap]


# Module-level default singleton used by the HTTP routes. Tests can pass their
# own indexer; the route layer does not require this global.
default_indexer: BaseIndexer = BM25Indexer()
