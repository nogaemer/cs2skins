<#
.SYNOPSIS
    Truncates regenerable dev data in both Postgres and ClickHouse, so
    optimizeAll() can be re-run from a clean slate to test the rating /
    best_tradeup_by_skin_pair pipeline.

.DESCRIPTION
    Windows/PowerShell equivalent of infra/clickhouse/reset_clickhouse_dev_data.sh,
    extended to also cover Postgres now that tradeup_recipes, calculator_runs,
    and best_tradeup_by_skin_pair all need resetting together for a clean test run.

    By default this truncates only the RUN-derived tables (recipes, snapshots,
    calculator run history) -- NOT the catalog/pricing tables, since those get
    slowly re-populated by PriceIngestionService's live API calls and don't need
    to be wiped just to test a new optimizer run.

    Credentials come from infra/.env, read directly by THIS script (next to it,
    via $PSScriptRoot) rather than relying on the container's own environment.

    IMPORTANT (redesign, replaces two earlier quote-escaping patches): earlier
    versions routed every query through `docker exec <container> sh -c "<script>"`
    so the CONTAINER's shell could expand $POSTGRES_PASSWORD etc. from its own
    environment. That required flattening credentials + SQL into one string
    with embedded quotes, which PowerShell's native-argument marshalling
    mangled when handing that string to docker.exe (embedded " got re-encoded
    as \", which POSIX `sh` doesn't treat as a quote delimiter -- silently
    breaking the quoting protecting the SQL text).

    Now that this script reads infra/.env itself, it can pass credentials and
    SQL as separate, discrete arguments directly to `docker exec` -- no
    intermediate shell, no string-flattening, no quote characters to mangle.
    This matters beyond just fixing the bug: POSTGRES_PASSWORD in this env file
    contains '(', ';', '@', and '=' -- a literal semicolon would have been
    interpreted as a shell command separator if it were ever embedded into a
    `sh -c` string, silently corrupting the command regardless of quoting.
    Passing it as a discrete `-e PGPASSWORD=...` argument (Docker's own arg
    parser, not a shell) sidesteps that entirely: the value is never
    tokenized/parsed for shell metacharacters at all.

.PARAMETER IncludePriceHistory
    Also truncate ClickHouse's item_price_history_raw. Off by default now that
    it's genuinely accumulating time-series data (spread/slippage/price-impact/
    volatility) rather than being an unused writer -- only pass this if you
    specifically want to wipe accumulated history too.

.PARAMETER IncludeCatalogPrices
    Also truncate Postgres's item_current_prices and item_wear_availability.
    Off by default -- forces a full, slow re-ingestion from openskin on the
    next run (seedSkins() + ingestCurrentPrices() repopulate these
    automatically, but that's real API calls, not instant). Only pass this if
    you specifically need to verify ingestion itself, not just the optimizer.

.EXAMPLE
    .\reset_dev_data.ps1
    .\reset_dev_data.ps1 -IncludePriceHistory
    .\reset_dev_data.ps1 -IncludeCatalogPrices -IncludePriceHistory
#>

param(
    [switch]$IncludePriceHistory,
    [switch]$IncludeCatalogPrices
)

$ErrorActionPreference = "Stop"

$PostgresContainer = "cs2-postgres"
$ClickHouseContainer = "cs2-clickhouse"

# --- Load infra/.env, next to this script ---
$EnvFilePath = Join-Path (Split-Path $PSScriptRoot -Parent) ".env"
if (-not (Test-Path $EnvFilePath)) {
    throw "$EnvFilePath not found -- this script expects .env in the same folder as itself."
}

$EnvVars = @{}
Get-Content $EnvFilePath | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    $separatorIndex = $line.IndexOf("=")
    if ($separatorIndex -lt 0) { return }
    $key = $line.Substring(0, $separatorIndex).Trim()
    $value = $line.Substring($separatorIndex + 1).Trim()
    $EnvVars[$key] = $value
}

foreach ($requiredKey in @("POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_DB", "CLICKHOUSE_USER", "CLICKHOUSE_PASSWORD")) {
    if (-not $EnvVars.ContainsKey($requiredKey)) {
        throw "$requiredKey missing from $EnvFilePath"
    }
}

$PostgresUser = $EnvVars["POSTGRES_USER"]
$PostgresPassword = $EnvVars["POSTGRES_PASSWORD"]
$PostgresDb = $EnvVars["POSTGRES_DB"]
$ClickHouseUser = $EnvVars["CLICKHOUSE_USER"]
$ClickHousePassword = $EnvVars["CLICKHOUSE_PASSWORD"]

function Invoke-ClickHouseQuery {
    param([string]$Query)
    # Every value here is its own discrete argument to docker exec -- no
    # intermediate shell, so nothing needs escaping regardless of what
    # characters the password contains.
    docker exec $ClickHouseContainer clickhouse-client `
        --user $ClickHouseUser `
        --password $ClickHousePassword `
        --database tradeups `
        --query $Query
}

function Invoke-PostgresQuery {
    param([string]$Query)
    # -e PGPASSWORD=... sets the env var directly via Docker's own argument
    # parser (KEY=VALUE), not a shell -- safe even though the password
    # contains '(', ';', '@', '='.
    docker exec -e "PGPASSWORD=$PostgresPassword" $PostgresContainer psql `
        -U $PostgresUser `
        -d $PostgresDb `
        -c $Query
}

Write-Host "==> Checking containers are running..." -ForegroundColor Cyan
$running = docker ps --format "{{.Names}}"
if ($running -notcontains $PostgresContainer) { throw "$PostgresContainer is not running -- start it with docker compose up -d first." }
if ($running -notcontains $ClickHouseContainer) { throw "$ClickHouseContainer is not running -- start it with docker compose up -d first." }

Write-Host "==> Truncating Postgres run-derived tables (calculator_runs, tradeup_recipes CASCADE)..." -ForegroundColor Cyan
Invoke-PostgresQuery "TRUNCATE TABLE calculator_runs, tradeup_recipes CASCADE;"

if ($IncludeCatalogPrices) {
    Write-Host "==> [-IncludeCatalogPrices] Truncating item_current_prices, item_wear_availability..." -ForegroundColor Yellow
    Invoke-PostgresQuery "TRUNCATE TABLE item_current_prices, item_wear_availability;"
}

Write-Host "==> Truncating ClickHouse snapshot tables..." -ForegroundColor Cyan
$clickhouseTables = @(
    "tradeups.tradeup_snapshot_raw",
    "tradeups.tradeup_outcome_snapshot_raw",
    "tradeups.tradeup_snapshot_latest",
    "tradeups.tradeup_snapshot_rollup_1d"
)
foreach ($table in $clickhouseTables) {
    Write-Host "   Truncating $table..."
    Invoke-ClickHouseQuery "TRUNCATE TABLE $table"
}

if ($IncludePriceHistory) {
    Write-Host "==> [-IncludePriceHistory] Truncating item_price_history_raw..." -ForegroundColor Yellow
    Invoke-ClickHouseQuery "TRUNCATE TABLE tradeups.item_price_history_raw"
}

Write-Host ""
Write-Host "==> Done. Verifying row counts (all reset tables should be 0):" -ForegroundColor Green

Write-Host ""
Write-Host "-- Postgres --" -ForegroundColor Green
# Plain single-quoted SQL string literals are safe again now that there's no
# shell in between to fight over quote characters.
Invoke-PostgresQuery "SELECT 'calculator_runs' AS table, COUNT(*) FROM calculator_runs UNION ALL SELECT 'tradeup_recipes', COUNT(*) FROM tradeup_recipes UNION ALL SELECT 'tradeup_recipe_outcomes', COUNT(*) FROM tradeup_recipe_outcomes UNION ALL SELECT 'best_tradeup_by_skin_pair', COUNT(*) FROM best_tradeup_by_skin_pair UNION ALL SELECT 'ingest_batches', COUNT(*) FROM ingest_batches;"

Write-Host ""
Write-Host "-- ClickHouse --" -ForegroundColor Green
Invoke-ClickHouseQuery "SELECT database, table, sum(rows) AS rows FROM system.parts WHERE active AND database = 'tradeups' GROUP BY database, table ORDER BY table FORMAT Pretty"

Write-Host ""
Write-Host "Ready for a fresh run: mvn exec:java" -ForegroundColor Green
