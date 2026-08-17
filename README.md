# CS2 Trade‑Up Calculator

[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org)
[![ClickHouse](https://img.shields.io/badge/ClickHouse-latest-F7DF1E?logo=clickhouse&logoColor=black)](https://clickhouse.com)
[![License](https://img.shields.io/badge/License-Proprietary-red)](LICENSE)

> **A high‑performance CS2 skin trade‑up calculator that ingests live market data, evaluates millions of trade‑up contracts, scores them with a composite rating, and serves the results through a modern REST API.**

Built for data‑driven traders, developers, and CS2 enthusiasts who want to understand the actual profitability of trade‑up contracts — not just guess.

---

## Table of Contents

- [What is a CS2 Trade‑Up?](#what-is-a-cs2-trade-up)
- [Features](#features)
- [How the System Works](#how-the-system-works)
  - [1. Catalog Seeding](#1-catalog-seeding)
  - [2. Price Ingestion](#2-price-ingestion)
  - [3. Trade‑Up Optimization](#3-trade-up-optimization)
  - [4. Best‑Per‑Pair Refresh](#4-best-per-pair-refresh)
- [The Math Behind the Scenes](#the-math-behind-the-scenes)
  - [Input Float and Wear](#input-float-and-wear)
  - [Output Float from Input Floats](#output-float-from-input-floats)
  - [The Cost of a Specific Float (Steam Fees)](#the-cost-of-a-specific-float-steam-fees)
  - [Outcome Probabilities](#outcome-probabilities)
  - [Expected Value, Profit, and ROI](#expected-value-profit-and-roi)
  - [Profit Chance](#profit-chance)
  - [Composite Rating](#composite-rating)
    - [ROI Score](#roi-score)
    - [Profit Chance Score](#profit-chance-score)
    - [Execution Cost Score](#execution-cost-score)
    - [Volatility Score](#volatility-score)
    - [Liquidity Score](#liquidity-score)
    - [Depth Gate](#depth-gate)
    - [Final Rating](#final-rating)
- [Architecture](#architecture)
- [API Overview](#api-overview)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Scheduling](#scheduling)
- [Security](#security)
- [Project Structure](#project-structure)
- [Testing](#testing)
- [Data Sources](#data-sources)
- [License](#license)
- [Acknowledgments](#acknowledgments)

---

## What is a CS2 Trade‑Up?

In **Counter‑Strike 2**, a **trade‑up contract** lets a player exchange **10 weapon skins of one rarity tier** for **one random skin from the next higher tier**.

For example:

- `10 × Mil‑Spec` → `1 × Restricted`
- `10 × Restricted` → `1 × Classified`
- `10 × Classified` → `1 × Covert`

The **collection** of each input skin determines the possible output pool. If you use 7 skins from Collection A and 3 from Collection B, every output skin from both collections is a possible outcome.

This project answers:

1. Which input skins should I buy?
2. Which float / wear condition should each input have?
3. What is the expected profit?
4. How risky is the contract?
5. Which contract is the best for a given pair of skins?

---

## Features

- **Catalog ingestion** from [CSGO‑API](https://github.com/ByMykel/CSGO-API)
- **Live price ingestion** from [openskin.dev](https://openskin.dev)
- **Market microstructure enrichment**:
  - Bid/ask spread
  - Slippage
  - Price impact at 5‑ and 10‑unit depth
  - 1‑day and 7‑day volatility
  - Liquidity score
- **Probabilistic trade‑up optimizer**
  - Millions of recipes evaluated per run
  - Optimal input float selection
  - Expected value, profit, ROI, and profit chance
- **Composite rating** (0–100)
- **Time‑series persistence** in ClickHouse
- **Best‑per‑pair lookup** table
- **REST API** for collections, skins, trade‑ups, and admin
- **Scheduled ingestion and optimization**
- **HTTP Basic auth** for admin endpoints

---

## How the System Works

The project consists of four main stages.

### 1. Catalog Seeding

```
CSGO‑API  →  PostgreSQL
```

`PostgresSeedService` downloads collections, weapons, rarities, and skins from the public CSGO‑API and stores them in PostgreSQL.

All catalog IDs are **deterministic** — derived from a SHA‑256 hash of the external ID. This ensures stability across database resets and avoids cross‑database drift.

### 2. Price Ingestion

```
openskin.dev  →  PostgreSQL + ClickHouse
```

Two independent jobs:

| Job | Frequency | Purpose |
|---|---|---|
| `ingestCurrentPrices()` | Daily | Fast batch endpoint, fetches bid/ask/median/volume/liquidity for every item/wear combination. |
| `ingestSteamMetrics()` | Daily, before optimization | Slow single‑item endpoint, fetches spread/slippage/price impact/volatility. |

Current prices live in PostgreSQL; historical snapshots are appended to ClickHouse.

### 3. Trade‑Up Optimization

```
PostgreSQL + ClickHouse input  →  Optimizer  →  PostgreSQL + ClickHouse output
```

`TradeUpOptimizer` enumerates:

- Every collection pair
- Every rarity transition
- Every possible output float
- Every input count split from `1/9` to `9/1`
- Every candidate skin pair

For each candidate, it solves for the cheapest input float combination that still produces the desired output float.

The result is a **trade‑up recipe** containing:

- Two input skins and their counts
- Input float values
- Output probability distribution
- Cost, profit, ROI, and profit chance
- Composite rating

Only the best candidate for each `(skin pair, count split, wear bucket)` group is kept.

### 4. Best‑Per‑Pair Refresh

After a successful run, the system recomputes the **best‑rated recipe for every skin pair** and stores it in a dedicated PostgreSQL table.

This enables instant lookups like:

> “What is the best trade‑up using skin A and skin B?”

without scanning millions of ClickHouse rows.

---

## The Math Behind the Scenes

This section explains the core calculations in plain language, with formulas where helpful.

### Input Float and Wear

Every CS2 skin has a **float value** between `0.00` and `1.00`.

Float determines the visible wear:

| Wear | Float range |
|---|---|
| Factory New | `0.00 – 0.07` |
| Minimal Wear | `0.07 – 0.15` |
| Field‑Tested | `0.15 – 0.38` |
| Well‑Worn | `0.38 – 0.45` |
| Battle‑Scarred | `0.45 – 1.00` |

Lower float skins are generally more expensive, even within the same wear bucket.

### Output Float from Input Floats

The output float of a trade‑up is the **arithmetic mean of the 10 input floats**:

$$
f_{\text{out}} = \frac{1}{10}\sum_{i=1}^{10} f_i
$$

For example, 7 skins at float `0.20` and 3 skins at `0.40` yield:

$$
f_{\text{out}} = \frac{7 \times 0.20 + 3 \times 0.40}{10} = 0.26
$$

### The Cost of a Specific Float (Steam Fees)

Inside a wear bucket, float values are not uniform. The probability of a skin falling within a **specific sub‑range** is proportional to that range’s width.

Suppose you need an input skin with a very low float (e.g., `f ≤ 0.02` inside the Factory New bucket, which spans `0.00–0.07`). Only a small fraction of all Factory New skins satisfy that requirement.

Let:

- $p$ = probability that a randomly chosen skin from the wear bucket falls within the desired sub‑range
- $P_{\text{base}}$ = market price of a generic skin in that wear bucket

On average, to acquire one **usable** skin, you must buy $\frac{1}{p}$ skins. The remaining $\frac{1}{p} - 1$ are surplus and must be sold again.

When you sell on the Steam Community Market, **Valve charges a 13% transaction fee** on the sale price. So every surplus skin effectively loses 13% of its value.

The **effective cost per usable skin** becomes:

$$
P_{\text{eff}} = P_{\text{base}} \times \left(1 + 0.13 \times \left(\frac{1}{p} - 1\right)\right)
$$

This is the formula used by the optimizer to price inputs at specific floats. It explains why low‑float inputs can be dramatically more expensive than the listed market price.

### Outcome Probabilities

The probability of receiving a particular outcome skin is:

$$
P(\text{outcome}) = \frac{\text{inputs from its collection} / 10}{\text{number of possible outputs in that collection}}
$$

Example:

- 8 inputs from Collection A
- 2 inputs from Collection B
- Collection A has 3 possible Classified outputs
- Collection B has 4 possible Classified outputs

For an outcome from Collection A:

$$
P = \frac{8/10}{3} = \frac{0.8}{3} \approx 0.267
$$

For an outcome from Collection B:

$$
P = \frac{2/10}{4} = \frac{0.2}{4} = 0.05
$$

The probabilities sum to 1 across all possible outcomes.

### Expected Value, Profit, and ROI

**Expected value** is the probability‑weighted average of all outcome prices:

$$
EV = \sum_{\text{outcomes}} P_i \times V_i
$$

where $V_i$ is the price of outcome $i$.

**Profit**:

$$
\text{profit} = EV - \text{input cost}
$$

**ROI**:

$$
\text{ROI} = \frac{EV}{\text{input cost}}
$$

An ROI of `1.10` means you expect to receive `$1.10` back for every `$1.00` spent.

### Profit Chance

Profit chance is the fraction of possible outcomes whose value is at least the input cost:

$$
P_{\text{profit}} = \frac{\text{number of outcomes with } V_i \ge \text{input cost}}{\text{total number of outcomes}}
$$

This is a simple, intuitive measure of how likely the contract is to at least break even.

### Composite Rating

The rating is a number from `0` to `100`, designed to answer:

> “How good is this trade‑up overall?”

It combines five separate scores using a **weighted geometric mean**:

$$
\text{rating} = 100 \times \text{depthGate}
\times \text{roiScore}^{0.25}
\times \text{profitChanceScore}^{0.15}
\times \text{execCostScore}^{0.15}
\times \text{volScore}^{0.25}
\times \text{liquidityScore}^{0.20}
$$

#### Why Geometric Mean?

A normal arithmetic average lets one terrible factor be hidden by good factors. A geometric mean does the opposite: if any single component is near zero, the whole product collapses.

That matches real‑world trade‑ups — if one input skin cannot actually be bought, the entire contract is bad, no matter how good the ROI looks on paper.

#### ROI Score

A sigmoid function maps ROI into the `[0, 1]` range:

$$
\text{roiScore} = \frac{1}{1 + e^{-4 \times \text{ROI}}}
$$

| ROI | Score |
|---|---|
| 0.00 | 0.50 |
| 0.20 | 0.73 |
| 0.50 | 0.88 |
| 1.00 | 0.98 |

#### Profit Chance Score

Profit chance is already between `0` and `1`, so it is used directly.

#### Execution Cost Score

Execution cost is the sum of three openskin metrics:

$$
C_{\text{exec}} = \text{spread\%} + \text{slippage\%} + \text{price impact\%}
$$

A saturating transform converts it to a score:

$$
\text{execCostScore} = \frac{1}{1 + C_{\text{exec}} / 30}
$$

Lower execution cost means a higher score.

#### Volatility Score

CS2 skins have a mandatory **7‑day Steam trade lock** after purchase. After receiving the output skin, there is another **7‑day lock**.

You are therefore exposed to two sequential risk windows:

1. Holding input skins for 7 days
2. Holding the output skin for 7 days

The combined volatility is:

$$
\sigma_{\text{combined}} = \sqrt{\sigma_{\text{input}}^2 + \sigma_{\text{output}}^2}
$$

This is **not an average** — because the two exposure windows are sequential, their variances add.

The score is:

$$
\text{volScore} = \frac{1}{1 + \sigma_{\text{combined}} / 12}
$$

#### Liquidity Score

Openskin provides a liquidity score from `0` to `100`, based on trading volume relative to price movement.

Because a trade‑up is only as liquid as its worst leg, the rating uses the **minimum liquidity** across all input and output skins:

$$
\text{liquidityScore} = \frac{\min(\text{all liquidity scores})}{100}
$$

#### Depth Gate

The depth gate is a **hard feasibility penalty**, not another blended component.

Openskin’s `price_impact_5_pct` and `price_impact_10_pct` fields tell us whether the order book could fill 5 or 10 units of a skin at snapshot time.

If either input leg cannot fill its required quantity:

$$
\text{depthGate} = 0.1
$$

Otherwise:

$$
\text{depthGate} = 1.0
$$

This gate is **multiplied** into the final rating after the geometric mean.

#### Final Rating

A rating of `100` would mean:

- Excellent ROI
- High profit chance
- Low execution cost
- Low volatility
- High liquidity
- Sufficient order‑book depth

A rating near `0` means at least one factor makes the trade‑up practically unexecutable or very risky.

---

## Architecture

```
                  ┌──────────────────────┐
                  │       REST API       │
                  │      Spring Boot     │
                  │        Kotlin        │
                  └──────────┬───────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
      ┌───────▼────────┐           ┌────────▼────────┐
      │  PostgreSQL    │           │   ClickHouse    │
      │  Catalog       │           │  Price history  │
      │  Current prices│           │  Trade‑up snapshots│
      │  Recipes       │           │  Daily rollups  │
      │  Best pairs    │           │                 │
      └────────────────┘           └─────────────────┘
```

### PostgreSQL

Stores all reference and transactional data:

- Collections
- Weapons
- Rarities
- Items / skins
- Wear buckets
- Current prices
- Trade‑up recipes
- Recipe outcomes
- Calculator run status
- Best trade‑up per skin pair

### ClickHouse

Stores append‑only time‑series data:

- `item_price_history_raw`
- `tradeup_snapshot_raw`
- `tradeup_outcome_snapshot_raw`
- `tradeup_snapshot_latest`
- `tradeup_snapshot_rollup_1d`

Materialized views keep the `latest` and daily rollup tables updated automatically.

---

## API Overview

All endpoints are under `/api/v1`.

### Public Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/collections` | List all collections |
| GET | `/collections/{id}` | Collection detail grouped by rarity |
| GET | `/skins` | Paginated / filterable skin list |
| GET | `/skins/{id}` | Skin detail with prices per wear |
| GET | `/skins/{id}/price-history` | Price history for one skin/wear |
| GET | `/tradeups` | Paginated / filterable trade‑up list |
| GET | `/tradeups/best` | Best trade‑up for a skin pair |
| GET | `/tradeups/top` | Top‑rated trade‑ups overall |
| GET | `/tradeups/{recipeId}` | Full trade‑up detail |
| GET | `/tradeups/{recipeId}/history` | Performance history for one recipe |

### Admin Endpoints

All admin endpoints require **HTTP Basic authentication**.

| Method | Path | Description |
|---|---|---|
| POST | `/admin/optimize` | Start a full optimization run asynchronously |
| GET | `/admin/runs` | Recent calculator runs |
| GET | `/admin/runs/{id}` | Single run status |
| POST | `/admin/ingest/prices` | Start price ingestion |
| POST | `/admin/ingest/metrics` | Start Steam metrics enrichment |

---

## Getting Started

### Prerequisites

- **JDK 21**
- **Docker** with Docker Compose
- **Python 3** (optional, for smoke tests)

### 1. Clone the Repository

```bash
git clone https://github.com/nogaemer/cs2skins.git
cd cs2skins
```

### 2. Create the Environment File

```bash
cp .env.example .env
```

Edit `.env` and set secure passwords:

```dotenv
POSTGRES_USER=tradeup_app
POSTGRES_PASSWORD=your-strong-password
POSTGRES_DB=tradeups
CLICKHOUSE_USER=tradeup_app
CLICKHOUSE_PASSWORD=your-strong-password
CLICKHOUSE_DB=tradeups
ADMIN_USERNAME=admin
ADMIN_PASSWORD=your-admin-password

DAILY_PRICE_CRON=0 0 2 * * ?
DAILY_METRICS_CRON=0 0 3 * * ?
```

### 3. Start the Databases

```bash
docker compose -f infra/docker-compose.yml up -d
```

### 4. Build and Run

```bash
./gradlew bootRun
```

The API will be available at:

```
http://localhost:8080/api/v1
```

Swagger UI:

```
http://localhost:8080/swagger-ui.html
```

### 5. Seed the Catalog

Catalog seeding is performed by `PostgresSeedService`. You can trigger it via a small runner or integrate it into application startup as needed.

To start price ingestion manually:

```bash
curl -u admin:your-admin-password \
  -X POST http://localhost:8080/api/v1/admin/ingest/prices
```

---

## Configuration

Application configuration lives in `src/main/resources/application.yml`.

Environment variables are loaded from the root `.env` file.

| Property | Description |
|---|---|
| `POSTGRES_USER` | PostgreSQL username |
| `POSTGRES_PASSWORD` | PostgreSQL password |
| `POSTGRES_DB` | PostgreSQL database name |
| `CLICKHOUSE_USER` | ClickHouse username |
| `CLICKHOUSE_PASSWORD` | ClickHouse password |
| `CLICKHOUSE_DB` | ClickHouse database name |
| `ADMIN_USERNAME` | Basic auth username for admin endpoints |
| `ADMIN_PASSWORD` | Basic auth password for admin endpoints |
| `DAILY_PRICE_CRON` | Cron expression for daily price ingestion |
| `DAILY_METRICS_CRON` | Cron expression for daily metrics + optimization |

---

## Scheduling

The application uses Spring’s scheduling support.

Default schedule:

| Job | Schedule | Description |
|---|---|---|
| Price ingestion | Daily at 02:00 | Fast batch refresh of prices and volume |
| Metrics + optimization | Daily at 03:00 | Slow metrics enrichment, then full optimization |

Cron expressions can be overridden in `.env`.

---

## Security

Public read endpoints are available without authentication.

Admin endpoints require **HTTP Basic authentication** using the credentials from `.env`.

```
http://localhost:8080/api/v1/admin/**
```

The current implementation is intentionally simple, suitable for personal/local use or a trusted network. Before exposing the API publicly, consider:

- Moving credentials to a secret manager
- Adding HTTPS
- Replacing HTTP Basic with OAuth2 / API keys
- Adding rate limiting

---

## Project Structure

```
cs2skins_v2/
├── infra/
│   ├── docker-compose.yml
│   ├── reset_dev_data.ps1
│   ├── clickhouse/
│   │   └── init/
│   └── postgres/
│       └── init/
├── scripts/
│   └── smoke_test.py
└── src/
    └── main/
        ├── kotlin/de/nogaemer/cs2skinsv2/
        │   ├── catalog/
        │   ├── common/
        │   ├── config/
        │   ├── pricing/
        │   └── tradeup/
        └── resources/
            ├── application.yml
            └── logback.xml
```

---

## Data Sources

- [CSGO‑API](https://github.com/ByMykel/CSGO-API) — catalog data
- [openskin.dev](https://openskin.dev) — live prices and market microstructure

Both are free and public.

---

## License

This project does not yet specify a license. All rights reserved by the repository owner.

---

## Acknowledgments

Special thanks to:

- **ByMykel** for the CSGO‑API catalog data
- **openskin.dev** for free market data and metrics
- The CS2 trading community for documenting trade‑up mechanics

---

*Built for fun, data, and the search for profitable trade‑ups.*