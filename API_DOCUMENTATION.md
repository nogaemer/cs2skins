# CS2 Skins Trade-Up API Documentation

## Overview
This document provides comprehensive documentation for the CS2 Skins Trade-Up REST API. This API serves calculated trade-up opportunities, skin data, and collection information for CS2 (Counter-Strike 2) skins.

**Base URL:** `http://localhost:8080/api`

**Response Format:** JSON

**Authentication:** None (for now)

---

## Table of Contents
1. [Skins API](#skins-api)
2. [Collections API](#collections-api)
3. [Trade-Ups API](#trade-ups-api)
4. [Price History API](#price-history-api)
5. [System API](#system-api)
6. [Data Models](#data-models)
7. [Error Handling](#error-handling)

---

## Skins API

### Get All Skins
Retrieve a list of all skins in the database.

**Endpoint:** `GET /api/skins`

**Response:**
```json
[
  {
    "skinId": "skin-m4a4-howl",
    "name": "M4A4 | Howl",
    "collectionId": "the-huntsman-collection",
    "collectionName": "The Huntsman Collection",
    "weaponId": "weapon-m4a4",
    "weaponName": "M4A4",
    "rarityId": "rarity-covert",
    "rarityName": "Covert",
    "rarityColor": "EB4B4B",
    "stattrak": false,
    "minFloat": 0.0,
    "maxFloat": 1.0,
    "image": "https://example.com/image.png",
    "prices": {
      "FACTORY_NEW": {
        "price": 1500.00,
        "quantity": 10
      },
      "MINIMAL_WEAR": {
        "price": 1200.00,
        "quantity": 25
      }
    }
  }
]
```

---

### Get Skin by ID
Retrieve a specific skin by its ID.

**Endpoint:** `GET /api/skins/{skinId}`

**Path Parameters:**
- `skinId` (string, required): The unique identifier of the skin

**Response:**
```json
{
  "skinId": "skin-m4a4-howl",
  "name": "M4A4 | Howl",
  "collectionId": "the-huntsman-collection",
  "collectionName": "The Huntsman Collection",
  "weaponId": "weapon-m4a4",
  "weaponName": "M4A4",
  "rarityId": "rarity-covert",
  "rarityName": "Covert",
  "rarityColor": "EB4B4B",
  "stattrak": false,
  "minFloat": 0.0,
  "maxFloat": 1.0,
  "image": "https://example.com/image.png",
  "prices": {
    "FACTORY_NEW": {
      "price": 1500.00,
      "quantity": 10
    }
  }
}
```

**Status Codes:**
- `200 OK`: Skin found
- `404 Not Found`: Skin not found

---

### Search Skins (with Filters)
Search and filter skins based on various criteria.

**Endpoint:** `POST /api/skins/search`

**Request Body:**
```json
{
  "weaponId": "weapon-ak47",
  "rarityId": "rarity-covert",
  "collectionId": "the-bravo-collection",
  "stattrak": false,
  "minPrice": 10.00,
  "maxPrice": 100.00,
  "searchTerm": "Redline"
}
```

**Request Parameters** (all optional):
- `weaponId` (string): Filter by weapon ID
- `rarityId` (string): Filter by rarity ID
- `collectionId` (string): Filter by collection ID
- `stattrak` (boolean): Filter by StatTrak™ availability
- `minPrice` (decimal): Minimum average price
- `maxPrice` (decimal): Maximum average price
- `searchTerm` (string): Search term for skin or weapon name

**Response:** Array of `SkinResponse` objects (same structure as Get All Skins)

---

### Get Skins by Weapon
Retrieve all skins for a specific weapon.

**Endpoint:** `GET /api/skins/weapon/{weaponId}`

**Path Parameters:**
- `weaponId` (string, required): The weapon identifier (e.g., "weapon-ak47")

**Response:** Array of `SkinResponse` objects

---

### Get Skins by Rarity
Retrieve all skins of a specific rarity.

**Endpoint:** `GET /api/skins/rarity/{rarityId}`

**Path Parameters:**
- `rarityId` (string, required): The rarity identifier (e.g., "rarity-covert")

**Response:** Array of `SkinResponse` objects

---

### Get Skins by Collection
Retrieve all skins from a specific collection.

**Endpoint:** `GET /api/skins/collection/{collectionId}`

**Path Parameters:**
- `collectionId` (string, required): The collection identifier

**Query Parameters:**
- `stattrak` (boolean, optional, default: false): Filter for StatTrak™ versions

**Response:** Array of `SkinResponse` objects

---

## Collections API

### Get All Collections
Retrieve a list of all collections.

**Endpoint:** `GET /api/collections`

**Response:**
```json
[
  {
    "collectionId": "the-bravo-collection",
    "name": "The Bravo Collection",
    "image": "https://example.com/collection-image.png",
    "skinCount": null
  }
]
```

---

### Get Collection by ID
Retrieve a specific collection by its ID.

**Endpoint:** `GET /api/collections/{collectionId}`

**Path Parameters:**
- `collectionId` (string, required): The unique identifier of the collection

**Response:**
```json
{
  "collectionId": "the-bravo-collection",
  "name": "The Bravo Collection",
  "image": "https://example.com/collection-image.png",
  "skinCount": null
}
```

**Status Codes:**
- `200 OK`: Collection found
- `404 Not Found`: Collection not found

---

### Get Collection with Skins
Retrieve a collection along with all its skins.

**Endpoint:** `GET /api/collections/{collectionId}/skins`

**Path Parameters:**
- `collectionId` (string, required): The collection identifier

**Query Parameters:**
- `stattrak` (boolean, optional, default: false): Filter for StatTrak™ versions

**Response:**
```json
{
  "collectionId": "the-bravo-collection",
  "name": "The Bravo Collection",
  "image": "https://example.com/collection-image.png",
  "skins": [
    {
      "skinId": "skin-ak47-fire-serpent",
      "name": "AK-47 | Fire Serpent",
      "collectionId": "the-bravo-collection",
      "weaponId": "weapon-ak47",
      "weaponName": "AK-47",
      "rarityId": "rarity-covert",
      "rarityName": "Covert",
      "rarityColor": "EB4B4B",
      "stattrak": false,
      "minFloat": 0.0,
      "maxFloat": 1.0,
      "image": "https://example.com/skin-image.png",
      "prices": {
        "FIELD_TESTED": {
          "price": 500.00,
          "quantity": 50
        }
      }
    }
  ]
}
```

---

## Trade-Ups API

### Get All Trade-Ups
Retrieve all calculated trade-up opportunities.

**Endpoint:** `GET /api/tradeups`

**Response:**
```json
[
  {
    "id": 1,
    "collectionA": {
      "collectionId": "the-bravo-collection",
      "name": "The Bravo Collection"
    },
    "collectionB": {
      "collectionId": "the-phoenix-collection",
      "name": "The Phoenix Collection"
    },
    "rarity": {
      "rarityId": "rarity-mil-spec",
      "name": "Mil-Spec Grade",
      "colorHex": "4B69FF"
    },
    "stattrak": false,
    "outputFloat": 0.15,
    "roi": 1.45,
    "profit": 2.50,
    "inputCost": 5.50,
    "outputCost": 8.00,
    "inputs": [
      {
        "skinId": "skin-ak47-blue-laminate",
        "skinName": "AK-47 | Blue Laminate",
        "amount": 6,
        "floatValue": 0.15,
        "pricePerUnit": 0.50
      },
      {
        "skinId": "skin-m4a1s-guardian",
        "skinName": "M4A1-S | Guardian",
        "amount": 4,
        "floatValue": 0.18,
        "pricePerUnit": 0.75
      }
    ],
    "outputs": [
      {
        "skinId": "skin-awp-asiimov",
        "skinName": "AWP | Asiimov",
        "probability": 0.6,
        "floatValue": 0.20,
        "price": 5.00
      },
      {
        "skinId": "skin-ak47-redline",
        "skinName": "AK-47 | Redline",
        "probability": 0.4,
        "floatValue": 0.22,
        "price": 3.00
      }
    ],
    "createdAt": 1708300000000
  }
]
```

---

### Get Trade-Up by ID
Retrieve a specific trade-up by its ID.

**Endpoint:** `GET /api/tradeups/{id}`

**Path Parameters:**
- `id` (integer, required): The unique identifier of the trade-up

**Response:** Single `TradeUpResultResponse` object (same structure as above)

**Status Codes:**
- `200 OK`: Trade-up found
- `404 Not Found`: Trade-up not found

---

### Filter Trade-Ups
Filter and sort trade-up opportunities based on various criteria with pagination support.

**Endpoint:** `POST /api/tradeups/filter`

**Request Body:**
```json
{
  "minRoi": 1.2,
  "maxRoi": 2.0,
  "minProfit": 1.0,
  "maxProfit": 10.0,
  "stattrak": false,
  "rarityId": "rarity-restricted",
  "sortBy": "roi",
  "sortDirection": "desc",
  "page": 0,
  "size": 20
}
```

**Request Parameters** (all optional):
- `minRoi` (double): Minimum ROI (Return on Investment)
- `maxRoi` (double): Maximum ROI
- `minProfit` (double): Minimum profit in dollars
- `maxProfit` (double): Maximum profit in dollars
- `stattrak` (boolean): Filter by StatTrak™
- `rarityId` (string): Filter by input rarity
- `sortBy` (string): Sort field - "roi", "profit", "inputCost", or "createdAt" (default: "roi")
- `sortDirection` (string): Sort direction - "asc" or "desc" (default: "desc")
- `page` (int): Page number, 0-indexed (default: 0)
- `size` (int): Number of results per page (default: 20)

**Response:** Paginated response with metadata
```json
{
  "content": [
    {
      "id": 1,
      "collectionA": {
        "collectionId": "the-huntsman-collection",
        "name": "The Huntsman Collection"
      },
      "collectionB": {
        "collectionId": "the-chroma-collection",
        "name": "The Chroma Collection"
      },
      "rarity": {
        "rarityId": "rarity-restricted",
        "name": "Restricted",
        "colorHex": "8847FF"
      },
      "stattrak": false,
      "outputFloat": 0.15,
      "roi": 1.85,
      "profit": 5.23,
      "inputCost": 6.15,
      "outputCost": 11.38,
      "inputs": [...],
      "outputs": [...],
      "createdAt": 1708354800000
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8,
  "isFirst": true,
  "isLast": false,
  "hasNext": true,
  "hasPrevious": false
}
```

**Pagination Response Fields:**
- `content`: Array of trade-up results for the current page
- `page`: Current page number (0-indexed)
- `size`: Number of items per page
- `totalElements`: Total number of results across all pages
- `totalPages`: Total number of pages
- `isFirst`: True if this is the first page
- `isLast`: True if this is the last page
- `hasNext`: True if there is a next page
- `hasPrevious`: True if there is a previous page

---

### Delete All Trade-Ups
Delete all trade-up results from the database.

**Endpoint:** `DELETE /api/tradeups`

**Response:**
```json
{
  "deleted": 150,
  "message": "All trade-ups deleted successfully"
}
```

---

### Get Trade-Up History (Time-Series)
Returns a time-bucketed series of ROI/profit snapshots for a specific trade-up.

**Endpoint:** `GET /api/tradeups/{id}/history`

**Path Parameters:**
- `id` (integer, required): Trade-up master ID

**Query Parameters:**
| Parameter   | Type    | Default        | Description                                                                                                    |
|-------------|---------|----------------|----------------------------------------------------------------------------------------------------------------|
| `from`      | long    | 30 days ago    | Start of time range as epoch milliseconds (e.g. `1700000000000`)                                              |
| `to`        | long    | now            | End of time range as epoch milliseconds                                                                        |
| `bucket`    | long    | `86400000`     | Bucket width in milliseconds. **= 86400000 (exactly 1 day) → reads from the `tradeup_daily` continuous aggregate (fast). Any other value → reads raw `tradeup_snapshots` hypertable.** |
| `maxPoints` | integer | `1000`         | Maximum number of buckets to return (clamped to 1–10000). The effective `from` is adjusted so at most this many points are produced. |

> **Aggregate vs. raw data:**  
> When `bucket == 86400000` (exactly 1 day) the API reads from the pre-materialised `tradeup_daily`
> view for fast, index-only access. For all other bucket widths (both sub-day and coarser) it
> falls back to scanning raw `tradeup_snapshots` within the requested time range so the requested
> bucket width is applied correctly.

**Example request (daily, last 7 days):**
```
GET /api/tradeups/42/history?bucket=86400000&maxPoints=7
```

**Response:** `200 OK` — array of time-bucketed points
```json
[
  {
    "bucketStart": 1706745600000,
    "roi": 1.42,
    "profit": 3.15,
    "inputCost": 6.20,
    "outputCost": 9.35,
    "samples": 12
  },
  {
    "bucketStart": 1706832000000,
    "roi": 1.38,
    "profit": 2.97,
    "inputCost": 6.20,
    "outputCost": 9.17,
    "samples": 8
  }
]
```

**Response fields:**
- `bucketStart` — start of the bucket as epoch milliseconds
- `roi` — average ROI in this bucket (e.g. `1.42` = 42 % profit)
- `profit` — average profit in USD
- `inputCost` — average total input cost in USD
- `outputCost` — average expected output value in USD
- `samples` — number of raw snapshots aggregated into this bucket

**Status Codes:**
- `200 OK`: Success (empty array if no data in range)

---

### Get Trade-Up Risk Summary
Returns aggregated risk metrics for a trade-up over a given time window.

Risk columns (`probProfit`, `variance`, `p05`/`p50`/`p95`) require migration 006 and
are `null` until snapshots with risk-metric data are written.

**Endpoint:** `GET /api/tradeups/{id}/risk`

**Path Parameters:**
- `id` (integer, required): Trade-up master ID

**Query Parameters:**
| Parameter | Type | Default     | Description                                          |
|-----------|------|-------------|------------------------------------------------------|
| `from`    | long | 30 days ago | Start of time range as epoch milliseconds            |
| `to`      | long | now         | End of time range as epoch milliseconds              |

**Example request:**
```
GET /api/tradeups/42/risk?from=1706745600000&to=1709251200000
```

**Response:** `200 OK`
```json
{
  "tradeupId": 42,
  "from": 1706745600000,
  "to": 1709251200000,
  "avgRoi": 1.41,
  "samples": 120,
  "probProfit": 0.73,
  "variance": 4.52,
  "p05": -1.20,
  "p50": 2.80,
  "p95": 7.40
}
```

**Response fields:**
| Field        | Type   | Description                                                                 |
|--------------|--------|-----------------------------------------------------------------------------|
| `tradeupId`  | int    | Trade-up master ID                                                          |
| `from`       | long   | Start of window (epoch ms, echoed back)                                     |
| `to`         | long   | End of window (epoch ms, echoed back)                                       |
| `avgRoi`     | double | Average ROI across all snapshots in the window                              |
| `samples`    | long   | Number of snapshots in the window                                           |
| `probProfit` | double | Average fraction of outcome values with profit > 0 (null if not populated) |
| `variance`   | double | Average weighted variance of the output-value distribution (null if not populated) |
| `p05`        | double | Average 5th percentile of the output distribution in USD (null if not populated) |
| `p50`        | double | Average 50th percentile (median) in USD (null if not populated)            |
| `p95`        | double | Average 95th percentile in USD (null if not populated)                     |

**Status Codes:**
- `200 OK`: Success (avgRoi will be 0.0 and samples 0 if no data in range)

---

### Get Top Trade-Ups
Returns the top-N trade-ups ranked by average ROI or profit within a time window.

Uses the `tradeup_daily` continuous aggregate when `bucket=day` (default), providing
fast index-only reads. Falling back to raw snapshots is triggered by any other `bucket`
value, but is not recommended for large time windows.

**Endpoint:** `GET /api/tradeups/top`

**Query Parameters:**
| Parameter     | Type    | Default     | Description                                                                                         |
|---------------|---------|-------------|-----------------------------------------------------------------------------------------------------|
| `from`        | long    | 30 days ago | Start of window as epoch milliseconds                                                               |
| `to`          | long    | now         | End of window as epoch milliseconds                                                                 |
| `limit`       | integer | `10`        | Maximum number of results, clamped to 1–200                                                         |
| `sort`        | string  | `roi`       | Ranking field: `roi` or `profit`. Any other value returns `400 Bad Request`.                       |
| `bucket`      | string  | `day`       | Aggregation granularity. `day` → reads from `tradeup_daily` aggregate (fast). Anything else → raw snapshots (slower). |
| `stattrak`    | boolean | –           | Optional filter: `true` = StatTrak™ only, `false` = non-StatTrak only, omit = both                |
| `rarity`      | string  | –           | Optional rarity ID filter (e.g. `rarity-classified`)                                               |
| `collections` | string  | –           | Optional comma-separated collection IDs (e.g. `the-bravo-collection,the-phoenix-collection`)       |

**Example requests:**
```bash
# Top 5 by ROI in January 2024 using daily aggregate
GET /api/tradeups/top?from=1704067200000&to=1706745599000&limit=5&sort=roi&bucket=day

# Top 10 Classified StatTrak tradeups this month
GET /api/tradeups/top?limit=10&sort=roi&stattrak=true&rarity=rarity-classified

# Filter by collections
GET /api/tradeups/top?limit=20&sort=profit&collections=the-bravo-collection,the-phoenix-collection
```

**Response:** `200 OK`
```json
{
  "tradeups": [
    {
      "tradeupId": 42,
      "avgRoi": 1.89,
      "avgProfit": 5.43,
      "samples": 480,
      "stattrak": false,
      "rarityId": "rarity-classified",
      "rarityName": "Classified"
    },
    {
      "tradeupId": 17,
      "avgRoi": 1.74,
      "avgProfit": 3.21,
      "samples": 312,
      "stattrak": false,
      "rarityId": "rarity-restricted",
      "rarityName": "Restricted"
    }
  ],
  "from": 1704067200000,
  "to": 1706745599000,
  "bucket": "day",
  "source": "aggregate"
}
```

**Response fields:**
| Field       | Type   | Description                                                                 |
|-------------|--------|-----------------------------------------------------------------------------|
| `tradeups`  | array  | Ranked list of trade-up entries (see below)                                 |
| `from`      | long   | Start of window (epoch ms, echoed back)                                     |
| `to`        | long   | End of window (epoch ms, echoed back)                                       |
| `bucket`    | string | Echoed back from the request parameter                                      |
| `source`    | string | `"aggregate"` when `tradeup_daily` was used; `"raw"` when falling back to raw snapshots |

**`tradeups[]` entry fields:**
| Field       | Type    | Description                                          |
|-------------|---------|------------------------------------------------------|
| `tradeupId` | int     | Trade-up master ID                                   |
| `avgRoi`    | double  | Average ROI in the window (e.g. `1.89` = 89% profit)|
| `avgProfit` | double  | Average profit in USD                                |
| `samples`   | long    | Total number of snapshots / daily aggregate rows     |
| `stattrak`  | boolean | Whether this is a StatTrak™ trade-up                 |
| `rarityId`  | string  | Rarity identifier of the input skins                 |
| `rarityName`| string  | Human-readable rarity name                           |

**Status Codes:**
- `200 OK`: Success (empty `tradeups` array if no data in range)
- `400 Bad Request`: Invalid `sort` value

---

## Price History API

### Get Latest Prices for a Skin
Returns the most recent known price for every wear/source/currency combination for a skin.

**Endpoint:** `GET /api/prices/skins/{skinId}/latest`

**Path Parameters:**
- `skinId` (string, required): The skin identifier

**Query Parameters:**
| Parameter  | Type   | Description                                              |
|------------|--------|----------------------------------------------------------|
| `source`   | string | Optional source filter (e.g. `steam`, `csfloat`)        |
| `currency` | string | Optional currency code filter (e.g. `USD`, `EUR`)       |

**Example request:**
```
GET /api/prices/skins/skin-ak47-redline/latest?source=steam&currency=USD
```

**Response:** `200 OK` — array of latest price records
```json
[
  {
    "skinId": "skin-ak47-redline",
    "wearId": "factory_new",
    "sourceId": 1,
    "sourceName": "steam",
    "currencyId": 1,
    "currencyCode": "USD",
    "price": 45.23,
    "quantity": 12,
    "updatedAt": "2024-02-01T14:30:00Z"
  }
]
```

---

### Get Price History for a Skin
Returns historical price data for a skin with optional time-bucketing.

**Endpoint:** `GET /api/prices/skins/{skinId}/history`

**Path Parameters:**
- `skinId` (string, required): The skin identifier

**Query Parameters:**
| Parameter  | Type    | Default      | Description                                                                                     |
|------------|---------|--------------|-----------------------------------------------------------------------------------------------|
| `wearId`   | string  | –            | Optional wear-condition filter (e.g. `factory_new`)                                            |
| `from`     | string  | 7 days ago   | Start of range in ISO 8601 / RFC 3339 format (e.g. `2024-01-01T00:00:00Z`)                   |
| `to`       | string  | now          | End of range in ISO 8601 / RFC 3339 format                                                     |
| `source`   | string  | –            | Optional source filter (e.g. `steam`)                                                          |
| `currency` | string  | –            | Optional currency filter (e.g. `USD`)                                                          |
| `bucket`   | string  | –            | Bucket width: `1h`, `6h`, `1d`, `7d`, `30d`. When set, returns aggregated (avg/min/max) data; when omitted, returns raw rows. |
| `limit`    | integer | `100`        | Maximum rows/buckets (capped at 1000 raw / 500 bucketed)                                      |
| `offset`   | integer | `0`          | Row offset for raw queries (ignored when `bucket` is set)                                     |

**Example requests:**
```bash
# Raw price history (last 7 days, default)
GET /api/prices/skins/skin-ak47-redline/history

# Daily bucketed aggregates
GET /api/prices/skins/skin-ak47-redline/history?bucket=1d&from=2024-01-01T00:00:00Z&to=2024-02-01T00:00:00Z
```

**Response (raw, bucket omitted):** `200 OK`
```json
[
  {
    "skinId": "skin-ak47-redline",
    "wearId": "factory_new",
    "sourceId": 1,
    "sourceName": "steam",
    "currencyId": 1,
    "currencyCode": "USD",
    "price": 45.23,
    "quantity": 12,
    "recordedAt": "2024-01-15T12:00:00Z"
  }
]
```

**Response (bucketed, with `bucket=1d`):** `200 OK`
```json
[
  {
    "bucket": "2024-01-15T00:00:00Z",
    "wearId": "factory_new",
    "sourceId": 1,
    "sourceName": "steam",
    "currencyId": 1,
    "currencyCode": "USD",
    "avgPrice": 45.10,
    "minPrice": 43.50,
    "maxPrice": 46.80
  }
]
```

**Status Codes:**
- `200 OK`: Success
- `400 Bad Request`: Invalid `bucket` value or `from` is after `to`

---

### Seed Collections
Trigger the collection seeding job to fetch and populate collection data.

**Endpoint:** `POST /api/system/seed/collections`

**Response:**
```json
{
  "status": "started",
  "message": "Collection seed job started"
}
```

**Status Values:**
- `started`: Job has been initiated
- `running`: Job is already running

---

### Seed Skins
Trigger the skin seeding job to fetch and populate skin and price data.

**Endpoint:** `POST /api/system/seed/skins`

**Response:**
```json
{
  "status": "started",
  "message": "Skins seed job started"
}
```

**Note:** This job can take several minutes to complete as it fetches data from external APIs.

---

### Seed All Data
Trigger a complete data seeding (collections + skins).

**Endpoint:** `POST /api/system/seed/all`

**Response:**
```json
{
  "status": "started",
  "message": "Full seed job started (collections + skins)"
}
```

---

### Calculate Trade-Ups
Trigger the trade-up calculation engine to find profitable opportunities.

**Endpoint:** `POST /api/system/calculate`

**Query Parameters:**
- `stattrak` (boolean, optional, default: false): Calculate for StatTrak™ skins

**Response:**
```json
{
  "status": "started",
  "message": "Trade-up calculation job started"
}
```

**Note:** This is a computationally intensive operation that can take significant time.

---

### Calculate All Trade-Ups
Calculate trade-ups for both regular and StatTrak™ skins.

**Endpoint:** `POST /api/system/calculate/all`

**Response:**
```json
{
  "status": "started",
  "message": "Full trade-up calculation job started (non-stattrak + stattrak)"
}
```

---

### Get System Status
Check the status of background jobs.

**Endpoint:** `GET /api/system/status`

**Response:**
```json
{
  "seedJobRunning": false,
  "calculateJobRunning": true
}
```

---

## Data Models

### SkinResponse
```typescript
{
  skinId: string;
  name: string;
  collectionId: string | null;
  collectionName: string | null;
  weaponId: string | null;
  weaponName: string | null;
  rarityId: string | null;
  rarityName: string | null;
  rarityColor: string | null; // Hex color without #
  stattrak: boolean;
  minFloat: number; // Minimum float value (0.0 - 1.0)
  maxFloat: number; // Maximum float value (0.0 - 1.0)
  image: string | null;
  prices: {
    [wearCondition: string]: {
      price: number;
      quantity: number;
    }
  } | null;
}
```

**Wear Conditions:**
- `FACTORY_NEW`
- `MINIMAL_WEAR`
- `FIELD_TESTED`
- `WELL_WORN`
- `BATTLE_SCARRED`

---

### CollectionResponse
```typescript
{
  collectionId: string;
  name: string;
  image: string | null;
  skinCount: number | null;
}
```

---

### TradeUpResultResponse
```typescript
{
  id: number;
  collectionA: {
    collectionId: string;
    name: string;
  };
  collectionB: {
    collectionId: string;
    name: string;
  };
  rarity: {
    rarityId: string;
    name: string;
    colorHex: string | null;
  } | null;
  stattrak: boolean;
  outputFloat: number; // Average output float (0.0 - 1.0)
  roi: number; // Return on investment multiplier (e.g., 1.45 = 45% profit)
  profit: number; // Profit in dollars
  inputCost: number; // Total cost of inputs in dollars
  outputCost: number; // Expected value of outputs in dollars
  inputs: [
    {
      skinId: string;
      skinName: string;
      amount: number; // Number of this skin to use (1-10)
      floatValue: number; // Required float value
      pricePerUnit: number;
    }
  ];
  outputs: [
    {
      skinId: string;
      skinName: string;
      probability: number; // Drop probability (0.0 - 1.0)
      floatValue: number; // Expected output float
      price: number;
    }
  ];
  createdAt: number; // Unix timestamp in milliseconds
}
```

---

### TradeUpHistoryPoint
```typescript
{
  bucketStart: number;  // Start of bucket as epoch ms
  roi: number;          // Average ROI in this bucket
  profit: number;       // Average profit in USD
  inputCost: number;    // Average total input cost in USD
  outputCost: number;   // Average expected output value in USD
  samples: number;      // Number of raw snapshots in this bucket (default 1 for raw rows)
}
```

---

### TradeUpRiskResponse
```typescript
{
  tradeupId: number;
  from: number;          // Start of window (epoch ms)
  to: number;            // End of window (epoch ms)
  avgRoi: number;        // Average ROI across all snapshots in window
  samples: number;       // Number of snapshots
  probProfit: number | null;  // Average fraction of outcomes where profit > 0
  variance: number | null;    // Average weighted variance of output distribution
  p05: number | null;         // Average 5th percentile of output distribution (USD)
  p50: number | null;         // Average median of output distribution (USD)
  p95: number | null;         // Average 95th percentile of output distribution (USD)
}
```

> **Note:** Risk fields are `null` until migration 006 has been applied and snapshots
> with risk-metric data have been written.

---

### TopTradeupResponse
```typescript
{
  tradeups: TopTradeupEntry[];
  from: number;    // Start of window (epoch ms)
  to: number;      // End of window (epoch ms)
  bucket: string;  // Echoed bucket parameter ("day", etc.)
  source: string;  // "aggregate" | "raw"
}

interface TopTradeupEntry {
  tradeupId: number;
  avgRoi: number;
  avgProfit: number;
  samples: number;
  stattrak: boolean;
  rarityId: string | null;
  rarityName: string | null;
}
```

---

## Error Handling

### Standard Error Response
```json
{
  "timestamp": "2026-02-18T23:51:46.872Z",
  "status": 404,
  "error": "Not Found",
  "message": "Skin not found",
  "path": "/api/skins/invalid-id"
}
```

### Common HTTP Status Codes
- `200 OK`: Successful request
- `201 Created`: Resource created successfully
- `400 Bad Request`: Invalid request parameters
- `404 Not Found`: Resource not found
- `500 Internal Server Error`: Server error

---

## Usage Examples

### Example: Typical Workflow for Frontend

1. **Initialize Data** (First time setup)
```bash
# Seed collections
POST /api/system/seed/collections

# Seed skins (this takes a while)
POST /api/system/seed/skins

# Calculate trade-ups (this also takes a while)
POST /api/system/calculate/all
```

2. **Display Dashboard**
```bash
# Get top profitable trade-ups using daily aggregate (fast)
GET /api/tradeups/top?limit=10&sort=roi&bucket=day

# Get top trade-ups (old filter endpoint also works)
POST /api/tradeups/filter
Body: { "sortBy": "profit", "sortDirection": "desc" }

# Get all collections for navigation
GET /api/collections
```

3. **Analytics: Trade-Up History and Risk**
```bash
# Daily ROI history for a trade-up (uses aggregate view, fast)
GET /api/tradeups/42/history?bucket=86400000&maxPoints=30

# Sub-day history (hourly, uses raw snapshots)
GET /api/tradeups/42/history?bucket=3600000

# Risk summary for past 30 days
GET /api/tradeups/42/risk

# Top StatTrak Classified trade-ups this month
GET /api/tradeups/top?stattrak=true&rarity=rarity-classified&sort=roi
```

3. **Search for Specific Skins**
```bash
# Search for AK-47 skins
POST /api/skins/search
Body: { "weaponId": "weapon-ak47" }
```

4. **View Trade-Up Details**
```bash
# Get specific trade-up by ID
GET /api/tradeups/123
```

---

## Notes for Frontend Development

1. **Asynchronous Jobs**: The seed and calculate endpoints are asynchronous. Poll `/api/system/status` to check job completion.

2. **Large Datasets**: The `/api/skins` endpoint returns all skins (potentially 1000+). Consider implementing pagination or use the search endpoint with filters.

3. **Price Display**: Prices are returned as BigDecimal strings. Convert to numbers for display: `parseFloat(price)`.

4. **Float Values**: Float values range from 0.0 (Factory New) to 1.0 (Battle Scarred). Lower floats are generally more valuable.

5. **ROI Interpretation**: An ROI of 1.2 means 20% profit, 1.5 means 50% profit, etc.

6. **Timestamps**: `createdAt` is a Unix timestamp in milliseconds. Convert to Date: `new Date(createdAt)`.

7. **Color Codes**: Rarity colors are hex codes without the `#` prefix. Add it when displaying: `#${rarityColor}`.

8. **Timestamps in history/risk/top endpoints**: `from`, `to`, and `bucketStart` are epoch milliseconds. Convert to Date: `new Date(timestamp)`. ISO 8601 strings are used only in price-history endpoints.

9. **Aggregate vs. raw data**: Endpoints with a `bucket` parameter note whether they use the `tradeup_daily` continuous aggregate or fall back to raw `tradeup_snapshots`. The `source` field in `/top` responses indicates which path was taken. Use `bucket=day` to guarantee aggregate usage and avoid slow hypertable scans.

10. **Risk metrics**: `probProfit`, `variance`, `p05`, `p50`, `p95` fields are `null` until the snapshots table is populated with risk columns. They become available after migration 006 and a recalculation run.

---

## Rate Limiting

Currently, there are no rate limits implemented. However, avoid excessive requests to the `/api/system/seed/*` and `/api/system/calculate` endpoints as they are resource-intensive.

---

## Future Enhancements

Potential additions to consider for the frontend:
- Real-time price updates via WebSocket
- User accounts and saved trade-ups
- Profit tracking and portfolio management
- Price history and trends
- Advanced filtering with multiple collections
- Export trade-ups to CSV/PDF

---

**Last Updated:** 2026-02-28
**API Version:** 1.1
