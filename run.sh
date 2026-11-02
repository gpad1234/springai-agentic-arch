#!/usr/bin/env bash
# Starts the backend (8080) and the gateway with admin UI (8081). Ctrl+C stops both.
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then set -a; . ./.env; set +a; fi
# A key chosen in the admin UI overrides .env
ACTIVE="${GATEWAY_DATA_DIR:-gateway/gateway-data}/active-key.env"
if [ -f "$ACTIVE" ]; then . "$ACTIVE"; fi
: "${ANTHROPIC_API_KEY:?Set ANTHROPIC_API_KEY in .env (see .env.example)}"

mvn -q spring-boot:run &
BACKEND=$!
mvn -q -f gateway/pom.xml spring-boot:run &
GATEWAY=$!
trap 'kill $BACKEND $GATEWAY 2>/dev/null || true' EXIT INT TERM
echo "Admin UI: http://localhost:8081/admin/   Backend: http://localhost:8080"
wait
