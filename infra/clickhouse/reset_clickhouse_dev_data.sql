TRUNCATE TABLE tradeups.tradeup_snapshot_raw;
TRUNCATE TABLE tradeups.tradeup_outcome_snapshot_raw;
TRUNCATE TABLE tradeups.tradeup_snapshot_latest;
TRUNCATE TABLE tradeups.tradeup_snapshot_rollup_1d;
TRUNCATE TABLE tradeups.item_price_history_raw;

SELECT database, table, sum(rows) AS rows
FROM system.parts
WHERE active AND database = 'tradeups'
GROUP BY database, table
ORDER BY table;