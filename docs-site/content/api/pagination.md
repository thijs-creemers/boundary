---
title: "API Pagination"
weight: 10
description: "Enterprise-grade pagination for Boundary Framework APIs with offset and cursor strategies"
---

# API Pagination Guide

**Enterprise-grade pagination for Boundary Framework APIs**

---

## Overview

Boundary provides two pagination strategies to handle different use cases:

1. **Offset-Based Pagination** (default) - Simple, familiar, good for small-to-medium datasets
2. **Cursor-Based Pagination** (opt-in) - Consistent performance for large datasets

Both strategies follow RFC 5988 for Link headers and provide a consistent API interface.

---

## Quick start

### Basic usage (offset pagination)

```bash
# First page (default: 20 items)
curl -X GET "http://localhost:3000/api/users"

# Custom page size
curl -X GET "http://localhost:3000/api/users?limit=50"

# Second page
curl -X GET "http://localhost:3000/api/users?limit=50&offset=50"
```bash

### Response format

```json
{
  "users": [
    {"id": "123...", "email": "user1@example.com", "name": "User 1"},
    {"id": "456...", "email": "user2@example.com", "name": "User 2"}
  ],
  "pagination": {
    "type": "offset",
    "total": 1000,
    "offset": 0,
    "limit": 20,
    "hasNext": true,
    "hasPrev": false,
    "page": 1,
    "pages": 50
  }
}
```bash

### Link headers (RFC 5988)

```http
Link: </api/users?limit=20&offset=0>; rel="first",
      </api/users?limit=20&offset=20>; rel="next",
      </api/users?limit=20&offset=980>; rel="last",
      </api/users?limit=20&offset=0>; rel="self"
```text

---

## Pagination strategies

### Offset-based pagination

**When to use:**
- Small to medium datasets (< 100,000 items)
- Need to jump to specific pages
- Need total count of items
- Familiar pagination pattern (page numbers)

**Query Parameters:**

| Parameter | Type | Default | Max | Description |
|-----------|------|---------|-----|-------------|
| `limit` | int | 20 | 100 | Items per page |
| `offset` | int | 0 | - | Starting position |

**Example:**
```bash
# Page 1 (items 0-19)
GET /api/users?limit=20&offset=0

# Page 2 (items 20-39)
GET /api/users?limit=20&offset=20

# Page 3 (items 40-59)
GET /api/users?limit=20&offset=40
```text

**Response:**
```json
{
  "users": [...],
  "pagination": {
    "type": "offset",
    "total": 1000,
    "offset": 20,
    "limit": 20,
    "hasNext": true,
    "hasPrev": true,
    "page": 2,
    "pages": 50
  }
}
```bash

**Pros:**
- ✅ Simple to implement and understand
- ✅ Can jump to any page
- ✅ Shows total pages/items
- ✅ Familiar to users (page numbers)

**Cons:**
- ❌ Performance degrades with large offsets (database must skip rows)
- ❌ Inconsistent results if data changes during pagination
- ❌ `COUNT(*)` query can be expensive on large tables

---

### Cursor-Based Pagination

**When to use:**
- Large datasets (> 100,000 items)
- Real-time data feeds
- Infinite scroll UIs
- Need consistent performance

**Query Parameters:**

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limit` | int | 20 | Items per page |
| `cursor` | string | - | Opaque pagination token |

**Example:**
```bash
# First page
GET /api/users?limit=20

# Next page (use cursor from previous response)
GET /api/users?limit=20&cursor=eyJpZCI6MTIzLCJjcmVhdGVkX2F0IjoiMjAyNC0wMS0wMVQwMDowMDowMFoifQ==

# Previous page (use prevCursor from response)
GET /api/users?limit=20&cursor=eyJpZCI6MTAzLCJjcmVhdGVkX2F0IjoiMjAyMy0xMi0zMVQwMDowMDowMFoifQ==
```text

**Response:**
```json
{
  "users": [...],
  "pagination": {
    "type": "cursor",
    "limit": 20,
    "nextCursor": "eyJpZCI6MTQzLCJjcmVhdGVkX2F0IjoiMjAyNC0wMS0wMlQwMDowMDowMFoifQ==",
    "prevCursor": "eyJpZCI6MTAzLCJjcmVhdGVkX2F0IjoiMjAyMy0xMi0zMVQwMDowMDowMFoifQ==",
    "hasNext": true,
    "hasPrev": true
  }
}
```text

**Cursor Format** (Base64-encoded JSON):
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "created_at": "2024-01-01T00:00:00Z"
}
```bash

**Pros:**
- ✅ Consistent performance regardless of position
- ✅ Stable results even if data changes
- ✅ No expensive `COUNT(*)` queries
- ✅ Perfect for infinite scroll

**Cons:**
- ❌ Cannot jump to specific page
- ❌ No total count of items
- ❌ Cursors are opaque (not human-readable)

---

## RFC 5988 Link Headers

All paginated responses include RFC 5988 Link headers for navigation:

### Link relations

| Relation | Description | Present When |
|----------|-------------|--------------|
| `first` | First page | Always |
| `prev` | Previous page | When `hasPrev: true` |
| `next` | Next page | When `hasNext: true` |
| `last` | Last page | Offset pagination only |
| `self` | Current page | Always |

### Example header

```http
Link: </api/users?limit=20&offset=0>; rel="first",
      </api/users?limit=20&offset=0>; rel="prev",
      </api/users?limit=20&offset=40>; rel="next",
      </api/users?limit=20&offset=980>; rel="last",
      </api/users?limit=20&offset=20>; rel="self"
```bash

### Parsing link headers

**JavaScript:**
```javascript
function parseLink(linkHeader) {
  const links = {};
  linkHeader.split(',').forEach(part => {
    const [url, rel] = part.trim().split('; ');
    const cleanUrl = url.slice(1, -1); // Remove < >
    const relation = rel.match(/rel="(.+)"/)[1];
    links[relation] = cleanUrl;
  });
  return links;
}

// Usage
const links = parseLink(response.headers.get('Link'));
const nextUrl = links.next; // "/api/users?limit=20&offset=40"
```text

**Python:**
```python
import requests

response = requests.get('http://localhost:3000/api/users')
links = response.links
next_url = links.get('next', {}).get('url')
```text

**Clojure:**
```clojure
(require '[clj-http.client :as http])

(defn parse-link-header [link-str]
  (into {}
    (for [part (clojure.string/split link-str #",")]
      (let [[url rel] (clojure.string/split (clojure.string/trim part) #"; ")
            clean-url (subs url 1 (dec (count url)))
            relation (second (re-find #"rel=\"(.+)\"" rel))]
        [relation clean-url]))))

(let [response (http/get "http://localhost:3000/api/users")
      links (parse-link-header (get-in response [:headers "link"]))]
  (:next links))
```bash

---

## Client examples

### JavaScript (Fetch API)

**Fetch All Pages (Offset)**:
```javascript
async function fetchAllUsers() {
  const allUsers = [];
  let offset = 0;
  const limit = 100;
  
  while (true) {
    const response = await fetch(
      `http://localhost:3000/api/users?limit=${limit}&offset=${offset}`
    );
    const data = await response.json();
    
    allUsers.push(...data.users);
    
    if (!data.pagination.hasNext) break;
    offset += limit;
  }
  
  return allUsers;
}
```text

**Fetch All Pages (Cursor)**:
```javascript
async function fetchAllUsers() {
  const allUsers = [];
  let cursor = null;
  const limit = 100;
  
  while (true) {
    const url = cursor
      ? `http://localhost:3000/api/users?limit=${limit}&cursor=${cursor}`
      : `http://localhost:3000/api/users?limit=${limit}`;
    
    const response = await fetch(url);
    const data = await response.json();
    
    allUsers.push(...data.users);
    
    if (!data.pagination.hasNext) break;
    cursor = data.pagination.nextCursor;
  }
  
  return allUsers;
}
```bash

---

### Python (requests)

**Fetch All Pages (Offset)**:
```python
import requests

def fetch_all_users():
    base_url = "http://localhost:3000/api/users"
    all_users = []
    offset = 0
    limit = 100
    
    while True:
        response = requests.get(base_url, params={"limit": limit, "offset": offset})
        data = response.json()
        
        all_users.extend(data["users"])
        
        if not data["pagination"]["hasNext"]:
            break
        
        offset += limit
    
    return all_users
```text

**Using Link Headers**:
```python
def fetch_all_users_with_links():
    base_url = "http://localhost:3000/api/users"
    all_users = []
    url = base_url
    
    while url:
        response = requests.get(url)
        data = response.json()
        
        all_users.extend(data["users"])
        
        # Get next URL from Link header
        url = response.links.get("next", {}).get("url")
    
    return all_users
```bash

---

### Clojure (clj-http)

**Fetch All Pages (Offset)**:
```clojure
(require '[clj-http.client :as http])

(defn fetch-all-users
  [base-url]
  (loop [offset 0
         limit 100
         all-users []]
    (let [response (http/get (str base-url "/api/users")
                            {:query-params {:limit limit :offset offset}
                             :as :json})
          users (-> response :body :users)
          pagination (-> response :body :pagination)]
      (if (:hasNext pagination)
        (recur (+ offset limit) limit (concat all-users users))
        (concat all-users users)))))
```text

**Using Link Headers**:
```clojure
(defn fetch-all-users-with-links
  [base-url]
  (loop [url (str base-url "/api/users")
         all-users []]
    (let [response (http/get url {:as :json})
          users (-> response :body :users)
          link-header (get-in response [:headers "link"])
          links (when link-header (parse-link-header link-header))
          next-url (:next links)]
      (if next-url
        (recur next-url (concat all-users users))
        (concat all-users users)))))
```bash

---

## Configuration

**Location**: `resources/conf/dev/config.edn`

```clojure
{:boundary/pagination
 {:default-limit 20           ; Default items per page
  :max-limit 100              ; Maximum allowed limit
  :default-type :offset       ; :offset or :cursor
  :enable-link-headers true}} ; Include RFC 5988 Link headers
```bash

---

## Performance guidelines

### Offset pagination performance

| Dataset Size | Offset Range | Performance | Recommendation |
|--------------|--------------|-------------|----------------|
| < 10,000 | Any | Fast (< 10ms) | ✅ Use offset |
| 10,000 - 100,000 | 0-1,000 | Good (10-50ms) | ✅ Use offset |
| 10,000 - 100,000 | > 1,000 | Degrading (50-200ms) | ⚠️ Consider cursor |
| > 100,000 | Any | Slow (> 200ms) | ❌ Use cursor |

### Cursor pagination performance

| Dataset Size | Position | Performance | Recommendation |
|--------------|----------|-------------|----------------|
| Any | Any | Consistent (5-20ms) | ✅ Always fast |

**Key Insight**: Cursor pagination uses indexed WHERE clauses instead of OFFSET, maintaining constant performance regardless of position.

---

## Best practices

### 1. Choose the right strategy

**Use Offset Pagination When:**
- Dataset is small (< 100,000 items)
- Users need page numbers (e.g., search results)
- Users need to jump to specific pages
- Total count is important

**Use Cursor Pagination When:**
- Dataset is large (> 100,000 items)
- Building infinite scroll
- Real-time data feeds
- Performance is critical

### 2. Set Reasonable Limits

```clojure
;; Good: Reasonable defaults
{:default-limit 20
 :max-limit 100}

;; Bad: Too large (server overload)
{:default-limit 1000
 :max-limit 10000}
```bash

### 3. Cache COUNT(*) Queries

For offset pagination, cache the total count:

```clojure
(defn find-users-paginated
  [repository params]
  (let [cached-total (cache/get "users:total")
        total (or cached-total
                  (let [count (repository/count-users repository)]
                    (cache/put "users:total" count {:ttl 300}) ; 5 min cache
                    count))
        users (repository/find-users repository params)]
    {:users users
     :pagination (calculate-offset-pagination total (:offset params) (:limit params))}))
```bash

### 4. Use Link Headers

Always respect Link headers in client code:

```javascript
// Good: Use Link headers for navigation
const nextUrl = response.links.next;
fetch(nextUrl);

// Bad: Manually construct URLs
const nextOffset = currentOffset + limit;
fetch(`/api/users?offset=${nextOffset}&limit=${limit}`);
```bash

### 5. Validate Parameters

```clojure
(defn validate-pagination-params
  [{:keys [limit offset] :or {limit 20 offset 0}}]
  (cond
    (< limit 1)    {:error "limit must be at least 1"}
    (> limit 100)  {:error "limit must be at most 100"}
    (< offset 0)   {:error "offset must be non-negative"}
    :else          {:valid? true :limit limit :offset offset}))
```bash

---

## Troubleshooting

### Problem: Slow Pagination at High Offsets

**Symptom**: Queries take seconds when `offset > 10000`

**Solution**: Switch to cursor pagination

```bash
# Before (slow at high offsets)
GET /api/users?limit=20&offset=50000

# After (consistently fast)
GET /api/users?limit=20&cursor=eyJ...
```bash

### Problem: Inconsistent Results During Pagination

**Symptom**: Items appear twice or are skipped during pagination

**Cause**: Data changed between requests (new items inserted)

**Solution**: Use cursor pagination (stable results)

### Problem: Link Headers Not Appearing

**Check Configuration**:
```clojure
;; Ensure Link headers are enabled
{:boundary/pagination
 {:enable-link-headers true}}
```text

**Check Middleware**:
```clojure
;; Ensure pagination middleware is applied
(-> handler
    (wrap-pagination config)
    (wrap-defaults site-defaults))
```text

### Problem: "Cursor invalid" Error

**Cause**: Malformed or expired cursor

**Solution**: Cursors are opaque tokens - always get them from API responses, never construct manually:

```javascript
// Good: Use cursor from response
const cursor = data.pagination.nextCursor;
fetch(`/api/users?cursor=${cursor}`);

// Bad: Construct cursor manually
const cursor = btoa(JSON.stringify({id: 123})); // Don't do this!
```bash

---

## API reference

### Pagination query parameters

```
GET /api/users?limit=50&offset=100
GET /api/users?limit=50&cursor=eyJ...
```text

| Parameter | Type | Default | Max | Required | Description |
|-----------|------|---------|-----|----------|-------------|
| `limit` | integer | 20 | 100 | No | Items per page |
| `offset` | integer | 0 | - | No | Starting position (offset mode) |
| `cursor` | string | - | - | No | Pagination token (cursor mode) |

### Response format

**Offset Pagination Response**:
```json
{
  "data": [...],
  "pagination": {
    "type": "offset",
    "total": 1000,
    "offset": 0,
    "limit": 20,
    "hasNext": true,
    "hasPrev": false,
    "page": 1,
    "pages": 50
  }
}
```text

**Cursor Pagination Response**:
```json
{
  "data": [...],
  "pagination": {
    "type": "cursor",
    "limit": 20,
    "nextCursor": "eyJ...",
    "prevCursor": null,
    "hasNext": true,
    "hasPrev": false
  }
}
```bash

### HTTP Headers

**Request Headers**:
```http
Accept: application/vnd.boundary.v1+json
```text

**Response Headers**:
```http
Link: </api/users?limit=20&offset=20>; rel="next"
X-API-Version: v1
```

---

## See also

- [Operations Guide](../guides/operations) - Production deployment
- [RFC 5988: Web Linking](https://datatracker.ietf.org/doc/html/rfc5988) - Link header specification

---

**Last Updated**: January 4, 2026  
**Version**: 1.0.0  
**Status**: Production Ready
