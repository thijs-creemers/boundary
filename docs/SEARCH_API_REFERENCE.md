# Full-Text Search API Reference

**Version**: 1.0  
**Last Updated**: 2026-01-04  
**Status**: ✅ Production Ready

---

## Table of Contents

1. [Overview](#overview)
2. [Quick Start](#quick-start)
3. [API Endpoints](#api-endpoints)
4. [Query Syntax](#query-syntax)
5. [Response Format](#response-format)
6. [Configuration](#configuration)
7. [Performance](#performance)
8. [Examples](#examples)
9. [Migration Guide](#migration-guide)
10. [Troubleshooting](#troubleshooting)

---

## Overview

The Boundary Framework provides enterprise-grade full-text search capabilities using PostgreSQL's native full-text search engine. This provides:

- ✅ **Zero Dependencies**: Built into PostgreSQL, no external services required
- ✅ **High Performance**: < 100ms average search time, sub-50ms for < 10K documents
- ✅ **Rich Features**: Highlighting, ranking, recency boosting, autocomplete
- ✅ **Production Ready**: SQL injection prevention, concurrent search handling
- ✅ **Easy Integration**: RESTful API with JSON responses

### Supported Search Operations

| Operation | Description | Endpoint |
|-----------|-------------|----------|
| **User Search** | Full-text search across user names, emails, bios | `GET /api/search/users` |
| **Item Search** | Full-text search across item names, SKUs, locations | `GET /api/search/items` |
| **Autocomplete** | Prefix-based suggestions for typeahead | `GET /api/search/suggest` |
| **Reindexing** | Rebuild search indexes | `POST /api/search/reindex/:index` |
| **Statistics** | Query search index statistics | `GET /api/search/stats` |

### Architecture

```
HTTP Request
    ↓
Search HTTP Handler (http.clj)
    ↓ validate & parse query params
Search Service (service.clj)
    ↓ orchestrate search flow
PostgreSQL Provider (postgresql.clj)
    ↓ generate SQL with plainto_tsquery, ts_rank
PostgreSQL Database
    ↓ execute full-text search using GIN index
    ↓ return ranked results
Service Layer
    ↓ apply recency boost
    ↓ re-rank results
    ↓ add highlighting
HTTP Response
    ↓ JSON with results, pagination, timing
```

---

## Quick Start

### 1. Basic User Search

**Request**:
```bash
curl "http://localhost:3000/api/search/users?q=john"
```

**Response**:
```json
{
  "results": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "John Smith",
      "email": "john@example.com",
      "bio": "Software engineer",
      "score": 0.85,
      "rank": 1,
      "_highlights": {
        "name": "<mark>John</mark> Smith"
      }
    }
  ],
  "total": 1,
  "max-score": 0.85,
  "page": {
    "from": 0,
    "size": 20
  },
  "took-ms": 15
}
```

### 2. Search with Pagination

```bash
curl "http://localhost:3000/api/search/users?q=admin&from=20&size=10"
```

### 3. Search Without Highlighting

```bash
curl "http://localhost:3000/api/search/users?q=engineer&highlight=false"
```

### 4. Autocomplete Suggestions

```bash
curl "http://localhost:3000/api/search/suggest?prefix=joh&field=name&index=users"
```

**Response**:
```json
{
  "suggestions": [
    {"value": "John Smith", "score": 0.95},
    {"value": "Johnny Doe", "score": 0.85},
    {"value": "John Anderson", "score": 0.75}
  ],
  "total": 3
}
```

---

## API Endpoints

### 1. Search Users

**Endpoint**: `GET /api/search/users`

Search users by name, email, or bio with full-text search capabilities.

**Query Parameters**:

| Parameter | Type | Required | Default | Max | Description |
|-----------|------|----------|---------|-----|-------------|
| `q` | string | ✅ Yes | - | - | Search query text |
| `from` | integer | No | 0 | - | Pagination offset (0-based) |
| `size` | integer | No | 20 | 100 | Number of results per page |
| `highlight` | boolean | No | true | - | Enable result highlighting |
| `highlight_fields` | string | No | name,email | - | Comma-separated fields to highlight |
| `boost_recent` | boolean | No | true | - | Boost scores for recent documents |

**Response**: [Search Response Format](#search-response-format)

**Status Codes**:
- `200 OK` - Search successful
- `400 Bad Request` - Invalid query parameters (missing `q` or empty)
- `500 Internal Server Error` - Search failed

**Examples**:

```bash
# Basic search
curl "http://localhost:3000/api/search/users?q=john"

# Search with pagination
curl "http://localhost:3000/api/search/users?q=admin&from=0&size=10"

# Search without highlighting
curl "http://localhost:3000/api/search/users?q=engineer&highlight=false"

# Search with custom highlight fields
curl "http://localhost:3000/api/search/users?q=clojure&highlight_fields=bio"

# Search without recency boost
curl "http://localhost:3000/api/search/users?q=developer&boost_recent=false"

# Multi-word search
curl "http://localhost:3000/api/search/users?q=software+engineer"
```

---

### 2. Search Items

**Endpoint**: `GET /api/search/items`

Search inventory items by name, SKU, or location.

**Query Parameters**:

| Parameter | Type | Required | Default | Max | Description |
|-----------|------|----------|---------|-----|-------------|
| `q` | string | ✅ Yes | - | - | Search query text |
| `from` | integer | No | 0 | - | Pagination offset (0-based) |
| `size` | integer | No | 20 | 100 | Number of results per page |
| `highlight` | boolean | No | true | - | Enable result highlighting |
| `highlight_fields` | string | No | name,sku,location | - | Fields to highlight |
| `boost_recent` | boolean | No | true | - | Boost scores for recent documents |

**Response**: [Search Response Format](#search-response-format)

**Status Codes**:
- `200 OK` - Search successful
- `400 Bad Request` - Invalid query parameters
- `500 Internal Server Error` - Search failed

**Examples**:

```bash
# Search by item name
curl "http://localhost:3000/api/search/items?q=laptop"

# Search by SKU
curl "http://localhost:3000/api/search/items?q=SKU-12345"

# Search by location
curl "http://localhost:3000/api/search/items?q=warehouse+A"

# Search with pagination
curl "http://localhost:3000/api/search/items?q=electronics&from=10&size=5"
```

---

### 3. Autocomplete Suggestions

**Endpoint**: `GET /api/search/suggest`

Get prefix-based autocomplete suggestions for typeahead functionality.

**Query Parameters**:

| Parameter | Type | Required | Default | Max | Description |
|-----------|------|----------|---------|-----|-------------|
| `prefix` | string | ✅ Yes | - | - | Prefix to complete (e.g., "joh") |
| `field` | string | ✅ Yes | - | - | Field to search (name, email, sku, location) |
| `index` | string | No | users | - | Index to search (users or items) |
| `limit` | integer | No | 10 | 50 | Maximum number of suggestions |

**Response Format**:
```json
{
  "suggestions": [
    {"value": "John Smith", "score": 0.95},
    {"value": "Johnny Doe", "score": 0.85}
  ],
  "total": 2
}
```

**Status Codes**:
- `200 OK` - Suggestions retrieved
- `400 Bad Request` - Missing required parameters
- `500 Internal Server Error` - Suggest failed

**Examples**:

```bash
# Get name suggestions
curl "http://localhost:3000/api/search/suggest?prefix=joh&field=name&index=users"

# Get email suggestions
curl "http://localhost:3000/api/search/suggest?prefix=john@&field=email&index=users"

# Get SKU suggestions (items)
curl "http://localhost:3000/api/search/suggest?prefix=SKU-&field=sku&index=items&limit=5"

# Get location suggestions (items)
curl "http://localhost:3000/api/search/suggest?prefix=war&field=location&index=items"
```

---

### 4. Reindex Documents

**Endpoint**: `POST /api/search/reindex/:index`

Rebuild search index from database. Use this after bulk data imports or schema changes.

**Path Parameters**:

| Parameter | Type | Required | Values | Description |
|-----------|------|----------|--------|-------------|
| `index` | string | ✅ Yes | users, items | Index to rebuild |

**Response Format**:
```json
{
  "status": "success",
  "index": "users",
  "documents-indexed": 1234,
  "took-ms": 1500
}
```

**Status Codes**:
- `200 OK` - Reindex successful
- `400 Bad Request` - Invalid index name
- `500 Internal Server Error` - Reindex failed

**Examples**:

```bash
# Reindex users
curl -X POST "http://localhost:3000/api/search/reindex/users"

# Reindex items
curl -X POST "http://localhost:3000/api/search/reindex/items"
```

**When to Reindex**:
- After bulk data import
- After changing search field configurations
- After database schema changes affecting search columns
- If search results seem stale (shouldn't happen with GENERATED columns)

**Performance**:
- Users: ~100 docs/second
- Items: ~100 docs/second
- Reindexing is done in batches to avoid memory issues

---

### 5. Search Statistics

**Endpoint**: `GET /api/search/stats`

Retrieve statistics about search indexes, document counts, and query performance.

**Query Parameters**: None

**Response Format**:
```json
{
  "indices": {
    "users": {
      "total-documents": 1234,
      "last-indexed": "2026-01-04T20:30:00Z",
      "index-size-mb": 5.2
    },
    "items": {
      "total-documents": 5678,
      "last-indexed": "2026-01-04T20:30:00Z",
      "index-size-mb": 12.8
    }
  },
  "total-documents": 6912,
  "query-stats": {
    "total-queries": 15234,
    "avg-query-time-ms": 35,
    "cache-hit-rate": 0.85
  }
}
```

**Status Codes**:
- `200 OK` - Statistics retrieved
- `500 Internal Server Error` - Failed to retrieve stats

**Examples**:

```bash
curl "http://localhost:3000/api/search/stats"
```

---

## Query Syntax

### Basic Text Search

**Simple Word**:
```bash
# Search for "john"
curl "http://localhost:3000/api/search/users?q=john"
```

**Multiple Words** (implicit AND):
```bash
# Search for documents containing both "software" AND "engineer"
curl "http://localhost:3000/api/search/users?q=software+engineer"
```

**Phrase Search** (quoted):
```bash
# Search for exact phrase "software engineer"
curl "http://localhost:3000/api/search/users?q=%22software+engineer%22"
```

### Special Characters

The search engine safely handles all special characters:

```bash
# Apostrophes, quotes, hyphens - all work safely
curl "http://localhost:3000/api/search/users?q=O%27Brien"
curl "http://localhost:3000/api/search/users?q=Jean-Claude"
curl "http://localhost:3000/api/search/items?q=SKU-12345"
```

**SQL Injection Prevention**: All queries are parameterized and safe from SQL injection.

### Case Sensitivity

**All searches are case-insensitive**:

```bash
# These are equivalent:
curl "http://localhost:3000/api/search/users?q=john"
curl "http://localhost:3000/api/search/users?q=JOHN"
curl "http://localhost:3000/api/search/users?q=JoHn"
```

### Unicode Support

Full Unicode support including:

```bash
# Accented characters
curl "http://localhost:3000/api/search/users?q=José"

# Emojis
curl "http://localhost:3000/api/search/items?q=🔥"

# CJK characters
curl "http://localhost:3000/api/search/users?q=田中"
```

---

## Response Format

### Search Response Format

```json
{
  "results": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "John Smith",
      "email": "john@example.com",
      "bio": "Software engineer passionate about Clojure",
      "created-at": "2025-06-15T10:30:00Z",
      "updated-at": "2025-12-20T14:22:00Z",
      "score": 0.85,
      "rank": 1,
      "_highlights": {
        "name": "<mark>John</mark> Smith",
        "bio": "Software engineer passionate about <mark>Clojure</mark>"
      }
    },
    {
      "id": "660e8400-e29b-41d4-a716-446655440001",
      "name": "Johnny Doe",
      "email": "johnny@example.com",
      "bio": "Backend developer",
      "created-at": "2025-08-10T09:15:00Z",
      "updated-at": "2026-01-03T11:45:00Z",
      "score": 0.72,
      "rank": 2,
      "_highlights": {
        "name": "<mark>Johnny</mark> Doe"
      }
    }
  ],
  "total": 42,
  "max-score": 0.85,
  "page": {
    "from": 0,
    "size": 20
  },
  "took-ms": 15
}
```

**Field Descriptions**:

| Field | Type | Description |
|-------|------|-------------|
| `results` | array | Array of matching documents with scores and highlights |
| `results[].id` | uuid | Document unique identifier |
| `results[].score` | float | Relevance score (0-1, higher = more relevant) |
| `results[].rank` | integer | Position in results (1-based) |
| `results[]._highlights` | object | Highlighted fields with `<mark>` tags around matches |
| `total` | integer | Total number of matching documents |
| `max-score` | float | Highest score in result set |
| `page.from` | integer | Pagination offset (0-based) |
| `page.size` | integer | Number of results per page |
| `took-ms` | integer | Query execution time in milliseconds |

### Autocomplete Response Format

```json
{
  "suggestions": [
    {"value": "John Smith", "score": 0.95},
    {"value": "Johnny Doe", "score": 0.85},
    {"value": "John Anderson", "score": 0.75}
  ],
  "total": 3
}
```

**Field Descriptions**:

| Field | Type | Description |
|-------|------|-------------|
| `suggestions` | array | Array of suggestions sorted by score (descending) |
| `suggestions[].value` | string | Suggested text value |
| `suggestions[].score` | float | Relevance score (0-1) |
| `total` | integer | Number of suggestions returned |

### Error Response Format

```json
{
  "error": "Query parameter 'q' is required",
  "status": 400,
  "timestamp": "2026-01-04T20:45:00Z"
}
```

---

## Configuration

### Default Configuration

```clojure
;; resources/conf/dev/config.edn
{:boundary/search
 {:provider :postgresql
  :language "english"
  :pagination {:default-size 20
               :max-size 100}
  :highlighting {:pre-tag "<mark>"
                 :post-tag "</mark>"
                 :enabled? true}
  :ranking {:users {:recency-field :created-at
                    :recency-max-boost 2.0
                    :recency-decay-days 30}
            :items {:recency-field :created-at
                    :recency-max-boost 2.0
                    :recency-decay-days 90}}}}
```

### Configuration Options

#### Provider Settings

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:provider` | keyword | `:postgresql` | Search provider (`:postgresql` only currently) |
| `:language` | string | `"english"` | PostgreSQL text search language configuration |

#### Pagination Settings

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:default-size` | integer | `20` | Default number of results per page |
| `:max-size` | integer | `100` | Maximum allowed page size |

#### Highlighting Settings

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:pre-tag` | string | `"<mark>"` | HTML tag before highlighted term |
| `:post-tag` | string | `"</mark>"` | HTML tag after highlighted term |
| `:enabled?` | boolean | `true` | Enable highlighting by default |

#### Ranking Settings

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `:recency-field` | keyword | `:created-at` | Field to use for recency boost |
| `:recency-max-boost` | float | `2.0` | Maximum boost multiplier for newest docs (2.0 = 2x) |
| `:recency-decay-days` | integer | `30` (users), `90` (items) | Days until boost reaches zero |

### Environment Variables

No environment variables required for basic search functionality. PostgreSQL connection is configured via database settings.

---

## Performance

### Performance Benchmarks

Based on integration tests with PostgreSQL 18 on local development machine:

| Operation | Dataset Size | Average Time | Target | Status |
|-----------|--------------|--------------|--------|--------|
| User search | 100 docs | 15-30ms | < 100ms | ✅ Excellent |
| User search | 1,000 docs | 30-60ms | < 100ms | ✅ Good |
| Item search | 100 docs | 20-35ms | < 100ms | ✅ Excellent |
| Autocomplete | 1,000 docs | 10-20ms | < 50ms | ✅ Excellent |
| Bulk indexing | 100 docs | 800-1000ms | < 2s | ✅ Good |
| Concurrent searches | 10 concurrent | 40-80ms | < 150ms | ✅ Good |

### Performance by Dataset Size

| Documents | Search Time | Index Size | Notes |
|-----------|-------------|------------|-------|
| < 1,000 | 10-30ms | < 1MB | Excellent performance |
| 1K-10K | 30-60ms | 1-10MB | Good performance |
| 10K-100K | 60-150ms | 10-100MB | Acceptable, consider Meilisearch |
| 100K-1M | 150-500ms | 100MB-1GB | Consider Elasticsearch |
| > 1M | > 500ms | > 1GB | Use Elasticsearch/OpenSearch |

### Optimization Tips

#### 1. Database Indexes

Ensure GIN indexes exist:

```sql
-- Verify indexes
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE indexname LIKE '%search%';

-- Expected output:
-- users | users_search_idx | CREATE INDEX users_search_idx ON users USING gin (search_vector)
-- items | items_search_idx | CREATE INDEX items_search_idx ON items USING gin (search_vector)
```

#### 2. Query Optimization

- **Use pagination**: Always use `from` and `size` parameters
- **Limit highlighted fields**: Only highlight fields displayed in UI
- **Disable recency boost** if not needed: `boost_recent=false`
- **Use specific indexes**: Search users or items, not both

#### 3. Database Tuning

```sql
-- PostgreSQL configuration for better full-text search performance
-- Add to postgresql.conf

shared_buffers = 256MB              # Cache frequently accessed data
work_mem = 16MB                     # Memory for sorting and search operations
effective_cache_size = 1GB          # OS + PostgreSQL cache estimate
random_page_cost = 1.1              # SSD tuning
```

#### 4. Connection Pooling

Use connection pooling (HikariCP) with appropriate settings:

```clojure
{:db {:maximum-pool-size 15
      :minimum-idle 5
      :connection-timeout 30000
      :idle-timeout 600000}}
```

---

## Examples

### Example 1: Basic Search UI

**HTML/JavaScript**:

```html
<input type="text" id="search-box" placeholder="Search users...">
<div id="results"></div>

<script>
const searchBox = document.getElementById('search-box');
const resultsDiv = document.getElementById('results');

searchBox.addEventListener('input', async (e) => {
  const query = e.target.value;
  
  if (query.length < 2) {
    resultsDiv.innerHTML = '';
    return;
  }
  
  const response = await fetch(
    `/api/search/users?q=${encodeURIComponent(query)}&size=10`
  );
  const data = await response.json();
  
  resultsDiv.innerHTML = data.results.map(user => `
    <div class="result">
      <h3>${user._highlights?.name || user.name}</h3>
      <p>${user._highlights?.bio || user.bio}</p>
      <small>Score: ${user.score.toFixed(2)}</small>
    </div>
  `).join('');
});
</script>
```

### Example 2: Autocomplete Typeahead

**JavaScript**:

```javascript
const nameInput = document.getElementById('name-input');
const suggestionsDiv = document.getElementById('suggestions');

nameInput.addEventListener('input', async (e) => {
  const prefix = e.target.value;
  
  if (prefix.length < 2) {
    suggestionsDiv.innerHTML = '';
    return;
  }
  
  const response = await fetch(
    `/api/search/suggest?prefix=${encodeURIComponent(prefix)}&field=name&index=users&limit=5`
  );
  const data = await response.json();
  
  suggestionsDiv.innerHTML = data.suggestions.map(suggestion => `
    <div class="suggestion" data-value="${suggestion.value}">
      ${suggestion.value}
    </div>
  `).join('');
});

// Handle suggestion click
suggestionsDiv.addEventListener('click', (e) => {
  if (e.target.classList.contains('suggestion')) {
    nameInput.value = e.target.dataset.value;
    suggestionsDiv.innerHTML = '';
  }
});
```

### Example 3: Paginated Results

**JavaScript**:

```javascript
async function searchUsers(query, page = 0, size = 20) {
  const from = page * size;
  const response = await fetch(
    `/api/search/users?q=${encodeURIComponent(query)}&from=${from}&size=${size}`
  );
  const data = await response.json();
  
  displayResults(data.results);
  
  // Display pagination
  const totalPages = Math.ceil(data.total / size);
  displayPagination(page, totalPages, (newPage) => {
    searchUsers(query, newPage, size);
  });
}

function displayPagination(currentPage, totalPages, onPageChange) {
  const pagination = document.getElementById('pagination');
  
  // Previous button
  const prevDisabled = currentPage === 0 ? 'disabled' : '';
  let html = `<button ${prevDisabled} onclick="onPageChange(${currentPage - 1})">Previous</button>`;
  
  // Page numbers
  for (let i = 0; i < totalPages; i++) {
    const active = i === currentPage ? 'active' : '';
    html += `<button class="${active}" onclick="onPageChange(${i})">${i + 1}</button>`;
  }
  
  // Next button
  const nextDisabled = currentPage >= totalPages - 1 ? 'disabled' : '';
  html += `<button ${nextDisabled} onclick="onPageChange(${currentPage + 1})">Next</button>`;
  
  pagination.innerHTML = html;
}
```

### Example 4: Search with Filters (Future)

**Note**: Filters are not yet implemented but the API structure is ready.

```javascript
async function searchItems(query, filters = {}) {
  const params = new URLSearchParams({
    q: query,
    filters: JSON.stringify(filters)
  });
  
  const response = await fetch(`/api/search/items?${params}`);
  return await response.json();
}

// Usage
const results = await searchItems('laptop', {
  category: 'electronics',
  status: 'available',
  price_range: { min: 500, max: 1500 }
});
```

### Example 5: Clojure Client

**Clojure**:

```clojure
(ns myapp.search
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(defn search-users
  "Search users via API."
  [query {:keys [from size highlight?] :or {from 0 size 20 highlight? true}}]
  (let [response (http/get "http://localhost:3000/api/search/users"
                           {:query-params {:q query
                                          :from from
                                          :size size
                                          :highlight highlight?}
                            :as :json})]
    (:body response)))

;; Usage
(search-users "john" {:size 10})
;=> {:results [...] :total 42 :took-ms 15}

(search-users "engineer" {:from 20 :size 10 :highlight? false})
;=> {:results [...] :total 156 :took-ms 22}
```

---

## Migration Guide

### Adding Search to Existing Tables

#### Step 1: Create Migration

```sql
-- migrations/NNN_add_users_search.sql

-- Add search_vector column (GENERATED, auto-updates)
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS search_vector tsvector
    GENERATED ALWAYS AS (
      setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
      setweight(to_tsvector('english', coalesce(email, '')), 'B') ||
      setweight(to_tsvector('english', coalesce(bio, '')), 'C')
    ) STORED;

-- Create GIN index for fast search
CREATE INDEX IF NOT EXISTS users_search_idx 
  ON users USING GIN (search_vector);

-- Verify index
SELECT schemaname, tablename, indexname, indexdef
FROM pg_indexes
WHERE indexname = 'users_search_idx';
```

**Weight Meanings**:
- `'A'` - Highest weight (e.g., title, name) - 1.0x
- `'B'` - High weight (e.g., email, subtitle) - 0.4x
- `'C'` - Normal weight (e.g., bio, description) - 0.2x
- `'D'` - Low weight (e.g., tags, metadata) - 0.1x

#### Step 2: Configure Search

```clojure
;; config.edn
{:boundary/search
 {:provider :postgresql
  :language "english"
  :ranking {:users {:recency-field :created-at
                    :recency-max-boost 2.0
                    :recency-decay-days 30}}}}
```

#### Step 3: Add to System Wiring

```clojure
;; src/boundary/config.clj

(defn ig-config
  [config]
  (merge (core-system-config config)
         (user-module-config config)
         (search-module-config config)  ; Add this
         ...))

(defn- search-module-config
  [config]
  {:boundary/search-provider
   {:type :postgresql
    :ctx (ig/ref :boundary/db-context)
    :config (get config :boundary/search)}
   
   :boundary/search-service
   {:search-provider (ig/ref :boundary/search-provider)
    :config (get config :boundary/search)}
   
   :boundary/search-routes
   {:service (ig/ref :boundary/search-service)
    :config config}})
```

#### Step 4: Update HTTP Handler

```clojure
;; src/boundary/config.clj

:boundary/http-handler
{:config config
 :user-routes (ig/ref :boundary/user-routes)
 :search-routes (ig/ref :boundary/search-routes)  ; Add this
 ...}
```

```clojure
;; src/boundary/shell/system/wiring.clj

(defmethod ig/init-key :boundary/http-handler
  [_ {:keys [config user-routes search-routes]}]  ; Add search-routes
  (let [user-api-routes (or (:api user-routes) [])
        search-api-routes (or (:api search-routes) [])  ; Add this
        ...
        all-routes (concat static-routes
                           user-api-routes
                           search-api-routes  ; Add this
                           ...)]))
```

#### Step 5: Test Search

```bash
# Restart system
clojure -M:repl-clj
user=> (require '[integrant.repl :as ig-repl])
user=> (ig-repl/go)

# In another terminal, test search
curl "http://localhost:3000/api/search/users?q=test"
```

### Migrating from Custom Search

If you have custom SQL-based search, migration is straightforward:

**Before** (Custom SQL):
```clojure
(defn find-users-by-text [db query]
  (jdbc/execute! db
    ["SELECT * FROM users 
      WHERE name ILIKE ? OR email ILIKE ?
      ORDER BY created_at DESC
      LIMIT 20"
     (str "%" query "%")
     (str "%" query "%")]))
```

**After** (Full-Text Search):
```clojure
(require '[boundary.platform.search.ports :as search])

(defn find-users-by-text [search-service query]
  (search/search-users search-service query {:size 20}))
```

**Benefits**:
- ✅ Better relevance ranking (tf-idf)
- ✅ Highlighting of matched terms
- ✅ Recency boosting
- ✅ Better performance (GIN index vs. B-tree index)
- ✅ Handles word stemming (searches → search)

---

## Troubleshooting

### Problem: "No results found" but data exists

**Symptoms**: Search returns empty results even though matching data exists in database.

**Diagnosis**:
```sql
-- Check if search_vector column exists and has data
SELECT id, name, search_vector
FROM users
LIMIT 5;

-- If search_vector is NULL or empty, the column wasn't created properly
```

**Solution**:
```sql
-- Recreate search_vector column
ALTER TABLE users DROP COLUMN IF EXISTS search_vector;
ALTER TABLE users ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(email, '')), 'B')
  ) STORED;

-- Recreate index
DROP INDEX IF EXISTS users_search_idx;
CREATE INDEX users_search_idx ON users USING GIN (search_vector);
```

---

### Problem: Slow search performance (> 100ms)

**Symptoms**: Search takes longer than expected.

**Diagnosis**:
```sql
-- Check if GIN index exists
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'users' AND indexname LIKE '%search%';

-- Analyze query plan
EXPLAIN ANALYZE
SELECT * FROM users
WHERE search_vector @@ plainto_tsquery('english', 'john')
ORDER BY ts_rank(search_vector, plainto_tsquery('english', 'john')) DESC
LIMIT 20;

-- Should show "Bitmap Heap Scan" using GIN index
-- If shows "Seq Scan", index is missing or not being used
```

**Solutions**:

1. **Create missing index**:
```sql
CREATE INDEX users_search_idx ON users USING GIN (search_vector);
```

2. **Analyze table statistics**:
```sql
ANALYZE users;
```

3. **Increase work_mem** (for sorting large result sets):
```sql
SET work_mem = '16MB';
```

4. **Consider smaller page sizes**:
```bash
# Instead of size=100
curl "http://localhost:3000/api/search/users?q=john&size=20"
```

---

### Problem: Highlighting not working

**Symptoms**: `_highlights` field is missing or doesn't contain `<mark>` tags.

**Diagnosis**:
```bash
# Check if highlighting is enabled in request
curl "http://localhost:3000/api/search/users?q=john&highlight=true"

# Check application logs for errors
grep "highlighting" logs/boundary.log
```

**Solutions**:

1. **Enable highlighting explicitly**:
```bash
curl "http://localhost:3000/api/search/users?q=john&highlight=true"
```

2. **Check configuration**:
```clojure
{:boundary/search
 {:highlighting {:enabled? true
                 :pre-tag "<mark>"
                 :post-tag "</mark>"}}}
```

3. **Verify fields have content**:
```sql
SELECT id, name, email FROM users WHERE name IS NOT NULL LIMIT 5;
```

---

### Problem: Special characters cause errors

**Symptoms**: Searches with quotes, apostrophes fail with errors.

**This should NOT happen** - the implementation uses `plainto_tsquery` which safely handles all special characters.

**If you see errors**:

1. **Check PostgreSQL logs**:
```bash
tail -f /var/log/postgresql/postgresql-18-main.log
```

2. **Verify plainto_tsquery is used** (not to_tsquery):
```sql
-- Safe (should be in code):
SELECT * FROM users
WHERE search_vector @@ plainto_tsquery('english', 'O''Brien');

-- Unsafe (should NOT be in code):
SELECT * FROM users
WHERE search_vector @@ to_tsquery('english', 'O''Brien:*');  -- FAILS!
```

3. **Update to latest code** if using old implementation.

---

### Problem: Concurrent searches failing

**Symptoms**: Search works in development but fails under load in production.

**Diagnosis**:
```bash
# Check connection pool settings
grep -A5 "db:" config.edn

# Check active connections
psql -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'boundary_prod';"
```

**Solutions**:

1. **Increase connection pool size**:
```clojure
{:db {:maximum-pool-size 20    ; Increase from 15
      :minimum-idle 5
      :connection-timeout 30000}}
```

2. **Add retry logic** (already implemented in service layer).

3. **Monitor connection pool**:
```clojure
(require '[boundary.platform.shell.adapters.database.core :as db])
(db/get-pool-stats db-ctx)
```

---

### Problem: Search returns unexpected results

**Symptoms**: Results don't match expectations, irrelevant documents rank high.

**Diagnosis**:

1. **Check what's in search_vector**:
```sql
SELECT id, name, search_vector FROM users WHERE id = 'specific-uuid';
-- Verify expected terms are present
```

2. **Test query directly**:
```sql
SELECT 
  id, 
  name, 
  ts_rank(search_vector, plainto_tsquery('english', 'your-query')) AS score
FROM users
WHERE search_vector @@ plainto_tsquery('english', 'your-query')
ORDER BY score DESC
LIMIT 10;
```

**Solutions**:

1. **Adjust field weights** in GENERATED column:
```sql
-- Give more weight to name, less to bio
ALTER TABLE users DROP COLUMN search_vector;
ALTER TABLE users ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(name, '')), 'A') ||      -- 1.0x
    setweight(to_tsvector('english', coalesce(email, '')), 'B') ||     -- 0.4x
    setweight(to_tsvector('english', coalesce(bio, '')), 'D')          -- 0.1x (was C)
  ) STORED;
```

2. **Adjust recency boost**:
```clojure
;; Increase boost for newer documents
{:ranking {:users {:recency-max-boost 3.0      ; Was 2.0
                   :recency-decay-days 14}}}   ; Was 30
```

3. **Disable recency boost** for timeless content:
```bash
curl "http://localhost:3000/api/search/users?q=john&boost_recent=false"
```

---

### Problem: "Index does not exist" error

**Symptoms**: Reindex operation fails with "index users does not exist".

**This is a naming issue** - the system expects `users` or `items`, not custom names.

**Solutions**:

1. **Use correct index names**:
```bash
# Correct
curl -X POST "http://localhost:3000/api/search/reindex/users"
curl -X POST "http://localhost:3000/api/search/reindex/items"

# Incorrect
curl -X POST "http://localhost:3000/api/search/reindex/products"  # 400 error
```

2. **For custom indexes**, extend the service:
```clojure
;; In your module's search integration
(defn reindex-products [search-service]
  (ports/reindex search-service :products))
```

---

## Support & Feedback

### Reporting Issues

Found a bug? Please report it with:

1. **Search query** that caused the issue
2. **Expected behavior**
3. **Actual behavior**
4. **PostgreSQL version**: `SELECT version();`
5. **Application logs**: Check `logs/boundary.log`
6. **Database logs**: Check PostgreSQL logs

### Performance Issues

For performance problems, include:

1. **Dataset size**: Number of documents in index
2. **Query**: The search query causing slowness
3. **Query plan**: Output of `EXPLAIN ANALYZE` for the query
4. **Index status**: Output of `\d+ users` showing indexes
5. **Hardware**: CPU, RAM, disk type (SSD/HDD)

### Feature Requests

Want a new feature? Suggestions:

- Fuzzy search (typo tolerance)
- Faceted search (filtering by categories)
- Synonyms (CEO = Chief Executive Officer)
- Custom ranking formulas
- Search analytics dashboard

---

## Appendix

### PostgreSQL Full-Text Search Resources

- [PostgreSQL Full-Text Search Documentation](https://www.postgresql.org/docs/current/textsearch.html)
- [Understanding tsvector and tsquery](https://www.postgresql.org/docs/current/datatype-textsearch.html)
- [GIN Index Performance](https://www.postgresql.org/docs/current/gin.html)

### Alternative Search Solutions

| Solution | Best For | Deployment | Cost |
|----------|----------|------------|------|
| **PostgreSQL FTS** | < 100K docs, Simple needs | Built-in | Free |
| **Meilisearch** | < 1M docs, Typo tolerance | Docker/Binary | Free (OSS) |
| **Elasticsearch** | > 1M docs, Analytics | Docker/Cloud | Free (OSS) / Paid (Cloud) |
| **Algolia** | SaaS, No ops | Hosted | Paid only |

### Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-01-04 | Initial release with PostgreSQL support |

---

**End of API Reference**
