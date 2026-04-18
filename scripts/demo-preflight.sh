#!/usr/bin/env bash
# 🦆 RubberDuck Live — demo pre-flight checklist (10 steps).
# Run this 5 minutes before judging. Every check prints ✓ or ✗.

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

c_reset=$'\033[0m'
c_bold=$'\033[1m'
c_ok=$'\033[38;5;42m'
c_bad=$'\033[38;5;196m'
c_warn=$'\033[38;5;214m'
c_dim=$'\033[2m'

pass=0
fail=0
warn=0
step=0

check() {
  local label="$1"; shift
  step=$((step + 1))
  if "$@" >/dev/null 2>&1; then
    printf "  %s✓%s %2d. %s\n" "$c_ok" "$c_reset" "$step" "$label"
    pass=$((pass + 1))
  else
    printf "  %s✗%s %2d. %s\n" "$c_bad" "$c_reset" "$step" "$label"
    fail=$((fail + 1))
  fi
}

warn_if() {
  local label="$1"; shift
  step=$((step + 1))
  if "$@" >/dev/null 2>&1; then
    printf "  %s✓%s %2d. %s\n" "$c_ok" "$c_reset" "$step" "$label"
    pass=$((pass + 1))
  else
    printf "  %s!%s %2d. %s %s(warning, not fatal)%s\n" \
      "$c_warn" "$c_reset" "$step" "$label" "$c_dim" "$c_reset"
    warn=$((warn + 1))
  fi
}

# Source backend/.env for APP_PORT / APP_HOST / DASHBOARD_PORT so preflight
# checks agree with the actual runtime config.
if [ -f backend/.env ]; then
  set -a
  # shellcheck disable=SC1091
  . backend/.env
  set +a
fi
BACKEND_PORT="${APP_PORT:-8765}"
DASHBOARD_PORT="${DASHBOARD_PORT:-3000}"

printf "%s🦆 RubberDuck Live — demo pre-flight%s (backend=:%s dashboard=:%s)\n\n" \
  "$c_bold" "$c_reset" "$BACKEND_PORT" "$DASHBOARD_PORT"

# 1-3. LLM provider ready. OpenAI (default) is the PRIMARY gate; Ollama is
#      accepted as an alternative so teams demoing the offline path still
#      pass. Either one makes check #1 green.
openai_ok=0
# Pull backend/.env if present so the check sees OPENAI_API_KEY without exporting it.
if [ -f backend/.env ]; then
  # shellcheck disable=SC1091
  set -a && . backend/.env && set +a
fi
if [ -n "${OPENAI_API_KEY:-}" ]; then
  openai_ok=1
fi

ollama_ok=0
if command -v ollama >/dev/null 2>&1 \
   && curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1 \
   && ollama list 2>/dev/null | grep -q "qwen2.5-coder:7b"; then
  ollama_ok=1
fi

check "LLM provider configured (OPENAI_API_KEY set — default — OR local Ollama ready)" \
  bash -c "[ $openai_ok -eq 1 ] || [ $ollama_ok -eq 1 ]"

if [ "$openai_ok" -eq 1 ]; then
  check "OPENAI_API_KEY non-empty in backend/.env (primary provider)" \
    bash -c '[ -n "${OPENAI_API_KEY:-}" ]'
  if [ "$ollama_ok" -eq 1 ]; then
    check "Ollama fallback available (bonus: qwen2.5-coder:7b pulled)" \
      bash -c 'ollama list 2>/dev/null | grep -q "qwen2.5-coder:7b"'
  else
    warn_if "Ollama fallback available (optional when OpenAI is primary)" \
      bash -c 'ollama list 2>/dev/null | grep -q "qwen2.5-coder:7b"'
  fi
else
  # Offline-only demo path: Ollama is the only provider.
  check "Ollama daemon reachable (127.0.0.1:11434) — primary when no OpenAI key" \
    curl -fsS http://127.0.0.1:11434/api/tags
  check "qwen2.5-coder:7b model pulled" \
    bash -c 'ollama list 2>/dev/null | grep -q "qwen2.5-coder:7b"'
fi

# 4. Port $BACKEND_PORT free (cross-platform via scripts/kill_port.py --check).
check "Port $BACKEND_PORT free for backend" \
  bash -c "! python scripts/kill_port.py --check $BACKEND_PORT"

# 5. Port $DASHBOARD_PORT free
check "Port $DASHBOARD_PORT free for dashboard" \
  bash -c "! python scripts/kill_port.py --check $DASHBOARD_PORT"

# 6. Plugin zip present
check "Plugin zip built (plugin/build/distributions/*.zip)" \
  bash -c 'ls plugin/build/distributions/rubberduck-live-*.zip 2>/dev/null | head -1 | grep -q zip'

# 7. Backend deps installed
check "Backend venv ready (uv lockfile)" test -f backend/uv.lock -o -f backend/.venv/bin/python

# 8. Dashboard deps installed
check "Dashboard node_modules present" test -d dashboard/node_modules

# 9. docs/assets exist
warn_if "docs/assets/ exists (record GIFs here)" test -d docs/assets

# 10. git clean? (warn-only)
warn_if "git working tree clean" bash -c 'git diff --quiet && git diff --cached --quiet'

printf "\n  %s%d passed%s · %s%d failed%s · %s%d warnings%s\n\n" \
  "$c_ok" "$pass" "$c_reset" \
  "$c_bad" "$fail" "$c_reset" \
  "$c_warn" "$warn" "$c_reset"

if [ "$fail" -eq 0 ]; then
  printf "%s🎬  You are cleared for demo. Deep breath. Go.%s\n" "$c_ok" "$c_reset"
  exit 0
else
  printf "%s🛑  Fix the failed checks before stepping in front of judges.%s\n" "$c_bad" "$c_reset"
  exit 1
fi
