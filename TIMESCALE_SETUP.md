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
6. Configure **compression** (chunks older than 7 days are compressed).
7. Configure **data retention** for both hypertables
   (chunks older than 90 days are dropped automatically).

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

| Table                | Compress after | Retain raw data |
|----------------------|---------------|-----------------|
| `skin_price_history` | 7 days        | 90 days         |
| `tradeup_snapshots`  | 7 days        | 90 days         |

### Compression settings

```
skin_price_history:
  compress_segmentby = 'skin_id, wear_id'   -- skip unrelated per-skin segments
  compress_orderby   = 'recorded_at DESC'   -- optimal delta-compression

tradeup_snapshots:
  compress_segmentby = 'tradeup_id'         -- skip unrelated per-tradeup segments
  compress_orderby   = 'snapshot_time DESC'
```

### Changing policies at runtime

No migration is required — use the TimescaleDB SQL functions directly:

```sql
-- Change the compression schedule (e.g., compress after 14 days):
SELECT alter_compression_policy('skin_price_history',  compress_after => INTERVAL '14 days');
SELECT alter_compression_policy('tradeup_snapshots',   compress_after => INTERVAL '14 days');

-- Change the retention window (e.g., keep 180 days of raw data):
SELECT alter_retention_policy('skin_price_history',    drop_after => INTERVAL '180 days');
SELECT alter_retention_policy('tradeup_snapshots',     drop_after => INTERVAL '180 days');

-- Remove a policy entirely:
SELECT remove_compression_policy('skin_price_history');
SELECT remove_retention_policy('skin_price_history');
```

**Constraint:** the retention window must always be ≥ the compression
window, otherwise TimescaleDB will refuse to compress chunks before they
can be dropped.

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
