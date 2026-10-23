#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
run_id=${1:?Usage: cleanup-load.sh RUN_ID [compose-file]}
compose_file=${2:-compose.yaml}
[[ "$run_id" =~ ^[a-z0-9]{1,12}$ ]] || { echo 'Invalid RUN_ID' >&2; exit 2; }
# Both a private prefix and the synthetic target must match. No API deletion is added.
docker compose -f "$compose_file" exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v run_id="$1"' sh "$run_id" <<'SQL'
DELETE FROM links
WHERE code ~ ('^load_' || :'run_id' || '_[0-9]+$')
  AND target_url ~ ('^https://example[.]invalid/load/' || :'run_id' || '/[0-9]+$');
SQL
# Test cache entries may remain for at most the configured positive TTL.
