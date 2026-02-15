---
title: "Full-Text Search API"
weight: 30
description: "PostgreSQL-powered full-text search with highlighting, ranking, and autocomplete"
---
# Full-Text Search API Reference

**Version**: 1.0  
**Last Updated**: 2026-01-04  
**Status**: ✅ Production Ready

---

## Overview

Enterprise-grade full-text search using PostgreSQL's native engine:

- ✅ **Zero Dependencies**: No external services required
- ✅ **High Performance**: < 100ms average, sub-50ms for < 10K documents
- ✅ **Rich Features**: Highlighting, ranking, recency boosting, autocomplete
- ✅ **Production Ready**: SQL injection prevention, concurrent handling

### Supported Operations

| Operation | Description | Endpoint |
|-----------|-------------|----------|
| **User Search** | Full-text across names, emails, bios | `GET /api/search/users` |
| **Item Search** | Full-text across names, SKUs, locations | `GET /api/search/items` |
| **Autocomplete** | Prefix-based typeahead suggestions | `GET /api/search/suggest` |
| **Reindexing** | Rebuild search indexes | `POST /api/search/reindex/:index` |
| **Statistics** | Index statistics and metrics | `GET /api/search/stats` |

---

## Quick start

### Basic search

```bash
curl "http://localhost:3000/api/search/users?q=john"
```text

Response:
```json
{
  "results": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "John Smith",
      "email": "john@example.com",
      "score": 0.85,
      "rank": 1,
      "_highlights": {
        "name": "<mark>John</mark> Smith"
      }
    }
  ],
  "total": 1,
  "took-ms": 15
}
```bash

### With pagination

```bash
curl "http://localhost:3000/api/search/users?q=admin&from=20&size=10"
```bash

### Autocomplete

```bash
curl "http://localhost:3000/api/search/suggest?prefix=joh&field=name&index=users"
```bash

---

## API endpoints

### Search users

`GET /api/search/users`

**Query Parameters**:

| Parameter | Type | Required | Default | Max | Description |
|-----------|------|----------|---------|-----|-------------|
| `q` | string | ✅ | - | - | Search query text |
| `from` | integer | No | 0 | - | Pagination offset (0-based) |
| `size` | integer | No | 20 | 100 | Results per page |
| `highlight` | boolean | No | true | - | Enable highlighting |
| `highlight_fields` | string | No | name,email | - | Comma-separated fields |
| `boost_recent` | boolean | No | true | - | Boost recent documents |

**Response**: [Standard Format](#response-format)

**Examples**:
```bash
# Basic
curl "http://localhost:3000/api/search/users?q=john"

# With pagination
curl "http://localhost:3000/api/search/users?q=admin&from=0&size=10"

# Custom fields
curl "http://localhost:3000/api/search/users?q=clojure&highlight_fields=bio"
```bash

---

### Search items

`GET /api/search/items`

**Query Parameters**: Same as user search, with `highlight_fields` default: `name,sku,location`

**Examples**:
```bash
curl "http://localhost:3000/api/search/items?q=laptop"
curl "http://localhost:3000/api/search/items?q=SKU-12345"
```bash

---

### Autocomplete

`GET /api/search/suggest`

**Query Parameters**:

| Parameter | Type | Required | Default | Max | Description |
|-----------|------|----------|---------|-----|-------------|
| `prefix` | string | ✅ | - | - | Text prefix (e.g., "joh") |
| `field` | string | ✅ | - | - | Field (name, email, sku, location) |
| `index` | string | No | users | - | Index (users or items) |
| `limit` | integer | No | 10 | 50 | Max suggestions |

**Response**:
```json
{
  "suggestions": [
    {"value": "John Smith", "score": 0.95}
  ],
  "total": 1
}
```bash

---

### Reindex

`POST /api/search/reindex/:index`

Rebuild search index from database. Use after bulk imports or schema changes.

**Path Parameters**: `:index` = `users` or `items`

**When to Reindex**:
- After bulk data import
- After search field configuration changes
- After schema changes affecting search columns

**Performance**: ~100 docs/second, batched processing

---

### Statistics

`GET /api/search/stats`

Retrieve index statistics and query metrics.

**Response**:
```json
{
  "indices": {
    "users": {
      "total-documents": 1234,
      "index-size-mb": 5.2
    }
  },
  "query-stats": {
    "avg-query-time-ms": 35,
    "cache-hit-rate": 0.85
  }
}
```bash

---

## Query syntax

### Basic search

```bash
# Single word
curl "http://localhost:3000/api/search/users?q=john"

# Multiple words (AND)
curl "http://localhost:3000/api/search/users?q=software+engineer"

# Phrase search
curl "http://localhost:3000/api/search/users?q=%22software+engineer%22"
```bash

### Special characters

All special characters are safely handled:
```bash
curl "http://localhost:3000/api/search/users?q=O%27Brien"
curl "http://localhost:3000/api/search/items?q=SKU-12345"
```bash

### Case & Unicode

- **Case insensitive**: `john` = `JOHN` = `JoHn`
- **Unicode support**: Accents, emojis, CJK characters all work

---

## Response format

### Search response

```json
{
  "results": [
    {
      "id": "uuid",
      "name": "John Smith",
      "score": 0.85,
      "rank": 1,
      "_highlights": {
        "name": "<mark>John</mark> Smith"
      }
    }
  ],
  "total": 42,
  "max-score": 0.85,
  "page": {"from": 0, "size": 20},
  "took-ms": 15
}
```text

**Key Fields**:
- `score`: Relevance (0-1, higher = more relevant)
- `rank`: Position in results (1-based)
- `_highlights`: Fields with `<mark>` tags around matches
- `took-ms`: Query execution time

### Error response

```json
{
  "error": "Query parameter 'q' is required",
  "status": 400,
  "timestamp": "2026-01-04T20:45:00Z"
}
```bash

---

## Configuration

### Default config

```clojure
{:boundary/search
 {:provider :postgresql
  :language "english"
  :pagination {:default-size 20 :max-size 100}
  :highlighting {:pre-tag "<mark>" :post-tag "</mark>"}
  :ranking {:users {:recency-field :created-at
                    :recency-max-boost 2.0
                    :recency-decay-days 30}}}}
```bash

### Key options

| Option | Default | Description |
|--------|---------|-------------|
| `:language` | `"english"` | PostgreSQL text search language |
| `:default-size` | `20` | Default results per page |
| `:max-size` | `100` | Maximum page size |
| `:recency-max-boost` | `2.0` | Max boost for newest docs (2x) |
| `:recency-decay-days` | `30` (users), `90` (items) | Days until boost reaches zero |

---

## Performance

### Benchmarks

PostgreSQL 18 on local development machine:

| Operation | Dataset Size | Avg Time | Target | Status |
|-----------|--------------|----------|--------|--------|
| User search | 1,000 docs | 30-60ms | < 100ms | ✅ Good |
| Autocomplete | 1,000 docs | 10-20ms | < 50ms | ✅ Excellent |
| Concurrent (10x) | - | 40-80ms | < 150ms | ✅ Good |

### Dataset Guidelines

| Documents | Search Time | Index Size | Recommendation |
|-----------|-------------|------------|----------------|
| < 10K | 10-60ms | < 10MB | Excellent, use PostgreSQL |
| 10K-100K | 60-150ms | 10-100MB | Good, consider Meilisearch if needed |
| > 100K | > 150ms | > 100MB | Consider Elasticsearch |

### Optimization

**Database Indexes** - Verify GIN indexes exist:
```sql
SELECT indexname FROM pg_indexes WHERE indexname LIKE '%search%';
-- Expected: users_search_idx, items_search_idx
```text

**Query Optimization**:
- Always paginate with `from` and `size`
- Limit `highlight_fields` to displayed fields
- Disable `boost_recent` if not needed
- Use specific indexes (users or items)

**PostgreSQL Tuning**:
```sql
-- Add to postgresql.conf
shared_buffers = 256MB
work_mem = 16MB
effective_cache_size = 1GB
random_page_cost = 1.1  -- For SSD
```bash

---

## Integration examples

### Basic search UI

```html
<input type="text" id="search" placeholder="Search...">
<div id="results"></div>

<script>
document.getElementById('search').addEventListener('input', async (e) => {
  const q = e.target.value;
  if (q.length < 2) return;
  
  const res = await fetch(`/api/search/users?q=${encodeURIComponent(q)}&size=10`);
  const data = await res.json();
  
  document.getElementById('results').innerHTML = data.results.map(u => `
    <div><h3>${u._highlights?.name || u.name}</h3></div>
  `).join('');
});
</script>
```bash

### Autocomplete

```javascript
nameInput.addEventListener('input', async (e) => {
  const prefix = e.target.value;
  if (prefix.length < 2) return;
  
  const res = await fetch(
    `/api/search/suggest?prefix=${encodeURIComponent(prefix)}&field=name&index=users`
  );
  const {suggestions} = await res.json();
  
  showSuggestions(suggestions);
});
```bash

### Pagination

```javascript
async function search(query, page = 0) {
  const res = await fetch(
    `/api/search/users?q=${encodeURIComponent(query)}&from=${page * 20}&size=20`
  );
  const data = await res.json();
  
  displayResults(data.results);
  displayPagination(page, Math.ceil(data.total / 20));
}
```bash

---

## Migration guide

### From no search

**1. Run Migrations** - Create search columns and indexes:
```bash
clojure -M:migrate up
```text

Adds `search_vector` columns with GIN indexes to `users` and `items` tables.

**2. Configure Search** - Add to config:
```clojure
{:boundary/search
 {:provider :postgresql
  :language "english"}}
```text

**3. Initial Reindex**:
```bash
curl -X POST "http://localhost:3000/api/search/reindex/users"
curl -X POST "http://localhost:3000/api/search/reindex/items"
```bash

### Adding custom fields

**1. Update GENERATED Column**:
```sql
ALTER TABLE users DROP COLUMN search_vector;
ALTER TABLE users ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(email, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(bio, '')), 'C')  -- NEW
  ) STORED;

CREATE INDEX users_search_idx ON users USING GIN (search_vector);
```text

**2. Reindex**:
```bash
curl -X POST "http://localhost:3000/api/search/reindex/users"
```bash

---

## Troubleshooting

### No results

**Check index exists**:
```sql
SELECT count(*) FROM users WHERE search_vector IS NOT NULL;
```text

**Reindex if needed**:
```bash
curl -X POST "http://localhost:3000/api/search/reindex/users"
```bash

---

### Slow performance (> 100ms)

**Check GIN index**:
```sql
EXPLAIN ANALYZE
SELECT * FROM users
WHERE search_vector @@ plainto_tsquery('english', 'john')
LIMIT 20;
-- Should show "Bitmap Heap Scan" with GIN index
```bash

**Solutions**:
- Create missing index: `CREATE INDEX users_search_idx ON users USING GIN (search_vector);`
- Analyze table: `ANALYZE users;`
- Increase work_mem: `SET work_mem = '16MB';`
- Reduce page size: Use `size=20` instead of `size=100`

---

### Highlighting not working

**Enable explicitly**:
```bash
curl "http://localhost:3000/api/search/users?q=john&highlight=true"
```text

**Check config**:
```clojure
{:highlighting {:enabled? true :pre-tag "<mark>" :post-tag "</mark>"}}
```bash

---

### Unexpected results

**Check search_vector content**:
```sql
SELECT id, name, search_vector FROM users LIMIT 5;
```text

**Adjust field weights**:
```sql
-- Give more weight to name vs bio
ALTER TABLE users DROP COLUMN search_vector;
ALTER TABLE users ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(name, '')), 'A') ||   -- 1.0x
    setweight(to_tsvector('english', coalesce(bio, '')), 'D')       -- 0.1x (was C)
  ) STORED;
```text

**Adjust recency boost**:
```clojure
{:ranking {:users {:recency-max-boost 3.0      ; Increase from 2.0
                   :recency-decay-days 14}}}   ; Shorten from 30
```

---

## See also

- [API Pagination](pagination.md) - Cursor and offset pagination
- [Database Setup](../guides/database-setup.md) - PostgreSQL configuration
- [PostgreSQL Full-Text Search Docs](https://www.postgresql.org/docs/current/textsearch.html)

### Alternative solutions

| Solution | Best For | Cost |
|----------|----------|------|
| **PostgreSQL FTS** | < 100K docs | Free |
| **Meilisearch** | < 1M docs, typo tolerance | Free (OSS) |
| **Elasticsearch** | > 1M docs, analytics | Free (OSS) / Paid |
| **Algolia** | SaaS, no ops | Paid only |

---

**Version**: 1.0 (2026-01-04)
