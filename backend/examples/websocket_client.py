"""Smoke test for the RubberDuck Live WS /events endpoint.

Usage (backend must be running on :8765):
    python examples/websocket_client.py

It connects as a plugin, sends a hello, pushes one fake EDIT event, prints the
ack, then disconnects.
"""

from __future__ import annotations

import asyncio
import json
import time
import uuid

import websockets


WS_URL = "ws://127.0.0.1:8765/api/v1/events"


def now_ms() -> int:
    return int(time.time() * 1000)


async def main() -> None:
    session_id = f"demo-{uuid.uuid4()}"
    async with websockets.connect(WS_URL) as ws:
        # 1) Hello
        await ws.send(
            json.dumps(
                {
                    "type": "hello",
                    "timestamp": now_ms(),
                    "payload": {"role": "plugin", "session_id": session_id},
                }
            )
        )
        hello_ack = json.loads(await ws.recv())
        print("hello ack:", json.dumps(hello_ack, indent=2))

        # 2) One fake EDIT event
        event = {
            "event_id": str(uuid.uuid4()),
            "session_id": session_id,
            "ts": now_ms(),
            "type": "EDIT",
            "file": "src/fizz.py",
            "language": "python",
            "payload": {"chars_added": 12, "chars_removed": 0, "line": 42},
        }
        await ws.send(
            json.dumps(
                {"type": "event", "timestamp": now_ms(), "payload": event}
            )
        )
        event_ack = json.loads(await ws.recv())
        print("event ack:", json.dumps(event_ack, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
