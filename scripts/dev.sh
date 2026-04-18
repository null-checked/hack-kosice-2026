#!/usr/bin/env bash
# 🦆 RubberDuck Live dev orchestrator.
# Starts backend + dashboard in parallel with coloured per-service prefixes.
# Ctrl-C stops both cleanly via a trap.
#
# Usage: scripts/dev.sh [mock|live]
#   mock (default) — backend runs with DEMO_MOCK=1 (canned fixtures)
#   live           — backend runs with DEMO_MOCK=0 (requires Ollama on :11434)

set -euo pipefail
MODE="${1:-mock}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Source backend/.env so host/port/model come from the single source of
# truth. If the user bumps APP_PORT there, this script picks it up.
if [ -f backend/.env ]; then
  set -a
  # shellcheck disable=SC1091
  . backend/.env
  set +a
fi
HOST="${APP_HOST:-127.0.0.1}"
PORT="${APP_PORT:-8765}"
DASH_PORT="${DASHBOARD_PORT:-3000}"

# Colours (loop-safe on dumb terminals).
c_reset=$'\033[0m'
c_bold=$'\033[1m'
c_dim=$'\033[2m'
c_be=$'\033[38;5;75m'   # backend: cyan-blue
c_da=$'\033[38;5;213m'  # dashboard: magenta-pink
c_ok=$'\033[38;5;42m'
c_warn=$'\033[38;5;214m'

mock_flag=1
if [ "$MODE" = "live" ]; then mock_flag=0; fi

printf "%s🦆 RubberDuck Live dev%s  mode=%s%s%s\n" \
  "$c_bold" "$c_reset" "$c_ok" "$MODE" "$c_reset"
printf "%sbackend:%s http://%s:%s   %sdashboard:%s http://localhost:%s\n\n" \
  "$c_be" "$c_reset" "$HOST" "$PORT" "$c_da" "$c_reset" "$DASH_PORT"

# Port guard (cross-platform via scripts/kill_port.py --check).
for p in "$PORT" "$DASH_PORT"; do
  if python scripts/kill_port.py --check "$p"; then
    printf "%s✗%s port %s already in use. Run \`make stop\` first.\n" \
      "$c_warn" "$c_reset" "$p"
    exit 1
  fi
done

pids=()
trap 'trap - INT TERM; printf "\n%sstopping…%s\n" "$c_dim" "$c_reset"; kill "${pids[@]}" 2>/dev/null || true; wait 2>/dev/null || true; exit 0' INT TERM

# --- backend ---
(
  cd backend
  DEMO_MOCK=$mock_flag exec uv run uvicorn src.main:app \
    --host "$HOST" --port "$PORT" 2>&1 \
  | while IFS= read -r line; do printf "%s[backend]%s %s\n" "$c_be" "$c_reset" "$line"; done
) &
pids+=($!)

# --- dashboard ---
(
  cd dashboard
  exec npm run dev --silent 2>&1 \
  | while IFS= read -r line; do printf "%s[dashboard]%s %s\n" "$c_da" "$c_reset" "$line"; done
) &
pids+=($!)

wait
