# Metrics Log Analysis Report

- Input file: D:\Desktop\hack-kosice-2026\test_logs\events.ndjson
- Generated at: 2026-04-18T12:01:44.396030+00:00
- Total events: 38
- Parse errors: 0

## Timeline
- Start: 2026-04-18T11:52:58.734000+00:00
- End: 2026-04-18T11:55:23.077000+00:00
- Duration seconds: 144.34
- Events per minute: 15.80

## Key Findings
- Collected 38 events across 1 session(s) and 3 document(s).
- Average event throughput is 15.80 events/min, with estimated log growth of 0.54 MB/hour at this rate.
- Text-change aggregation: avg changes/event 3.67, avg inserted chars/event 4.4, median inserted chars/event 1.0, avg removed chars/event 2.4, avg batch duration 469.1 ms.
- Added/removed text totals: added 40 chars, removed 22 chars, net +18 chars.
- Cursor-selection events: 26, unknown kind ratio: 46.2%.
- Cursor kind 'unknown' means VS Code did not classify the source as keyboard, mouse, or command.
- Text batching appears effective (multiple editor changes grouped per event).

## Recommendations
- High unknown cursor-kind ratio suggests many non-keyboard interactions; consider separating keyboard and mouse analytics.

## Event Type Counts
| Event Type | Count |
| --- | ---: |
| cursor-selection | 26 |
| text-change | 9 |
| session-start | 1 |
| document-open | 1 |
| document-close | 1 |

## Added And Removed Text
- Total added characters: 40
- Total removed characters: 22
- Net characters: +18
- Added/removed ratio: 1.82

### Top Added Text Fragments
| Preview | Count | Length | Total Chars |
| --- | ---: | ---: | ---: |
|   | 1 | 1 | 1 |
| hello world  | 1 | 12 | 12 |
| print(math.sqrt(25)) | 1 | 20 | 20 |
| \r\n | 1 | 2 | 2 |
| a | 1 | 1 | 1 |
| hell | 1 | 4 | 4 |

### Top Removed Text Fragments
| Preview | Count | Length | Total Chars |
| --- | ---: | ---: | ---: |
|   | 2 | 1 | 2 |
| dlrowhello  #\r\n | 1 | 15 | 15 |
| a | 1 | 1 | 1 |
| hell | 1 | 4 | 4 |

## Top Documents
| Document | Events | Text Events | Cursor Events | Inserted | Removed |
| --- | ---: | ---: | ---: | ---: | ---: |
| file:///d%3A/Desktop/test_code_track/test.py | 31 | 7 | 24 | 36 | 18 |
| file:///d%3A/Desktop/test_code_track/test2.py | 6 | 2 | 2 | 4 | 4 |
| <no-document> | 1 | 0 | 0 | 0 | 0 |
