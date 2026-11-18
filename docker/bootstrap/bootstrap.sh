#!/bin/sh
# One-shot bootstrap for the Datadog Logs Docker suite (sdk-kit SPEC §8). POSIX sh, idempotent.
#
# fakeintake has no users, tokens or indexes, so the bootstrap only:
#   1. waits for /fakeintake/health,
#   2. makes POST /api/v2/logs answer like real Datadog: HTTP 202 with body {} (fakeintake's default
#      is 200 {"errors":[]}); the override keeps payload capture on,
#   3. makes GET /api/v1/validate answer {"valid":true} (official response shape; static stub, it
#      accepts every key - only the live job proves a key is valid),
#   4. writes placeholder credentials and endpoints to /bootstrap/env.
# It never flushes payloads, so re-running it during a test cannot delete captured data.
# Never prints secret values (there are none: the keys are fixed test-only placeholders).
set -eu

: "${SERVICE_URL:?SERVICE_URL is not set}"
BOOTSTRAP_ENV=${BOOTSTRAP_ENV:-/bootstrap/env}
log() { printf 'bootstrap: %s\n' "$*" >&2; }
fail() { log "error: $*"; exit 1; }

# 1. wait for health (compose already gates on the healthcheck; this covers manual runs)
i=0
until curl -fsS -o /dev/null "$SERVICE_URL/fakeintake/health"; do
  i=$((i + 1)); [ "$i" -lt 60 ] || fail "fakeintake not healthy after 60s"; sleep 1
done

# configure_override METHOD PATH STATUS BODY_JSON
#   fakeintake expects the body as base64 ([]byte in Go JSON). Overrides replace each other per
#   (method, path), so calling this again is idempotent.
configure_override() {
  b64=$(printf '%s' "$4" | base64 | tr -d '\n')
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
    --data "{\"method\":\"$1\",\"endpoint\":\"$2\",\"status_code\":$3,\"content_type\":\"application/json\",\"body\":\"$b64\"}" \
    "$SERVICE_URL/fakeintake/configure/override")
  [ "$code" = 200 ] || fail "override $1 $2 returned HTTP $code"
}

# 2 + 3
configure_override POST /api/v2/logs 202 '{}'
configure_override GET /api/v1/validate 200 '{"valid":true}'

# self-check: the intake now answers 202 {} (this probe is captured; it is a valid empty array,
# so consumers that count records see 0 records from it)
resp=$(curl -sS -w ' %{http_code}' -X POST -H 'Content-Type: application/json' \
  -H 'DD-API-KEY: grow-test-only-dd-api-key' --data '[]' "$SERVICE_URL/api/v2/logs")
[ "$resp" = "{} 202" ] || fail "intake override not active (got: $resp)"

# 4. env file, written atomically (KEY=value, values taken literally)
tmp="$BOOTSTRAP_ENV.tmp.$$"
mkdir -p "$(dirname "$BOOTSTRAP_ENV")"
cat > "$tmp" <<ENV
# written by datadog-logs bootstrap; test-only placeholders, fakeintake accepts any key
DD_API_KEY=grow-test-only-dd-api-key
DD_APP_KEY=grow-test-only-dd-app-key
DD_SITE=datadoghq.com
GROW_DATADOG_LOGS_URL=$SERVICE_URL
GROW_DATADOG_LOGS_INTAKE_URL=$SERVICE_URL
GROW_DATADOG_LOGS_API_URL=$SERVICE_URL
FAKEINTAKE_URL=$SERVICE_URL
FAKEINTAKE_LOGS_ENDPOINT=/api/v2/logs
ENV
chmod 0644 "$tmp"
mv "$tmp" "$BOOTSTRAP_ENV"
log "ok: intake answers 202 {}, validate stub installed, $(grep -c '=' "$BOOTSTRAP_ENV") keys in $BOOTSTRAP_ENV"
