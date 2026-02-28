-- ============================================================
-- Migration 002 (dev path): Drop and rebuild history tables
-- ============================================================
-- WARNING: This script drops ALL historical price and
-- trade-up snapshot data.  Use only in development environments
-- where that data is not valuable.
--
-- After running this script, restart the application.
-- The application startup will recreate both tables with the
-- correct timestamptz schema and re-register them as
-- TimescaleDB hypertables automatically.
-- ============================================================

DROP TABLE IF EXISTS skin_price_history  CASCADE;
DROP TABLE IF EXISTS tradeup_snapshots   CASCADE;
