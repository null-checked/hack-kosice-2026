"""POST /index/build and /index/search — BM25 semantic file search.

The plugin enumerates project-content files on open (and on debounced
VFS change) and pushes them here. The chat web UI queries via the
`searchSemantic` bridge when the user types `@?<query>` in the composer.

Design notes:
    - The indexer is a module-level singleton — one corpus per backend
      process. This is fine for v1 because each IDE sandbox spins up its
      own backend. A future multi-tenant variant would key the indexer
      by a project id.
    - Search-time allocations stay small: BM25 is computed term-at-a-time
      against pre-tokenised documents, and we never copy the full content
      back to the response (just a short snippet around the first match).
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends

from src.api.v1.deps import require_optional_token
from src.core.indexer import BM25Indexer, Document, default_indexer
from src.models.api import (
    IndexBuildRequest,
    IndexBuildResponse,
    IndexHit,
    IndexSearchRequest,
    IndexSearchResponse,
)

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post(
    "/index/build",
    response_model=IndexBuildResponse,
    dependencies=[Depends(require_optional_token)],
)
async def index_build(req: IndexBuildRequest) -> IndexBuildResponse:
    """Replace the process-level corpus with `req.files`.

    Callers decide the corpus — the backend does no file-system walking;
    everything is pushed from the plugin so privacy settings stay on the
    IDE side of the wire.
    """
    docs = [Document(path=f.path, content=f.content) for f in req.files]
    # Pure stdlib BM25; tests assert this via `isinstance`, so keep the
    # `BM25Indexer` type on the default singleton.
    assert isinstance(default_indexer, BM25Indexer)
    default_indexer.build(docs)
    n = default_indexer.document_count()
    logger.info("indexer.build: ingested %d docs", n)
    return IndexBuildResponse(indexed=n)


@router.post(
    "/index/search",
    response_model=IndexSearchResponse,
    dependencies=[Depends(require_optional_token)],
)
async def index_search(req: IndexSearchRequest) -> IndexSearchResponse:
    """Return ranked hits for `req.query` against the current corpus."""
    hits = default_indexer.search(req.query, k=req.k)
    return IndexSearchResponse(
        hits=[IndexHit(path=h.path, score=h.score, snippet=h.snippet) for h in hits],
    )
