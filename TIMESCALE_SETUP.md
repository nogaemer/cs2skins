# TimescaleDB Setup Guide

This document describes how to run the cs2skins application locally with
PostgreSQL + TimescaleDB.

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and
  [Docker Compose](https://docs.docker.com/compose/install/) installed

## Starting the Database

```bash
# Start TimescaleDB in the background
docker compose up -d

# Verify the container is healthy
docker compose ps
```

The container will be available at `localhost:5432` with:

| Setting  | Default      |
|----------|--------------|
| Database | `cs2skins`   |
| Username | `postgres`   |
| Password | `postgres`   |

Override credentials via environment variables (or a `.env` file):

```bash
export DB_USERNAME=myuser
export DB_PASSWORD=mysecret
docker compose up -d
```

## Starting the Application

```bash
mvn spring-boot:run
```

On first startup, `SkinDatabaseInitializer` will:

1. Create all reference tables (`collections`, `weapons`, `rarities`,
   `wear_conditions`, `skins`).
2. Create the price tables (`skin_prices_current`, `skin_price_history`).
3. Create the trade-up tables (`tradeups_master`, `tradeups_current`,
   `tradeup_snapshots`, `tradeup_inputs`, `tradeup_outputs`).
4. Enable the `timescaledb` extension.
5. Convert `skin_price_history` and `tradeup_snapshots` to
   **hypertables** partitioned by week.
6. Configure **compression** (chunks older than 6 months are compressed).
7. Configure **half-density thinning** for both hypertables
   (every second row older than 2 years is deleted weekly, preserving
   the time-series shape at 50 % resolution).

If TimescaleDB is not available (e.g., plain PostgreSQL), setup step 4–7
will be skipped with a warning and the application will continue running
using regular tables.

## Schema Overview

### Reference / Master Tables (mostly static)

| Table                | Purpose                                   |
|----------------------|-------------------------------------------|
| `collections`        | CS2 skin collections                      |
| `weapons`            | Weapon types                              |
| `rarities`           | Rarity tiers with colour codes            |
| `wear_conditions`    | Wear condition identifiers                |
| `skins`              | Individual skins with float caps          |

### Price Tables

| Table                  | Purpose                                          |
|------------------------|--------------------------------------------------|
| `skin_prices_current`  | Latest price snapshot per skin+wear              |
| `skin_price_history`   | **Hypertable** – full price history (time-series)|

### Trade-Up Tables

| Table               | Purpose                                             |
|---------------------|-----------------------------------------------------|
| `tradeups_master`   | Unique trade-up definition (collections, rarity, …) |
| `tradeups_current`  | Latest ROI/profit snapshot per master trade-up      |
| `tradeup_snapshots` | **Hypertable** – full metrics history               |
| `tradeup_inputs`    | Input skins for each master trade-up                |
| `tradeup_outputs`   | Possible output skins for each master trade-up      |

## Time-Series Columns

Both hypertables use `TIMESTAMPTZ` columns as their time dimension.
TimescaleDB chunk intervals are set to 1 week (`INTERVAL '7 days'`).

| Table                | Time column    | Type         |
|----------------------|----------------|--------------|
| `skin_price_history` | `recorded_at`  | `TIMESTAMPTZ`|
| `tradeup_snapshots`  | `snapshot_time`| `TIMESTAMPTZ`|

## Compression and Retention Policies

Both hypertables have automatic background policies (applied by
`SkinDatabaseInitializer` on startup and documented in
`migrations/003_compression_retention.sql`):

| Table                | Compress after | Thinning (half density after) |
|----------------------|---------------|-------------------------------|
| `skin_price_history` | 6 months      | 2 years                       |
| `tradeup_snapshots`  | 6 months      | 2 years                       |

### Compression settings

```
skin_price_history:
  compress_segmentby = 'skin_id, wear_id'   -- skip unrelated per-skin segments
  compress_orderby   = 'recorded_at DESC'   -- optimal delta-compression

tradeup_snapshots:
  compress_segmentby = 'tradeup_id'         -- skip unrelated per-tradeup segments
  compress_orderby   = 'snapshot_time DESC'
```

### Thinning policy (half density)

Rather than deleting all data beyond a hard cutoff, a weekly background
procedure (`thin_out_old_data`) reduces density for data older than 2 years
by removing temporal duplicates within 1-week windows.

For each group (`skin_id + wear_id` in `skin_price_history`,
`tradeup_id` in `tradeup_snapshots`), rows older than 2 years are bucketed
into 1-week windows.  Only the **earliest** row in each bucket is kept;
all later rows in the same bucket are deleted.

**Example:** if there are price readings on Monday the 8th and Tuesday the 9th
of the same week, both fall within the same 1-week bucket.  Monday 8th is
kept; Tuesday 9th is removed.  The result is at most one reading per week per
skin+wear instead of one per day.

Because data older than 6 months is already compressed, the procedure
automatically decompresses affected chunks before deleting rows, then
re-compresses them afterwards.

### Changing policies at runtime

**Compression schedule** (no migration needed):

```sql
SELECT alter_compression_policy('skin_price_history',  compress_after => INTERVAL '3 months');
SELECT alter_compression_policy('tradeup_snapshots',   compress_after => INTERVAL '3 months');
```

**Thinning threshold** (requires recreating the procedure):

```sql
-- Remove existing job first
SELECT delete_job(job_id)
FROM   timescaledb_information.jobs
WHERE  proc_name = 'thin_out_old_data';

-- Drop and recreate the procedure with updated INTERVAL values
DROP PROCEDURE thin_out_old_data;
-- Then run the updated CREATE OR REPLACE PROCEDURE block from migrations/003_compression_retention.sql
-- and re-register: SELECT add_job('thin_out_old_data', INTERVAL '1 week');
```

**Remove compression policy entirely:**

```sql
SELECT remove_compression_policy('skin_price_history');
SELECT remove_compression_policy('tradeup_snapshots');
```

## Seeding Data

After the application is running, seed reference data and prices via the
system API:

```bash
# Seed collections, then skins + prices (can be run independently)
curl -X POST http://localhost:8080/api/system/seed/all

# Calculate trade-ups (non-StatTrak)
curl -X POST http://localhost:8080/api/system/calculate

# Calculate trade-ups (StatTrak)
curl -X POST "http://localhost:8080/api/system/calculate?stattrak=true"
```

## Historical Query Endpoints

### Trade-up ROI/profit history

```
GET /api/tradeups/{id}/history?from=<epoch_ms>&to=<epoch_ms>&bucket=<bucket_ms>
```

- `from` / `to`: optional time range in epoch milliseconds (default: last 30 days)
- `bucket`: bucket width in milliseconds (default: `86400000` = 1 day)

### Skin price history

```
GET /api/skins/{skinId}/price-history/{wearId}?from=<epoch_ms>&to=<epoch_ms>
```

- `from` / `to`: optional time range in epoch milliseconds (default: all history)

## Stopping the Database

```bash
docker compose down
# To also remove the data volume:
docker compose down -v
```
