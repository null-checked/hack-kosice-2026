# Metrics Log Analysis Report

- Input file: D:\Desktop\hack-kosice-2026\test_logs\events.ndjson
- Generated at: 2026-04-18T11:39:34.564382+00:00
- Total events: 69
- Parse errors: 0

## Timeline
- Start: 2026-04-18T11:31:32.510000+00:00
- End: 2026-04-18T11:32:58.141000+00:00
- Duration seconds: 85.63
- Events per minute: 48.35

## Key Findings
- Collected 69 events across 1 session(s) and 4 document(s).
- Average event throughput is 48.35 events/min, with estimated log growth of 2.16 MB/hour at this rate.
- Text-change aggregation: avg changes/event 2.00, avg inserted chars/event 962.0, median inserted chars/event 1090.0, avg removed chars/event 0.5, avg batch duration 197.9 ms.
- Cursor-selection events: 27, unknown kind ratio: 66.7%.
- High self-tracking detected: 39 events (56.5%) target the metrics output file.
- Large text-change events are common: 27/35 (77.1%) inserted >= 200 chars.
- Text batching appears effective (multiple editor changes grouped per event).

## Recommendations
- Exclude the metrics output file from tracking to avoid recursive logging and inflated text-change payload sizes.
- Consider lowering maxRawTextChars or disabling raw text capture for privacy and smaller logs.
- High unknown cursor-kind ratio suggests many non-keyboard interactions; consider separating keyboard and mouse analytics.

## Event Type Counts
| Event Type | Count |
| --- | ---: |
| text-change | 35 |
| cursor-selection | 27 |
| document-open | 4 |
| document-close | 2 |
| session-start | 1 |

## Top Documents
| Document | Events | Text Events | Cursor Events | Inserted | Removed |
| --- | ---: | ---: | ---: | ---: | ---: |
| file:///d%3A/Desktop/test_code_track/.metrics/events.ndjson | 39 | 27 | 9 | 33615 | 0 |
| file:///d%3A/Desktop/test_code_track/test.py | 27 | 8 | 18 | 56 | 19 |
| file:///d%3A/Desktop/test_code_track/test.python | 2 | 0 | 0 | 0 | 0 |
| <no-document> | 1 | 0 | 0 | 0 | 0 |
