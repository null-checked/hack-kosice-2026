#!/usr/bin/env bash
# One-command startup for the RubberDuck Live backend.
# Binds 127.0.0.1:8765 per ARCHITECTURE.md §10.
set -euo pipefail
cd "$(dirname "$0")"
exec uvicorn src.main:app --host 127.0.0.1 --port 8765 --reload "$@"
