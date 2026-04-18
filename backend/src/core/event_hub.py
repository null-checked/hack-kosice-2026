"""In-memory ring buffer per session + pub/sub for dashboard subscribers."""

from __future__ import annotations

import asyncio
import logging
from collections import deque
from typing import Any

logger = logging.getLogger(__name__)


class EventHub:
    """Holds a per-session `deque` of recent events and a set of subscriber queues.

    Plugin clients push events into a session; dashboard clients subscribe to
    either a specific session or all sessions ("*") and receive every new event.
    On subscribe, the caller gets a snapshot of the last N events for replay.
    """

    def __init__(self, buffer_size: int = 500) -> None:
        self.buffer_size = buffer_size
        self._buffers: dict[str, deque[dict[str, Any]]] = {}
        self._subscribers: dict[str, set[asyncio.Queue[dict[str, Any]]]] = {}
        self._lock = asyncio.Lock()

    # ---------- producer side ----------

    async def publish(self, session_id: str, event: dict[str, Any]) -> None:
        """Store event in the session ring buffer and fan-out to subscribers."""
        async with self._lock:
            buf = self._buffers.setdefault(
                session_id, deque(maxlen=self.buffer_size)
            )
            buf.append(event)
            targets = list(self._subscribers.get(session_id, set())) + list(
                self._subscribers.get("*", set())
            )
        for q in targets:
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                logger.warning("Subscriber queue full for session %s; dropping", session_id)

    # ---------- consumer side ----------

    async def snapshot(self, session_id: str) -> list[dict[str, Any]]:
        """Return a copy of the current ring buffer for a session (or all if '*')."""
        async with self._lock:
            if session_id == "*":
                return [e for buf in self._buffers.values() for e in buf]
            buf = self._buffers.get(session_id)
            return list(buf) if buf else []

    async def subscribe(self, session_id: str) -> asyncio.Queue[dict[str, Any]]:
        """Register a new subscriber queue and return it."""
        queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=1024)
        async with self._lock:
            self._subscribers.setdefault(session_id, set()).add(queue)
        return queue

    async def unsubscribe(
        self, session_id: str, queue: asyncio.Queue[dict[str, Any]]
    ) -> None:
        async with self._lock:
            subs = self._subscribers.get(session_id)
            if subs and queue in subs:
                subs.remove(queue)
                if not subs:
                    self._subscribers.pop(session_id, None)

    async def stats(self) -> dict[str, Any]:
        async with self._lock:
            return {
                "sessions": {
                    sid: len(buf) for sid, buf in self._buffers.items()
                },
                "subscribers": {
                    sid: len(subs) for sid, subs in self._subscribers.items()
                },
            }


# Singleton used by the WebSocket endpoint.
event_hub = EventHub()
