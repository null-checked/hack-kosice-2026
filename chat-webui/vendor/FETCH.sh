#!/usr/bin/env bash
# Re-fetch vendored libraries for chat-webui. Pinned versions.
# Run from repo root:  bash chat-webui/vendor/FETCH.sh
#
# Libraries:
#   - marked        12.0.2   (MIT)       — Markdown parser
#   - DOMPurify     3.0.11   (MPL-2.0)   — HTML sanitizer
#   - highlight.js  11.9.0   (BSD-3)     — Syntax highlighter + github-dark theme
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

fetch() {
  local url="$1" out="$2"
  echo "→ $out"
  curl -sfL --max-time 30 -o "$HERE/$out" "$url"
}

fetch "https://cdn.jsdelivr.net/npm/marked@12.0.2/marked.min.js"                       marked.min.js
fetch "https://cdn.jsdelivr.net/npm/dompurify@3.0.11/dist/purify.min.js"               dompurify.min.js
fetch "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"    highlight.min.js
fetch "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css" highlight.css

echo
echo "Sizes:"
( cd "$HERE" && wc -c marked.min.js dompurify.min.js highlight.min.js highlight.css )
echo "✓ vendor fetched"
