"""WS /events — plugin pushes events, dashboards subscribe for broadcast replay.

Envelope (see ARCHITECTURE.md §4):
    { "type": "hello|event|ack|broadcast|ping|pong|error",
      "timestamp": <epoch_ms>, "payload": {...} }
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from src.core.event_hub import event_hub
from src.models.events import DeveloperEvent, WSEnvelope, WSMessageType

logger = logging.getLogger(__name__)

router = APIRouter()


PING_INTERVAL_S = 20.0
IDLE_TIMEOUT_S = 45.0


def _now_ms() -> int:
    return int(time.time() * 1000)


def _envelope(msg_type: WSMessageType, payload: dict[str, Any]) -> dict[str, Any]:
    return {"type": msg_type.value, "timestamp": _now_ms(), "payload": payload}


async def _send(ws: WebSocket, msg_type: WSMessageType, payload: dict[str, Any]) -> None:
    await ws.send_json(_envelope(msg_type, payload))


async def _send_error(ws: WebSocket, code: str, message: str) -> None:
    await _send(ws, WSMessageType.ERROR, {"code": code, "message": message})


async def _handle_plugin(ws: WebSocket, session_id: str) -> None:
    """Role=plugin: ingest events, reply with ack, respond to ping/pong."""
    last_seen = time.monotonic()
    while True:
        try:
            raw = await asyncio.wait_for(ws.receive_json(), timeout=IDLE_TIMEOUT_S)
        except asyncio.TimeoutError:
            logger.info("plugin %s idle > %ss, closing", session_id, IDLE_TIMEOUT_S)
            await _send_error(ws, "idle_timeout", "No activity")
            return
        last_seen = time.monotonic()

        try:
            env = WSEnvelope(**raw)
        except Exception as exc:  # noqa: BLE001
            await _send_error(ws, "bad_envelope", f"{exc}")
            continue

        if env.type == WSMessageType.EVENT:
            try:
                event = DeveloperEvent(**env.payload)
            except Exception as exc:  # noqa: BLE001
                await _send_error(ws, "bad_event", f"{exc}")
                continue
            event_dict = event.model_dump(mode="json")
            await event_hub.publish(session_id, event_dict)
            await _send(ws, WSMessageType.ACK, {"event_id": event.event_id})
        elif env.type == WSMessageType.PING:
            await _send(ws, WSMessageType.PONG, {})
        elif env.type == WSMessageType.PONG:
            # Treat as liveness; nothing to do.
            _ = last_seen
        else:
            await _send_error(ws, "unexpected_type", f"plugin role cannot send {env.type.value}")


async def _handle_dashboard(ws: WebSocket, session_id: str) -> None:
    """Role=dashboard: replay snapshot, then stream new events."""
    # 1) Snapshot replay of last N events.
    snapshot = await event_hub.snapshot(session_id)
    await _send(
        ws,
        WSMessageType.BROADCAST,
        {"kind": "snapshot", "session_id": session_id, "events": snapshot},
    )

    # 2) Subscribe and stream forever.
    queue = await event_hub.subscribe(session_id)
    ping_task = asyncio.create_task(_ping_loop(ws))
    try:
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=IDLE_TIMEOUT_S)
            except asyncio.TimeoutError:
                # No events; rely on ping loop to keep the socket alive.
                continue
            await _send(
                ws,
                WSMessageType.BROADCAST,
                {"kind": "event", "event": event},
            )
    finally:
        ping_task.cancel()
        await event_hub.unsubscribe(session_id, queue)


async def _ping_loop(ws: WebSocket) -> None:
    try:
        while True:
            await asyncio.sleep(PING_INTERVAL_S)
            try:
                await _send(ws, WSMessageType.PING, {})
            except Exception:  # noqa: BLE001
                return
    except asyncio.CancelledError:
        return


@router.websocket("/events")
async def events_ws(ws: WebSocket) -> None:
    await ws.accept()
    role = "unknown"
    session_id = "unknown"
    try:
        # Handshake: first frame must be `hello`.
        try:
            raw = await asyncio.wait_for(ws.receive_json(), timeout=10.0)
        except asyncio.TimeoutError:
            await _send_error(ws, "hello_timeout", "Expected hello within 10s")
            await ws.close()
            return

        try:
            env = WSEnvelope(**raw)
        except Exception as exc:  # noqa: BLE001
            await _send_error(ws, "bad_envelope", f"{exc}")
            await ws.close()
            return

        if env.type != WSMessageType.HELLO:
            await _send_error(ws, "expected_hello", f"got {env.type.value}")
            await ws.close()
            return

        role = str(env.payload.get("role", "")).lower()
        session_id = str(env.payload.get("session_id") or "default")

        if role not in {"plugin", "dashboard"}:
            await _send_error(ws, "bad_role", f"role must be plugin|dashboard, got {role!r}")
            await ws.close()
            return

        logger.info("WS connect role=%s session=%s", role, session_id)
        await _send(
            ws,
            WSMessageType.ACK,
            {"role": role, "session_id": session_id},
        )

        if role == "plugin":
            await _handle_plugin(ws, session_id)
        else:
            await _handle_dashboard(ws, session_id)

    except WebSocketDisconnect:
        logger.info("WS disconnect role=%s session=%s", role, session_id)
    except Exception:  # noqa: BLE001
        logger.exception("WS error role=%s session=%s", role, session_id)
        try:
            await ws.close()
        except Exception:  # noqa: BLE001
            pass
