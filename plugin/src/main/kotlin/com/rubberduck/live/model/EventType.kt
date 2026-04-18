package com.rubberduck.live.model

/**
 * Canonical developer-event types emitted by the plugin.
 *
 * Matches [ARCHITECTURE.md §3](../../../../../../docs/ARCHITECTURE.md) exactly; the string
 * form is what crosses the wire.
 */
enum class EventType {
    EDIT,
    UNDO,
    REDO,
    TEST_RUN,
    FILE_FOCUS,
    CARET_DWELL,
    STUCK_DETECTED,
    INTERVENTION_SHOWN,
    INTERVENTION_ACCEPTED,
    INTERVENTION_DISMISSED,
}
