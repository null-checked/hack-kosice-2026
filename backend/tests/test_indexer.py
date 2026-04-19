"""Tests for the BM25 semantic indexer (Phase 2.1)."""

from __future__ import annotations

import pytest

from src.core.indexer import BM25Indexer, Document, tokenize


# --------------------------------------------------------------- tokenize


def test_tokenize_empty_returns_empty_list() -> None:
    assert tokenize("") == []


def test_tokenize_lowercases_and_keeps_full_identifier() -> None:
    assert "authenticate" in tokenize("authenticate")


def test_tokenize_splits_camel_case_into_subwords() -> None:
    toks = tokenize("getUserById")
    # Full identifier is kept AND the subwords are emitted so "@user"
    # matches a CamelCase method.
    assert "getuserbyid" in toks
    assert "get" in toks
    assert "user" in toks
    assert "byid" in toks or "id" in toks


def test_tokenize_splits_snake_case_into_subwords() -> None:
    toks = tokenize("parse_json_response")
    assert "parse_json_response" in toks
    assert "parse" in toks
    assert "json" in toks
    assert "response" in toks


def test_tokenize_handles_acronyms() -> None:
    toks = tokenize("HTTPServer")
    assert "http" in toks
    assert "server" in toks


# --------------------------------------------------------------- BM25 search


def _make_index(*files: tuple[str, str]) -> BM25Indexer:
    idx = BM25Indexer()
    idx.build([Document(path=p, content=c) for p, c in files])
    return idx


def test_search_returns_empty_for_empty_index() -> None:
    idx = BM25Indexer()
    assert idx.search("anything") == []


def test_search_returns_file_that_mentions_query() -> None:
    idx = _make_index(
        ("auth/login.py", "def authenticate(user): return check_password(user)"),
        ("http/server.py", "class Server: def start(self): pass"),
        ("data/cache.py", "class LRUCache: def get(self, key): pass"),
    )
    hits = idx.search("authenticate", k=3)
    assert hits, "expected at least one hit for 'authenticate'"
    assert hits[0].path == "auth/login.py"
    # Score is monotonic-positive for the matched document.
    assert hits[0].score > 0


def test_search_ranks_higher_when_term_occurs_more_often() -> None:
    # BM25 is subject to TF saturation (k1), but within reason more hits
    # of the same term should still rank higher — the whole point of TF.
    idx = _make_index(
        ("many.py", "token token token token token"),
        ("few.py", "token something else entirely"),
    )
    hits = idx.search("token", k=2)
    assert len(hits) == 2
    assert hits[0].path == "many.py"
    assert hits[0].score > hits[1].score


def test_search_snippet_previews_match_context() -> None:
    idx = _make_index(
        ("util.py", "line1\nline2\ndef authenticate(u): return u\nline4\n"),
    )
    hits = idx.search("authenticate", k=1)
    assert hits
    assert "authenticate" in hits[0].snippet.lower()


def test_search_ignores_unknown_terms() -> None:
    idx = _make_index(
        ("a.py", "alpha beta gamma"),
    )
    hits = idx.search("zetadoesnotexist", k=5)
    assert hits == []


def test_search_k_caps_results() -> None:
    idx = _make_index(
        *[(f"f{i}.py", "common") for i in range(10)],
    )
    hits = idx.search("common", k=3)
    assert len(hits) == 3


def test_build_replaces_corpus_wholesale() -> None:
    idx = BM25Indexer()
    idx.build([Document(path="a.py", content="alpha")])
    assert idx.document_count() == 1
    idx.build([Document(path="b.py", content="beta"), Document(path="c.py", content="gamma")])
    assert idx.document_count() == 2
    # Old doc should be gone.
    assert idx.search("alpha", k=5) == []


def test_camelcase_query_matches_subword() -> None:
    idx = _make_index(
        ("user_service.py", "class UserRepository: def findByEmail(self, e): pass"),
        ("other.py", "def helper(): return 42"),
    )
    hits = idx.search("email", k=2)
    assert hits
    assert hits[0].path == "user_service.py"


# --------------------------------------------------------------- HTTP routes


@pytest.mark.asyncio
async def test_index_build_and_search_via_http() -> None:
    """End-to-end smoke: POST files to /index/build, then /index/search.

    Wraps the client in `app.router.lifespan_context` so FastAPI's startup
    hooks populate `app.state.settings` / `.llm_client` — the `/index/*`
    routes only need `.settings` (via the optional-auth dependency), but
    lifespan is still required because `deps.require_optional_token`
    reads it unconditionally.
    """

    from httpx import ASGITransport, AsyncClient

    from src.main import app

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        async with app.router.lifespan_context(app):
            build_resp = await client.post(
                "/api/v1/index/build",
                json={
                    "files": [
                        {"path": "auth/login.py", "content": "def authenticate(u): pass"},
                        {"path": "http/server.py", "content": "class Server: pass"},
                    ],
                },
            )
            assert build_resp.status_code == 200
            assert build_resp.json() == {"indexed": 2}

            search_resp = await client.post(
                "/api/v1/index/search",
                json={"query": "authenticate", "k": 3},
            )
            assert search_resp.status_code == 200
            body = search_resp.json()
            assert "hits" in body
            assert body["hits"], "expected at least one hit"
            assert body["hits"][0]["path"] == "auth/login.py"
            assert body["hits"][0]["score"] > 0
