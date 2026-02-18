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
4. [System API](#system-api)
5. [Data Models](#data-models)
6. [Error Handling](#error-handling)

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
Filter and sort trade-up opportunities based on various criteria.

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
  "sortDirection": "desc"
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

**Response:** Array of `TradeUpResultResponse` objects

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

## System API

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
# Get top profitable trade-ups
POST /api/tradeups/filter
Body: { "sortBy": "profit", "sortDirection": "desc" }

# Get all collections for navigation
GET /api/collections
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

**Last Updated:** 2026-02-18
**API Version:** 1.0
