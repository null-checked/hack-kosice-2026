#!/usr/bin/env bash
# One-command startup for the RubberDuck Live backend.
#
# Port/host/LLM model come from `backend/.env` (or the shell environment).
# No values are hardcoded here — change `.env` and both this script and the
# running uvicorn pick up the new values. See `.env.example` for the list.
set -euo pipefail
cd "$(dirname "$0")"

# Source .env if present. `set -a` auto-exports every var parsed.
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

HOST="${APP_HOST:-127.0.0.1}"
PORT="${APP_PORT:-8765}"

printf "backend: http://%s:%s  provider=%s model=%s\n" \
  "$HOST" "$PORT" "${LLM_PROVIDER:-openai}" "${OPENAI_MODEL:-gpt-4o-mini}"

exec uvicorn src.main:app --host "$HOST" --port "$PORT" --reload "$@"
