#!/usr/bin/env python3
"""Analyze VS Code metrics tracker NDJSON logs and generate plots, tables, and a report."""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean, median
from typing import Any

try:
    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.dates as mdates
    import matplotlib.pyplot as plt

    HAS_MATPLOTLIB = True
except Exception:
    HAS_MATPLOTLIB = False


@dataclass
class EventRecord:
    line_number: int
    raw_size_bytes: int
    timestamp: datetime | None
    event_type: str
    session_id: str
    workspace_name: str | None
    document_uri: str | None
    language_id: str | None
    payload: dict[str, Any]


@dataclass
class AnalysisStats:
    total_events: int
    parse_errors: int
    start_time: datetime | None
    end_time: datetime | None
    duration_seconds: float
    events_per_minute: float
    avg_event_size_bytes: float
    estimated_mb_per_hour: float
    unique_sessions: int
    unique_documents: int
    self_tracking_events: int
    self_tracking_ratio: float
    total_inserted_characters: int
    total_removed_characters: int
    net_text_characters: int
    added_removed_ratio: float
    event_type_counts: Counter[str]
    cursor_kind_counts: Counter[str]
    text_kind_counts: Counter[str]
    language_counts: Counter[str]
    timeline_per_minute: list[tuple[datetime, int]]
    document_rows: list[dict[str, Any]]
    session_rows: list[dict[str, Any]]
    text_rows: list[dict[str, Any]]
    top_added_text_rows: list[dict[str, Any]]
    top_removed_text_rows: list[dict[str, Any]]
    recommendations: list[str]
    findings: list[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Visualize and evaluate VS Code metrics tracker logs"
    )
    parser.add_argument(
        "--input",
        default="test_logs/events.ndjson",
        help="Path to input NDJSON metrics log",
    )
    parser.add_argument(
        "--output",
        default="analysis_output",
        help="Directory for plots, tables, and report",
    )
    parser.add_argument(
        "--top-documents",
        type=int,
        default=12,
        help="How many top documents to show in document chart",
    )
    return parser.parse_args()


def parse_timestamp(timestamp: Any) -> datetime | None:
    if not isinstance(timestamp, str) or not timestamp:
        return None

    cleaned = timestamp.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(cleaned)
    except ValueError:
        return None

    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)

    return parsed


def to_float(value: Any, default: float = 0.0) -> float:
    if isinstance(value, bool):
        return default
    if isinstance(value, (int, float)):
        return float(value)
    return default


def normalize_uri(uri: str | None) -> str:
    if not isinstance(uri, str):
        return ""
    return uri.lower().replace("\\", "/")


def to_bool(value: Any, default: bool = False) -> bool:
    if isinstance(value, bool):
        return value
    return default


def to_preview_text(value: str, limit: int = 120) -> str:
    escaped = value.replace("\r", "\\r").replace("\n", "\\n")
    if len(escaped) <= limit:
        return escaped
    if limit <= 3:
        return escaped[:limit]
    return f"{escaped[: limit - 3]}..."


def top_text_rows(counter: Counter[str], top_n: int = 8) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for text, count in counter.most_common(top_n):
        rows.append(
            {
                "text": text,
                "text_preview": to_preview_text(text, limit=140),
                "count": count,
                "text_length": len(text),
                "total_characters": len(text) * count,
            }
        )
    return rows


def load_events(path: Path) -> tuple[list[EventRecord], list[str]]:
    events: list[EventRecord] = []
    errors: list[str] = []

    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped:
                continue

            try:
                raw = json.loads(stripped)
            except json.JSONDecodeError as error:
                errors.append(f"line {line_number}: invalid JSON ({error})")
                continue

            payload = raw.get("payload")
            document = raw.get("document")

            if not isinstance(payload, dict):
                payload = {}
            if not isinstance(document, dict):
                document = {}

            events.append(
                EventRecord(
                    line_number=line_number,
                    raw_size_bytes=len(line.encode("utf-8")),
                    timestamp=parse_timestamp(raw.get("timestamp")),
                    event_type=str(raw.get("eventType", "unknown")),
                    session_id=str(raw.get("sessionId", "unknown")),
                    workspace_name=(
                        str(raw.get("workspaceName"))
                        if raw.get("workspaceName") is not None
                        else None
                    ),
                    document_uri=(
                        str(document.get("uri"))
                        if document.get("uri") is not None
                        else None
                    ),
                    language_id=(
                        str(document.get("languageId"))
                        if document.get("languageId") is not None
                        else None
                    ),
                    payload=payload,
                )
            )

    events.sort(key=lambda event: (event.timestamp or datetime.min.replace(tzinfo=timezone.utc), event.line_number))
    return events, errors


def build_analysis(events: list[EventRecord], parse_errors: list[str]) -> AnalysisStats:
    event_type_counts: Counter[str] = Counter()
    cursor_kind_counts: Counter[str] = Counter()
    text_kind_counts: Counter[str] = Counter()
    language_counts: Counter[str] = Counter()

    timeline_counter: Counter[datetime] = Counter()

    document_stats: dict[str, dict[str, Any]] = {}
    session_stats: dict[str, dict[str, Any]] = {}

    text_rows: list[dict[str, Any]] = []
    text_points: list[tuple[datetime, float, float]] = []

    timestamps: list[datetime] = []
    event_sizes: list[int] = []

    text_change_counts: list[float] = []
    text_inserted_lengths: list[float] = []
    text_removed_lengths: list[float] = []
    text_duration_ms_values: list[float] = []
    added_text_counter: Counter[str] = Counter()
    removed_text_counter: Counter[str] = Counter()

    cursor_settle_ms_values: list[float] = []

    self_tracking_events = 0

    for event in events:
        event_type_counts[event.event_type] += 1
        event_sizes.append(event.raw_size_bytes)

        if event.timestamp is not None:
            timestamps.append(event.timestamp)
            minute_bucket = event.timestamp.replace(second=0, microsecond=0)
            timeline_counter[minute_bucket] += 1

        if event.language_id:
            language_counts[event.language_id] += 1

        document_key = event.document_uri or "<no-document>"
        document_entry = document_stats.setdefault(
            document_key,
            {
                "document_uri": document_key,
                "language": event.language_id or "unknown",
                "events": 0,
                "text_events": 0,
                "cursor_events": 0,
                "inserted_length": 0.0,
                "removed_length": 0.0,
                "sessions": set(),
            },
        )
        document_entry["events"] += 1
        if event.session_id:
            document_entry["sessions"].add(event.session_id)

        session_entry = session_stats.setdefault(
            event.session_id,
            {
                "session_id": event.session_id,
                "events": 0,
                "text_events": 0,
                "cursor_events": 0,
                "start": event.timestamp,
                "end": event.timestamp,
                "inserted_length": 0.0,
                "removed_length": 0.0,
            },
        )
        session_entry["events"] += 1
        if event.timestamp is not None:
            if session_entry["start"] is None or event.timestamp < session_entry["start"]:
                session_entry["start"] = event.timestamp
            if session_entry["end"] is None or event.timestamp > session_entry["end"]:
                session_entry["end"] = event.timestamp

        normalized_uri = normalize_uri(event.document_uri)
        if "/.metrics/events.ndjson" in normalized_uri:
            self_tracking_events += 1

        if event.event_type == "cursor-selection":
            kind = str(event.payload.get("kind", "unknown"))
            cursor_kind_counts[kind] += 1
            document_entry["cursor_events"] += 1
            session_entry["cursor_events"] += 1

            settle_ms = to_float(event.payload.get("settleWindowMs", event.payload.get("idleMs", 0.0)))
            cursor_settle_ms_values.append(settle_ms)

        if event.event_type == "text-change":
            inserted = to_float(event.payload.get("insertedLength", 0.0))
            removed = to_float(event.payload.get("removedLength", 0.0))
            change_count = to_float(event.payload.get("changeCount", 0.0))
            duration_ms = to_float(event.payload.get("durationMs", 0.0))
            inserted_text = event.payload.get("insertedText")
            removed_text = event.payload.get("removedText")

            if not isinstance(inserted_text, str):
                inserted_text = ""
            if not isinstance(removed_text, str):
                removed_text = ""

            inserted_text_truncated = to_bool(event.payload.get("insertedTextTruncated", False))
            removed_text_truncated = to_bool(event.payload.get("removedTextTruncated", False))

            if inserted_text:
                added_text_counter[inserted_text] += 1
            if removed_text:
                removed_text_counter[removed_text] += 1

            document_entry["text_events"] += 1
            document_entry["inserted_length"] += inserted
            document_entry["removed_length"] += removed

            session_entry["text_events"] += 1
            session_entry["inserted_length"] += inserted
            session_entry["removed_length"] += removed

            text_change_counts.append(change_count)
            text_inserted_lengths.append(inserted)
            text_removed_lengths.append(removed)
            text_duration_ms_values.append(duration_ms)

            kinds = event.payload.get("kinds")
            if isinstance(kinds, dict):
                for kind_name, kind_value in kinds.items():
                    text_kind_counts[str(kind_name)] += int(to_float(kind_value, 0.0))

            text_rows.append(
                {
                    "timestamp": event.timestamp.isoformat() if event.timestamp else "",
                    "session_id": event.session_id,
                    "document_uri": event.document_uri or "",
                    "change_count": int(change_count),
                    "inserted_length": int(inserted),
                    "removed_length": int(removed),
                    "duration_ms": int(duration_ms),
                    "inserted_text": inserted_text,
                    "removed_text": removed_text,
                    "inserted_text_preview": to_preview_text(inserted_text),
                    "removed_text_preview": to_preview_text(removed_text),
                    "inserted_text_truncated": inserted_text_truncated,
                    "removed_text_truncated": removed_text_truncated,
                }
            )

            if event.timestamp is not None:
                text_points.append((event.timestamp, inserted, removed))

    start_time = min(timestamps) if timestamps else None
    end_time = max(timestamps) if timestamps else None
    duration_seconds = (
        max(0.0, (end_time - start_time).total_seconds())
        if start_time is not None and end_time is not None
        else 0.0
    )

    duration_minutes = max(duration_seconds / 60.0, 1e-9)
    events_per_minute = len(events) / duration_minutes if events else 0.0

    avg_event_size_bytes = mean(event_sizes) if event_sizes else 0.0
    estimated_mb_per_hour = (
        (events_per_minute * 60.0 * avg_event_size_bytes) / (1024.0 * 1024.0)
        if events
        else 0.0
    )

    timeline_per_minute = sorted(timeline_counter.items(), key=lambda item: item[0])

    document_rows = []
    for row in document_stats.values():
        document_rows.append(
            {
                "document_uri": row["document_uri"],
                "language": row["language"],
                "events": int(row["events"]),
                "text_events": int(row["text_events"]),
                "cursor_events": int(row["cursor_events"]),
                "inserted_length": int(row["inserted_length"]),
                "removed_length": int(row["removed_length"]),
                "net_length": int(row["inserted_length"] - row["removed_length"]),
                "sessions": int(len(row["sessions"])),
            }
        )
    document_rows.sort(key=lambda row: row["events"], reverse=True)

    session_rows = []
    for row in session_stats.values():
        start = row["start"]
        end = row["end"]
        session_duration = (
            max(0.0, (end - start).total_seconds())
            if isinstance(start, datetime) and isinstance(end, datetime)
            else 0.0
        )

        session_rows.append(
            {
                "session_id": row["session_id"],
                "events": int(row["events"]),
                "text_events": int(row["text_events"]),
                "cursor_events": int(row["cursor_events"]),
                "inserted_length": int(row["inserted_length"]),
                "removed_length": int(row["removed_length"]),
                "net_length": int(row["inserted_length"] - row["removed_length"]),
                "start": start.isoformat() if isinstance(start, datetime) else "",
                "end": end.isoformat() if isinstance(end, datetime) else "",
                "duration_seconds": int(session_duration),
            }
        )
    session_rows.sort(key=lambda row: row["events"], reverse=True)

    text_event_count = event_type_counts.get("text-change", 0)
    cursor_event_count = event_type_counts.get("cursor-selection", 0)

    self_tracking_ratio = self_tracking_events / len(events) if events else 0.0
    unknown_cursor_ratio = (
        cursor_kind_counts.get("unknown", 0) / cursor_event_count if cursor_event_count else 0.0
    )

    avg_changes_per_text_event = mean(text_change_counts) if text_change_counts else 0.0
    avg_inserted_per_text_event = mean(text_inserted_lengths) if text_inserted_lengths else 0.0
    median_inserted_per_text_event = median(text_inserted_lengths) if text_inserted_lengths else 0.0
    avg_removed_per_text_event = mean(text_removed_lengths) if text_removed_lengths else 0.0
    avg_text_duration_ms = mean(text_duration_ms_values) if text_duration_ms_values else 0.0
    total_inserted_characters = int(sum(text_inserted_lengths))
    total_removed_characters = int(sum(text_removed_lengths))
    net_text_characters = total_inserted_characters - total_removed_characters
    added_removed_ratio = (
        (total_inserted_characters / total_removed_characters)
        if total_removed_characters > 0
        else float(total_inserted_characters)
    )
    top_added_text_rows = top_text_rows(added_text_counter)
    top_removed_text_rows = top_text_rows(removed_text_counter)

    large_text_event_threshold = 200
    large_text_events = sum(1 for value in text_inserted_lengths if value >= large_text_event_threshold)
    large_text_event_ratio = large_text_events / text_event_count if text_event_count else 0.0

    findings: list[str] = []
    recommendations: list[str] = []

    findings.append(
        f"Collected {len(events)} events across {len(session_rows)} session(s) and {len(document_rows)} document(s)."
    )
    findings.append(
        f"Average event throughput is {events_per_minute:.2f} events/min, with estimated log growth of {estimated_mb_per_hour:.2f} MB/hour at this rate."
    )

    if text_event_count:
        findings.append(
            "Text-change aggregation: "
            f"avg changes/event {avg_changes_per_text_event:.2f}, "
            f"avg inserted chars/event {avg_inserted_per_text_event:.1f}, "
            f"median inserted chars/event {median_inserted_per_text_event:.1f}, "
            f"avg removed chars/event {avg_removed_per_text_event:.1f}, "
            f"avg batch duration {avg_text_duration_ms:.1f} ms."
        )
        findings.append(
            "Added/removed text totals: "
            f"added {total_inserted_characters} chars, "
            f"removed {total_removed_characters} chars, "
            f"net {net_text_characters:+d} chars."
        )

    if cursor_event_count:
        findings.append(
            f"Cursor-selection events: {cursor_event_count}, unknown kind ratio: {unknown_cursor_ratio:.1%}."
        )
        findings.append(
            "Cursor kind 'unknown' means VS Code did not classify the source as keyboard, mouse, or command."
        )

    if self_tracking_ratio >= 0.20:
        findings.append(
            f"High self-tracking detected: {self_tracking_events} events ({self_tracking_ratio:.1%}) target the metrics output file."
        )
        recommendations.append(
            "Exclude the metrics output file from tracking to avoid recursive logging and inflated text-change payload sizes."
        )

    if large_text_event_ratio >= 0.10:
        findings.append(
            f"Large text-change events are common: {large_text_events}/{text_event_count} ({large_text_event_ratio:.1%}) inserted >= {large_text_event_threshold} chars."
        )
        recommendations.append(
            "Consider lowering maxRawTextChars or disabling raw text capture for privacy and smaller logs."
        )

    if avg_changes_per_text_event <= 1.2 and text_event_count > 0:
        recommendations.append(
            "Text batching appears limited in this sample. Increase textChangeDebounceMs if you want fewer text-change events."
        )
    else:
        findings.append("Text batching appears effective (multiple editor changes grouped per event).")

    if unknown_cursor_ratio > 0.40 and cursor_event_count > 0:
        recommendations.append(
            "High unknown cursor-kind ratio suggests many non-keyboard interactions; consider separating keyboard and mouse analytics."
        )

    if estimated_mb_per_hour > 25.0:
        recommendations.append(
            "Current event density can still produce sizable logs over long sessions; reduce maxEvents and increase debounce values for leaner retention."
        )

    if not recommendations:
        recommendations.append("No critical issues detected in this sample. Current tracking volume appears manageable.")

    return AnalysisStats(
        total_events=len(events),
        parse_errors=len(parse_errors),
        start_time=start_time,
        end_time=end_time,
        duration_seconds=duration_seconds,
        events_per_minute=events_per_minute,
        avg_event_size_bytes=avg_event_size_bytes,
        estimated_mb_per_hour=estimated_mb_per_hour,
        unique_sessions=len(session_rows),
        unique_documents=len(document_rows),
        self_tracking_events=self_tracking_events,
        self_tracking_ratio=self_tracking_ratio,
        total_inserted_characters=total_inserted_characters,
        total_removed_characters=total_removed_characters,
        net_text_characters=net_text_characters,
        added_removed_ratio=added_removed_ratio,
        event_type_counts=event_type_counts,
        cursor_kind_counts=cursor_kind_counts,
        text_kind_counts=text_kind_counts,
        language_counts=language_counts,
        timeline_per_minute=timeline_per_minute,
        document_rows=document_rows,
        session_rows=session_rows,
        text_rows=text_rows,
        top_added_text_rows=top_added_text_rows,
        top_removed_text_rows=top_removed_text_rows,
        recommendations=recommendations,
        findings=findings,
    )


def write_csv(path: Path, headers: list[str], rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=headers)
        writer.writeheader()
        for row in rows:
            writer.writerow({header: row.get(header, "") for header in headers})


def generate_tables(output_dir: Path, stats: AnalysisStats) -> None:
    overview_rows = [
        {"metric": "total_events", "value": stats.total_events},
        {"metric": "parse_errors", "value": stats.parse_errors},
        {"metric": "start_time", "value": stats.start_time.isoformat() if stats.start_time else ""},
        {"metric": "end_time", "value": stats.end_time.isoformat() if stats.end_time else ""},
        {"metric": "duration_seconds", "value": int(stats.duration_seconds)},
        {"metric": "events_per_minute", "value": f"{stats.events_per_minute:.3f}"},
        {"metric": "avg_event_size_bytes", "value": f"{stats.avg_event_size_bytes:.2f}"},
        {"metric": "estimated_mb_per_hour", "value": f"{stats.estimated_mb_per_hour:.3f}"},
        {"metric": "unique_sessions", "value": stats.unique_sessions},
        {"metric": "unique_documents", "value": stats.unique_documents},
        {"metric": "self_tracking_events", "value": stats.self_tracking_events},
        {"metric": "self_tracking_ratio", "value": f"{stats.self_tracking_ratio:.4f}"},
        {"metric": "total_inserted_characters", "value": stats.total_inserted_characters},
        {"metric": "total_removed_characters", "value": stats.total_removed_characters},
        {"metric": "net_text_characters", "value": stats.net_text_characters},
        {
            "metric": "added_removed_ratio",
            "value": f"{stats.added_removed_ratio:.3f}" if stats.total_removed_characters > 0 else "inf",
        },
    ]
    write_csv(output_dir / "overview.csv", ["metric", "value"], overview_rows)

    event_type_rows = [
        {"event_type": event_type, "count": count}
        for event_type, count in sorted(stats.event_type_counts.items(), key=lambda item: item[1], reverse=True)
    ]
    write_csv(output_dir / "event_type_counts.csv", ["event_type", "count"], event_type_rows)

    cursor_kind_rows = [
        {"cursor_kind": kind, "count": count}
        for kind, count in sorted(stats.cursor_kind_counts.items(), key=lambda item: item[1], reverse=True)
    ]
    write_csv(output_dir / "cursor_kind_counts.csv", ["cursor_kind", "count"], cursor_kind_rows)

    text_kind_rows = [
        {"text_kind": kind, "count": count}
        for kind, count in sorted(stats.text_kind_counts.items(), key=lambda item: item[1], reverse=True)
    ]
    write_csv(output_dir / "text_kind_counts.csv", ["text_kind", "count"], text_kind_rows)

    write_csv(
        output_dir / "document_activity.csv",
        [
            "document_uri",
            "language",
            "events",
            "text_events",
            "cursor_events",
            "inserted_length",
            "removed_length",
            "net_length",
            "sessions",
        ],
        stats.document_rows,
    )

    write_csv(
        output_dir / "session_activity.csv",
        [
            "session_id",
            "events",
            "text_events",
            "cursor_events",
            "inserted_length",
            "removed_length",
            "net_length",
            "start",
            "end",
            "duration_seconds",
        ],
        stats.session_rows,
    )

    write_csv(
        output_dir / "text_change_events.csv",
        [
            "timestamp",
            "session_id",
            "document_uri",
            "change_count",
            "inserted_length",
            "removed_length",
            "duration_ms",
            "inserted_text",
            "removed_text",
            "inserted_text_preview",
            "removed_text_preview",
            "inserted_text_truncated",
            "removed_text_truncated",
        ],
        stats.text_rows,
    )

    write_csv(
        output_dir / "top_added_text.csv",
        ["text", "text_preview", "count", "text_length", "total_characters"],
        stats.top_added_text_rows,
    )

    write_csv(
        output_dir / "top_removed_text.csv",
        ["text", "text_preview", "count", "text_length", "total_characters"],
        stats.top_removed_text_rows,
    )


def plot_event_type_counts(output_dir: Path, stats: AnalysisStats) -> None:
    labels = [item[0] for item in stats.event_type_counts.most_common()]
    values = [item[1] for item in stats.event_type_counts.most_common()]

    figure, axis = plt.subplots(figsize=(9, 5))
    axis.bar(labels, values, color="#2a9d8f")
    axis.set_title("Event Type Distribution")
    axis.set_ylabel("Count")
    axis.tick_params(axis="x", rotation=25)
    axis.grid(axis="y", alpha=0.25)
    figure.tight_layout()
    figure.savefig(output_dir / "event_type_distribution.png", dpi=150)
    plt.close(figure)


def plot_timeline(output_dir: Path, stats: AnalysisStats) -> None:
    if not stats.timeline_per_minute:
        return

    times = [item[0] for item in stats.timeline_per_minute]
    counts = [item[1] for item in stats.timeline_per_minute]

    figure, axis = plt.subplots(figsize=(10, 4))
    axis.plot(times, counts, marker="o", linewidth=1.8, color="#264653")
    axis.set_title("Event Throughput Per Minute")
    axis.set_ylabel("Events")
    axis.set_xlabel("Timestamp")
    axis.grid(alpha=0.3)
    axis.xaxis.set_major_formatter(mdates.DateFormatter("%H:%M"))
    figure.autofmt_xdate()
    figure.tight_layout()
    figure.savefig(output_dir / "events_per_minute.png", dpi=150)
    plt.close(figure)


def plot_cumulative_text_volume(output_dir: Path, events: list[EventRecord]) -> None:
    points: list[tuple[datetime, float, float]] = []
    for event in events:
        if event.event_type != "text-change" or event.timestamp is None:
            continue
        points.append(
            (
                event.timestamp,
                to_float(event.payload.get("insertedLength", 0.0)),
                to_float(event.payload.get("removedLength", 0.0)),
            )
        )

    if not points:
        return

    points.sort(key=lambda item: item[0])

    xs: list[datetime] = []
    inserted_cumulative: list[float] = []
    removed_cumulative: list[float] = []

    inserted_total = 0.0
    removed_total = 0.0

    for timestamp, inserted, removed in points:
        inserted_total += inserted
        removed_total += removed
        xs.append(timestamp)
        inserted_cumulative.append(inserted_total)
        removed_cumulative.append(removed_total)

    figure, axis = plt.subplots(figsize=(10, 4.5))
    axis.plot(xs, inserted_cumulative, label="Inserted", color="#2a9d8f", linewidth=2)
    axis.plot(xs, removed_cumulative, label="Removed", color="#e76f51", linewidth=2)
    axis.set_title("Cumulative Text Volume")
    axis.set_ylabel("Characters")
    axis.set_xlabel("Timestamp")
    axis.grid(alpha=0.3)
    axis.legend(loc="upper left")
    axis.xaxis.set_major_formatter(mdates.DateFormatter("%H:%M"))
    figure.autofmt_xdate()
    figure.tight_layout()
    figure.savefig(output_dir / "cumulative_text_volume.png", dpi=150)
    plt.close(figure)


def plot_top_documents(output_dir: Path, stats: AnalysisStats, top_documents: int) -> None:
    rows = stats.document_rows[: max(1, top_documents)]
    if not rows:
        return

    labels = [shorten_label(row["document_uri"], limit=62) for row in rows]
    values = [row["events"] for row in rows]

    figure, axis = plt.subplots(figsize=(11, 6))
    axis.barh(labels[::-1], values[::-1], color="#457b9d")
    axis.set_title("Top Documents By Event Count")
    axis.set_xlabel("Events")
    axis.grid(axis="x", alpha=0.25)
    figure.tight_layout()
    figure.savefig(output_dir / "top_documents_by_events.png", dpi=150)
    plt.close(figure)


def plot_cursor_kind_distribution(output_dir: Path, stats: AnalysisStats) -> None:
    if not stats.cursor_kind_counts:
        return

    labels = [item[0] for item in stats.cursor_kind_counts.most_common()]
    values = [item[1] for item in stats.cursor_kind_counts.most_common()]

    figure, axis = plt.subplots(figsize=(8, 4.5))
    axis.bar(labels, values, color="#f4a261")
    axis.set_title("Cursor Selection Kind Distribution")
    axis.set_ylabel("Count")
    axis.grid(axis="y", alpha=0.25)
    figure.tight_layout()
    figure.savefig(output_dir / "cursor_kind_distribution.png", dpi=150)
    plt.close(figure)


def generate_plots(output_dir: Path, stats: AnalysisStats, events: list[EventRecord], top_documents: int) -> list[str]:
    if not HAS_MATPLOTLIB:
        return [
            "matplotlib not available; skipped plot generation. Install matplotlib to enable charts."
        ]

    output_dir.mkdir(parents=True, exist_ok=True)

    plot_event_type_counts(output_dir, stats)
    plot_timeline(output_dir, stats)
    plot_cumulative_text_volume(output_dir, events)
    plot_top_documents(output_dir, stats, top_documents)
    plot_cursor_kind_distribution(output_dir, stats)

    return []


def shorten_label(value: str, limit: int = 55) -> str:
    if len(value) <= limit:
        return value
    return f"...{value[-(limit - 3):]}"


def generate_markdown_report(
    output_dir: Path,
    input_file: Path,
    stats: AnalysisStats,
    plot_notes: list[str],
    parse_errors: list[str],
) -> Path:
    report_path = output_dir / "analysis_report.md"

    report_lines: list[str] = []
    report_lines.append("# Metrics Log Analysis Report")
    report_lines.append("")
    report_lines.append(f"- Input file: {input_file}")
    report_lines.append(f"- Generated at: {datetime.now(timezone.utc).isoformat()}")
    report_lines.append(f"- Total events: {stats.total_events}")
    report_lines.append(f"- Parse errors: {stats.parse_errors}")
    report_lines.append("")

    report_lines.append("## Timeline")
    report_lines.append(
        f"- Start: {stats.start_time.isoformat() if stats.start_time else 'n/a'}"
    )
    report_lines.append(f"- End: {stats.end_time.isoformat() if stats.end_time else 'n/a'}")
    report_lines.append(f"- Duration seconds: {stats.duration_seconds:.2f}")
    report_lines.append(f"- Events per minute: {stats.events_per_minute:.2f}")
    report_lines.append("")

    report_lines.append("## Key Findings")
    for finding in stats.findings:
        report_lines.append(f"- {finding}")
    report_lines.append("")

    report_lines.append("## Recommendations")
    for recommendation in stats.recommendations:
        report_lines.append(f"- {recommendation}")
    report_lines.append("")

    report_lines.append("## Event Type Counts")
    report_lines.append("| Event Type | Count |")
    report_lines.append("| --- | ---: |")
    for event_type, count in stats.event_type_counts.most_common():
        report_lines.append(f"| {event_type} | {count} |")
    report_lines.append("")

    report_lines.append("## Added And Removed Text")
    report_lines.append(f"- Total added characters: {stats.total_inserted_characters}")
    report_lines.append(f"- Total removed characters: {stats.total_removed_characters}")
    report_lines.append(f"- Net characters: {stats.net_text_characters:+d}")
    if stats.total_removed_characters > 0:
        report_lines.append(f"- Added/removed ratio: {stats.added_removed_ratio:.2f}")
    else:
        report_lines.append("- Added/removed ratio: inf (no removed characters)")
    report_lines.append("")

    report_lines.append("### Top Added Text Fragments")
    report_lines.append("| Preview | Count | Length | Total Chars |")
    report_lines.append("| --- | ---: | ---: | ---: |")
    if stats.top_added_text_rows:
        for row in stats.top_added_text_rows:
            report_lines.append(
                f"| {row['text_preview']} | {row['count']} | {row['text_length']} | {row['total_characters']} |"
            )
    else:
        report_lines.append("| <none> | 0 | 0 | 0 |")
    report_lines.append("")

    report_lines.append("### Top Removed Text Fragments")
    report_lines.append("| Preview | Count | Length | Total Chars |")
    report_lines.append("| --- | ---: | ---: | ---: |")
    if stats.top_removed_text_rows:
        for row in stats.top_removed_text_rows:
            report_lines.append(
                f"| {row['text_preview']} | {row['count']} | {row['text_length']} | {row['total_characters']} |"
            )
    else:
        report_lines.append("| <none> | 0 | 0 | 0 |")
    report_lines.append("")

    report_lines.append("## Top Documents")
    report_lines.append("| Document | Events | Text Events | Cursor Events | Inserted | Removed |")
    report_lines.append("| --- | ---: | ---: | ---: | ---: | ---: |")
    for row in stats.document_rows[:12]:
        report_lines.append(
            "| "
            f"{row['document_uri']} | {row['events']} | {row['text_events']} | {row['cursor_events']} | "
            f"{row['inserted_length']} | {row['removed_length']} |"
        )
    report_lines.append("")

    if plot_notes:
        report_lines.append("## Plot Notes")
        for note in plot_notes:
            report_lines.append(f"- {note}")
        report_lines.append("")

    if parse_errors:
        report_lines.append("## Parse Errors")
        for error in parse_errors[:25]:
            report_lines.append(f"- {error}")
        if len(parse_errors) > 25:
            report_lines.append(f"- ... and {len(parse_errors) - 25} more")
        report_lines.append("")

    report_path.write_text("\n".join(report_lines), encoding="utf-8")
    return report_path


def print_console_summary(output_dir: Path, report_path: Path, stats: AnalysisStats) -> None:
    print("Analysis complete")
    print(f"Output directory: {output_dir}")
    print(f"Report: {report_path}")
    print(f"Total events: {stats.total_events}")
    print(f"Events/minute: {stats.events_per_minute:.2f}")
    print(f"Estimated growth MB/hour: {stats.estimated_mb_per_hour:.2f}")
    print(f"Self-tracking ratio: {stats.self_tracking_ratio:.1%}")


def main() -> int:
    args = parse_args()
    input_file = Path(args.input).resolve()
    output_dir = Path(args.output).resolve()

    if not input_file.exists():
        raise FileNotFoundError(f"Input file not found: {input_file}")

    events, parse_errors = load_events(input_file)
    if not events:
        raise RuntimeError("No events parsed from input file")

    stats = build_analysis(events, parse_errors)

    output_dir.mkdir(parents=True, exist_ok=True)
    generate_tables(output_dir, stats)

    plot_notes = generate_plots(output_dir, stats, events, args.top_documents)

    report_path = generate_markdown_report(
        output_dir=output_dir,
        input_file=input_file,
        stats=stats,
        plot_notes=plot_notes,
        parse_errors=parse_errors,
    )

    print_console_summary(output_dir, report_path, stats)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
