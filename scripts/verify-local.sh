#!/usr/bin/env bash
# Full verification in a dedicated Compose project. Named volumes are never deleted.
set -euo pipefail
cd "$(dirname "$0")/.."
readonly PROJECT=shortlinks-verify
readonly REQUEST_TIMEOUT_SECONDS=10
readonly CONNECT_TIMEOUT_SECONDS=3
readonly HEALTH_CONNECT_TIMEOUT_SECONDS=2
readonly HEALTH_REQUEST_TIMEOUT_SECONDS=3
readonly CONTAINER_START_TIMEOUT_SECONDS=180
readonly REDIS_START_TIMEOUT_SECONDS=60
readonly MAX_PORT=65535
readonly READINESS_ATTEMPTS=60
readonly READINESS_INTERVAL_SECONDS=2
export APP_PORT=${VERIFY_APP_PORT:-18080}
export MANAGEMENT_PORT=${VERIFY_MANAGEMENT_PORT:-19090}
export PUBLIC_BASE_URL="http://127.0.0.1:$APP_PORT"
export CACHE_ENABLED=true
valid_port() {
  [[ "$1" =~ ^[0-9]{1,5}$ ]] && (( 10#$1 >= 1 && 10#$1 <= MAX_PORT ))
}
valid_port "$APP_PORT" && valid_port "$MANAGEMENT_PORT" || {
  echo 'Verification ports must be in 1..65535' >&2
  exit 2
}
(( 10#$APP_PORT != 10#$MANAGEMENT_PORT )) || {
  echo 'Application and management ports must differ' >&2
  exit 2
}
[[ -f .env ]] || { echo 'Prepare .env using .env.example before verification' >&2; exit 2; }
./mvnw -version
# Fail before touching containers if the current process cannot reach Docker.
docker info >/dev/null
docker compose version
./mvnw -B --no-transfer-progress clean verify
compose=(docker compose -p "$PROJECT" -f compose.yaml)
"${compose[@]}" config -q
"${compose[@]}" up -d --build --wait --wait-timeout "$CONTAINER_START_TIMEOUT_SECONDS"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
base="http://127.0.0.1:$APP_PORT"
alias="verify_$(date +%s)_${RANDOM}"
target='https://example.invalid/persistent-verification'
wait_ready() {
  local attempt
  for ((attempt=0;attempt<READINESS_ATTEMPTS;attempt++)); do
    if curl -fsS --connect-timeout "$HEALTH_CONNECT_TIMEOUT_SECONDS" --max-time "$HEALTH_REQUEST_TIMEOUT_SECONDS" \
      "http://127.0.0.1:$MANAGEMENT_PORT/actuator/health/readiness" >/dev/null; then
      return 0
    fi
    sleep "$READINESS_INTERVAL_SECONDS"
  done
  echo 'Readiness check exhausted' >&2
  return 1
}
assert_persisted_link() {
  local status
  status=$(curl --silent --show-error --connect-timeout "$CONNECT_TIMEOUT_SECONDS" \
    --max-time "$REQUEST_TIMEOUT_SECONDS" --dump-header "$work/headers" \
    --output /dev/null --write-out '%{http_code}' "$base/$alias")
  [[ "$status" == 302 ]] || { echo "Expected persisted link, received $status" >&2; return 1; }
  tr -d '\r' < "$work/headers" |
    awk 'tolower($1) == "location:" { sub(/^[^:]+:[[:space:]]*/, ""); print }' |
    grep -Fxq "$target"
}
wait_ready
scripts/smoke.sh "$base"
status=$(curl --silent --show-error --connect-timeout "$CONNECT_TIMEOUT_SECONDS" \
  --max-time "$REQUEST_TIMEOUT_SECONDS" --output /dev/null --write-out '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d "{\"url\":\"$target\",\"alias\":\"$alias\"}" "$base/api/links")
[[ "$status" == 201 ]] || { echo "Cannot create persistence probe: $status" >&2; exit 1; }
assert_persisted_link

# Container recreation with the same named PostgreSQL volume must preserve the link.
"${compose[@]}" down
"${compose[@]}" up -d --no-build --wait --wait-timeout "$CONTAINER_START_TIMEOUT_SECONDS"
wait_ready
assert_persisted_link

# Redis outage, application startup without Redis, and Redis recovery.
"${compose[@]}" stop redis
wait_ready
scripts/smoke.sh "$base"
"${compose[@]}" up -d --no-deps --force-recreate app
wait_ready
assert_persisted_link
scripts/smoke.sh "$base"
"${compose[@]}" up -d --no-deps --wait --wait-timeout "$REDIS_START_TIMEOUT_SECONDS" redis
recovery_alias="vr_${RANDOM}_$(date +%s)"
status=$(curl --silent --show-error --connect-timeout "$CONNECT_TIMEOUT_SECONDS" \
  --max-time "$REQUEST_TIMEOUT_SECONDS" --output /dev/null --write-out '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d "{\"url\":\"$target\",\"alias\":\"$recovery_alias\"}" "$base/api/links")
[[ "$status" == 201 ]] || { echo 'Cannot create Redis recovery probe' >&2; exit 1; }
cache_recovered=false
for ((attempt=0;attempt<READINESS_ATTEMPTS;attempt++)); do
  curl -sS --connect-timeout "$CONNECT_TIMEOUT_SECONDS" --max-time "$REQUEST_TIMEOUT_SECONDS" \
    "$base/$recovery_alias" --output /dev/null
  cached=$("${compose[@]}" exec -T redis sh -c '
    if [ -n "$REDIS_PASSWORD" ]; then export REDISCLI_AUTH="$REDIS_PASSWORD"; fi
    redis-cli --raw GET "$1"
  ' sh "shortlinks:v1:$recovery_alias")
  if [[ "$cached" == "url:$target" ]]; then cache_recovered=true; break; fi
  sleep "$READINESS_INTERVAL_SECONDS"
done
[[ "$cache_recovered" == true ]] || { echo 'Redis cache did not recover' >&2; exit 1; }
scripts/smoke.sh "$base"

# Explicitly disabled cache must work independently of Redis.
"${compose[@]}" stop redis
export CACHE_ENABLED=false
"${compose[@]}" up -d --no-deps --force-recreate app
wait_ready
assert_persisted_link
scripts/smoke.sh "$base"

# Leave this dedicated verification environment running in the normal configuration.
export CACHE_ENABLED=true
"${compose[@]}" up -d --no-deps redis
"${compose[@]}" up -d --no-deps --force-recreate app
wait_ready
"${compose[@]}" ps
echo "Functional verification passed: $base (Compose project: $PROJECT)"
echo 'Load/JFR measurements are a separate step documented in docs/performance-checks.md'
