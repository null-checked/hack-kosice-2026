"""Event and WebSocket envelope models (see ARCHITECTURE.md §3 and §4)."""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class EventType(str, Enum):
    """Canonical developer-event types emitted by the plugin."""

    EDIT = "EDIT"
    UNDO = "UNDO"
    REDO = "REDO"
    TEST_RUN = "TEST_RUN"
    FILE_FOCUS = "FILE_FOCUS"
    CARET_DWELL = "CARET_DWELL"
    STUCK_DETECTED = "STUCK_DETECTED"
    INTERVENTION_SHOWN = "INTERVENTION_SHOWN"
    INTERVENTION_ACCEPTED = "INTERVENTION_ACCEPTED"
    INTERVENTION_DISMISSED = "INTERVENTION_DISMISSED"
    # Local inspection (com.intellij.localInspection / DaemonCodeAnalyzer)
    # fires a red-squiggly or warning. Plugin emits one event per NEW issue;
    # repeats of the same (file, line, severity, description) are deduped.
    INSPECTION = "INSPECTION"


class DeveloperEvent(BaseModel):
    """A single developer event. Timestamps are epoch-millis ints."""

    model_config = ConfigDict(extra="ignore")

    event_id: str
    session_id: str
    ts: int = Field(..., description="Epoch millis")
    type: EventType
    file: str | None = None
    language: str | None = None
    payload: dict[str, Any] = Field(default_factory=dict)


class WSMessageType(str, Enum):
    HELLO = "hello"
    EVENT = "event"
    ACK = "ack"
    BROADCAST = "broadcast"
    PING = "ping"
    PONG = "pong"
    ERROR = "error"


class WSEnvelope(BaseModel):
    """Wire envelope for all WebSocket traffic."""

    model_config = ConfigDict(extra="ignore")

    type: WSMessageType
    timestamp: int = Field(..., description="Epoch millis")
    payload: dict[str, Any] = Field(default_factory=dict)
