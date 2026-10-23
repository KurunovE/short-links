#!/usr/bin/env bash
set -euo pipefail
readonly CONNECT_TIMEOUT_SECONDS=3
readonly REQUEST_TIMEOUT_SECONDS=10
base=${1:-http://127.0.0.1:8080}
[[ "$base" =~ ^https?:// ]] || { echo 'Expected HTTP base URL' >&2; exit 2; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
alias="smoke_$(date +%s)_${RANDOM}"
request() {
  local expected=$1
  shift
  local actual
  actual=$(curl --silent --show-error --connect-timeout "$CONNECT_TIMEOUT_SECONDS" --max-time "$REQUEST_TIMEOUT_SECONDS" --dump-header "$work/headers" --output "$work/body" --write-out '%{http_code}' "$@")
  [[ "$actual" == "$expected" ]] || { echo "Smoke expected $expected, received $actual" >&2; exit 1; }
}
request 201 -H 'Content-Type: application/json' -d "{\"url\":\"https://example.invalid/smoke-old\",\"alias\":\"$alias\"}" "$base/api/links"
request 302 "$base/$alias"
tr -d '\r' < "$work/headers" | grep -Eiq '^Location: https://example.invalid/smoke-old$'
tr -d '\r' < "$work/headers" | grep -Eiq '^Cache-Control: no-store$'
request 200 -X PUT -H 'Content-Type: application/json' -d '{"url":"https://example.invalid/smoke-new"}' "$base/api/links/$alias"
request 302 "$base/$alias"
tr -d '\r' < "$work/headers" | grep -Eiq '^Location: https://example.invalid/smoke-new$'
request 400 -H 'Content-Type: application/json' -d '{"url":"ftp://example.invalid"}' "$base/api/links"
request 409 -H 'Content-Type: application/json' -d "{\"url\":\"https://example.invalid\",\"alias\":\"$alias\"}" "$base/api/links"
request 404 "$base/missing_${RANDOM}_$(date +%s)"
echo "Smoke passed; test alias retained: $alias"
