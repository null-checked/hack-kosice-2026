"""POST /code-chat/stream — Server-Sent Events sibling of /code-chat.

Wire shape per frame:

    event: token             (orchestrator prose tokens)
    event: plan              (write_todos updates)
    event: subagent_start    (code-writer / test-writer / reviewer)
    event: subagent_end
    event: edit              (emit_edit output — CodeEdit JSON)
    event: done              (final CodeChatResponse)
    event: error             (terminal failure)

Two code paths under the single endpoint:

  * DEMO_MOCK → hand-crafted trace from `iter_demo_trace`, produces
    the full timeline without touching an LLM.
  * Live → `orchestrator_event_stream` wraps `astream` and translates.

We bind a fresh `EditSink` per request so multiple concurrent streams
never leak edits into one another. `reset_edit_sink` fires in a
`finally` so even a disconnected client can't leak the binding."""

from __future__ import annotations

import logging
import os
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import StreamingResponse

from src.api.v1.deps import (
    get_orchestrator,
    get_settings,
    require_optional_token,
)
from src.core.agents import (
    EditSink,
    bind_edit_sink,
    iter_demo_trace,
    orchestrator_event_stream,
)
from src.core.agents.collector import reset_edit_sink
from src.core.code_chat import _build_context, _build_initial_state  # noqa: PLC2701
from src.core.streaming import sse_event, sse_headers
from src.models.api import CodeChatRequest, CodeChatResponse, CodeEdit
from src.settings import Settings

logger = logging.getLogger(__name__)

router = APIRouter()


def _final_response_factory(req: CodeChatRequest, settings: Settings):
    """Closure used by `orchestrator_event_stream` to build the `done`
    payload. We don't know the orchestrator's prose until the stream has
    finished, so we pass a placeholder and let the plugin rely on
    accumulated `event: token` deltas for display. The `edits` come from
    the request-scoped sink."""

    def _build(sink: EditSink) -> CodeChatResponse:
        edits: list[CodeEdit] = []
        for entry in sink:
            raw = entry.get("edit") if isinstance(entry, dict) else None
            if isinstance(raw, dict):
                try:
                    edits.append(CodeEdit(**raw))
                except Exception as exc:  # noqa: BLE001
                    logger.warning("Skipping malformed edit: %s", exc)
        return CodeChatResponse(
            answer="",  # Plugin reconstructs prose from the token stream.
            model=settings.OPENAI_MODEL,
            edits=edits,
        )

    return _build


@router.post(
    "/code-chat/stream",
    dependencies=[Depends(require_optional_token)],
)
async def code_chat_stream_endpoint(
    req: CodeChatRequest,
    settings: Settings = Depends(get_settings),
    orchestrator: Any = Depends(get_orchestrator),
) -> StreamingResponse:
    logger.debug(
        "code-chat/stream: turns=%d allow_edits=%s orchestrator=%s mock=%s",
        len(req.conversation),
        req.allow_edits,
        orchestrator is not None,
        settings.DEMO_MOCK,
    )

    if settings.DEMO_MOCK:
        return StreamingResponse(
            iter_demo_trace(),
            media_type="text/event-stream; charset=utf-8",
            headers=sse_headers(),
        )

    if orchestrator is None:
        # Still give the client a valid SSE stream — just one error frame.
        async def _err():
            yield sse_event(
                "error",
                {
                    "error": "Orchestrator unavailable (missing OpenAI key or disabled).",
                    "step": "startup",
                },
            )

        return StreamingResponse(
            _err(),
            media_type="text/event-stream; charset=utf-8",
            headers=sse_headers(),
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        )

    # Live path — wrap the LangGraph astream in our translator. The sink
    # binding happens inside the generator so it lives for the duration
    # of the HTTP response, not just this function scope.
    work_dir = settings.ORCHESTRATOR_WORK_DIR or os.getcwd()
    initial_state = _build_initial_state(req, work_dir=work_dir, allow_edits=req.allow_edits)
    context = _build_context(req, settings)
    factory = _final_response_factory(req, settings)

    async def _driver():
        sink: EditSink = []
        token = bind_edit_sink(sink)
        try:
            async for frame in orchestrator_event_stream(
                orchestrator,
                initial_state=initial_state,
                context=context,
                settings=settings,
                final_response_factory=factory,
            ):
                yield frame
        except Exception as exc:  # noqa: BLE001
            logger.exception("/code-chat/stream driver crashed")
            yield sse_event("error", {"error": str(exc), "step": "driver"})
        finally:
            reset_edit_sink(token)

    return StreamingResponse(
        _driver(),
        media_type="text/event-stream; charset=utf-8",
        headers=sse_headers(),
    )


__all__ = ["router"]
