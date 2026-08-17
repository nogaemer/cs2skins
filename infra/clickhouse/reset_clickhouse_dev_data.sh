#!/usr/bin/env bash
# Drops all calculated dev data from ClickHouse ahead of the deterministic-id
# migration. Safe to re-run (TRUNCATE is idempotent). Does NOT touch table
# schemas -- structure from 002_tables.sql stays exactly as-is.
#
# tradeup_snapshot_latest / tradeup_snapshot_rollup_1d are materialized-view
# TARGET tables -- truncating tradeup_snapshot_raw does NOT clear them
# automatically, they must be truncated explicitly too.

set -euo pipefail

TABLES=(
  "tradeups.tradeup_snapshot_raw"
  "tradeups.tradeup_outcome_snapshot_raw"
  "tradeups.tradeup_snapshot_latest"
  "tradeups.tradeup_snapshot_rollup_1d"
  "tradeups.item_price_history_raw"   # unused writer today, truncated for cleanliness
)

for table in "${TABLES[@]}"; do
  echo "Truncating ${table}..."
  docker exec -it cs2-clickhouse clickhouse-client --query "TRUNCATE TABLE ${table}"
done

echo "Done. Verifying row counts (all should be 0):"
docker exec -it cs2-clickhouse clickhouse-client --query "
SELECT database, table, sum(rows) AS rows
FROM system.parts
WHERE active AND database = 'tradeups'
GROUP BY database, table
ORDER BY table
FORMAT Pretty
"
