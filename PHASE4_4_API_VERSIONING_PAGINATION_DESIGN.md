# Phase 4.4: API Versioning & Pagination - Design Document

## Executive Summary

This document outlines the design and implementation strategy for Phase 4.4, which adds enterprise-grade API versioning and pagination capabilities to the Boundary Framework.

**Goals**:
1. Support multiple API versions concurrently (backward compatibility)
2. Provide both offset-based and cursor-based pagination
3. Follow RFC 5988 for Link headers
4. Maintain Functional Core / Imperative Shell architecture
5. Zero breaking changes to existing APIs

**Timeline**: 1 week  
**Status**: 🚧 In Design

---

## 1. API Versioning Strategy

### 1.1 Versioning Approach

**Decision: URL-based versioning** (recommended by Phase 3 plan)

**Rationale**:
- ✅ **Explicit**: Version is immediately visible in URL
- ✅ **Cacheable**: Different versions have different URLs
- ✅ **Simple**: No custom headers required
- ✅ **Browser-friendly**: Easy to test with curl/browser
- ✅ **Documentation-friendly**: Clear version separation

**Alternative Considered: Header-based versioning**
- ❌ Less visible (hidden in headers)
- ❌ Harder to cache (same URL, different versions)
- ❌ More complex client implementation
- ✅ Cleaner URLs (but not worth the trade-offs)

### 1.2 URL Structure

**Current**:
```
GET /api/users
POST /api/users
```

**Versioned**:
```
GET /api/v1/users
POST /api/v1/users
GET /api/v2/users (future)
POST /api/v2/users (future)
```

**Special Cases**:
- `/api` without version → redirects to latest stable (v1)
- `/api/v0` → reserved for experimental/unstable features
- `/api/latest` → always points to most recent stable

### 1.3 Version Lifecycle

**Stages**:
1. **Experimental (v0)**: Breaking changes allowed, no guarantees
2. **Stable (v1, v2, ...)**: Backward compatible within major version
3. **Deprecated**: Still functional, but marked for removal
4. **Sunset**: Removed after deprecation period (6 months minimum)

**Version Headers** (informational):
```http
X-API-Version: v1
X-API-Version-Latest: v2
X-API-Deprecated: true
X-API-Sunset: 2026-12-31
```

### 1.4 Implementation Strategy

**Route Structure**:
```clojure
;; Current (backward compatible)
{:api [{:path "/users" :methods {:get ...}}]}

;; Versioned
{:api {:v1 [{:path "/users" :methods {:get ...}}]
       :v2 [{:path "/users" :methods {:get ...}}]}}
```

**Router Composition**:
```clojure
;; Mount each version under versioned prefix
(def routes
  ["/api"
   ["/v1" v1-routes]
   ["/v2" v2-routes]
   ["" default-routes]])  ; Redirect to latest
```

**Version Extraction**:
```clojure
(defn extract-version
  "Extract API version from request path."
  [request]
  (or (get-in request [:path-params :version])
      (get-in request [:headers "x-api-version"])
      :v1))  ; Default to v1
```

---

## 2. Pagination Design

### 2.1 Pagination Types

#### Offset-Based Pagination

**Use Case**: Simple, familiar, good for small-to-medium datasets

**Query Parameters**:
```
GET /api/v1/users?limit=20&offset=0
```

**Response**:
```json
{
  "data": [...],
  "pagination": {
    "total": 1000,
    "offset": 0,
    "limit": 20,
    "hasNext": true,
    "hasPrev": false
  }
}
```

**Link Headers (RFC 5988)**:
```http
Link: </api/v1/users?limit=20&offset=20>; rel="next",
      </api/v1/users?limit=20&offset=980>; rel="last",
      </api/v1/users?limit=20&offset=0>; rel="first"
```

**Pros**:
- Simple to implement
- Familiar to developers
- Easy to jump to specific page

**Cons**:
- Performance degrades with large offsets
- Inconsistent results if data changes during pagination
- `COUNT(*)` queries can be expensive

#### Cursor-Based Pagination

**Use Case**: Large datasets, real-time data, infinite scroll

**Query Parameters**:
```
GET /api/v1/users?limit=20&cursor=eyJpZCI6MTIzLCJjcmVhdGVkX2F0IjoiMjAyNC0wMS0wMVQwMDowMDowMFoifQ==
```

**Response**:
```json
{
  "data": [...],
  "pagination": {
    "limit": 20,
    "nextCursor": "eyJpZCI6MTQzLCJjcmVhdGVkX2F0IjoiMjAyNC0wMS0wMlQwMDowMDowMFoifQ==",
    "prevCursor": "eyJpZCI6MTAzLCJjcmVhdGVkX2F0IjoiMjAyMy0xMi0zMVQwMDowMDowMFoifQ==",
    "hasNext": true,
    "hasPrev": true
  }
}
```

**Cursor Format** (Base64-encoded JSON):
```json
{
  "id": 123,
  "created_at": "2024-01-01T00:00:00Z"
}
```

**Link Headers (RFC 5988)**:
```http
Link: </api/v1/users?limit=20&cursor=eyJ...>; rel="next",
      </api/v1/users?limit=20&cursor=eyJ...>; rel="prev"
```

**Pros**:
- ✅ Consistent performance with large datasets
- ✅ Stable results even with data changes
- ✅ No `COUNT(*)` overhead
- ✅ Works well with infinite scroll

**Cons**:
- ❌ Can't jump to specific page
- ❌ Slightly more complex to implement
- ❌ Cursor must encode sort key

### 2.2 Query Parameters

**Standard Parameters**:
```
limit     - Number of items per page (default: 20, max: 100)
offset    - Starting position (offset pagination)
cursor    - Opaque pagination token (cursor pagination)
sort      - Sort field (e.g., "created_at", "-name" for desc)
```

**Example**:
```
GET /api/v1/users?limit=50&offset=100&sort=-created_at
GET /api/v1/users?limit=50&cursor=eyJ...&sort=-created_at
```

### 2.3 Response Format

**Envelope Response** (consistent structure):
```json
{
  "data": [...],
  "pagination": {
    "type": "offset",
    "total": 1000,
    "offset": 0,
    "limit": 20,
    "hasNext": true,
    "hasPrev": false
  },
  "meta": {
    "version": "v1",
    "timestamp": "2024-01-04T10:00:00Z"
  }
}
```

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
  },
  "meta": {
    "version": "v1",
    "timestamp": "2024-01-04T10:00:00Z"
  }
}
```

---

## 3. Functional Core / Imperative Shell Architecture

### 3.1 Core Layer (Pure Functions)

**Location**: `src/boundary/platform/core/pagination/`

#### `pagination.clj` - Pure Pagination Logic
```clojure
(ns boundary.platform.core.pagination)

(defn calculate-offset-pagination
  "Calculate offset pagination metadata.
   
   Args:
     total - Total number of items
     offset - Current offset
     limit - Items per page
     
   Returns:
     {:total     int
      :offset    int
      :limit     int
      :has-next? bool
      :has-prev? bool
      :page      int
      :pages     int}
      
   Pure: true"
  [total offset limit]
  {:total     total
   :offset    offset
   :limit     limit
   :has-next? (< (+ offset limit) total)
   :has-prev? (> offset 0)
   :page      (inc (quot offset limit))
   :pages     (int (Math/ceil (/ total limit)))})

(defn validate-pagination-params
  "Validate pagination parameters.
   
   Returns:
     {:valid? bool
      :errors map
      :params map}
      
   Pure: true"
  [params]
  (let [limit  (or (:limit params) 20)
        offset (or (:offset params) 0)
        errors (cond-> {}
                 (< limit 1)    (assoc :limit "Must be at least 1")
                 (> limit 100)  (assoc :limit "Must be at most 100")
                 (< offset 0)   (assoc :offset "Must be non-negative"))]
    {:valid? (empty? errors)
     :errors errors
     :params {:limit limit :offset offset}}))

(defn create-cursor
  "Create pagination cursor from item.
   
   Args:
     item - Item to create cursor from
     sort-key - Field to use for cursor (e.g., :created-at)
     
   Returns:
     Base64-encoded cursor string
     
   Pure: false (uses current time if needed)"
  [item sort-key]
  ;; Implementation in shell layer (needs JSON encoding)
  )

(defn decode-cursor
  "Decode pagination cursor.
   
   Args:
     cursor - Base64-encoded cursor string
     
   Returns:
     {:id uuid :sort-value value}
     
   Pure: false (needs JSON decoding)"
  [cursor]
  ;; Implementation in shell layer
  )
```

#### `versioning.clj` - Version Comparison Logic
```clojure
(ns boundary.platform.core.versioning)

(defn parse-version
  "Parse version string to map.
   
   Args:
     version - Version string (e.g., 'v1', 'v2')
     
   Returns:
     {:major int :minor int :patch int}
     
   Pure: true"
  [version]
  (let [[_ major minor patch] (re-matches #"v(\d+)(?:\.(\d+))?(?:\.(\d+))?" version)]
    {:major (Integer/parseInt major)
     :minor (if minor (Integer/parseInt minor) 0)
     :patch (if patch (Integer/parseInt patch) 0)}))

(defn compare-versions
  "Compare two version strings.
   
   Returns:
     -1 (v1 < v2), 0 (equal), 1 (v1 > v2)
     
   Pure: true"
  [v1 v2]
  (let [p1 (parse-version v1)
        p2 (parse-version v2)]
    (compare [(:major p1) (:minor p1) (:patch p1)]
             [(:major p2) (:minor p2) (:patch p2)])))

(defn is-deprecated?
  "Check if version is deprecated.
   
   Pure: true"
  [version config]
  (contains? (:deprecated-versions config) version))

(defn get-sunset-date
  "Get sunset date for version.
   
   Pure: true"
  [version config]
  (get-in config [:sunset-dates version]))
```

### 3.2 Shell Layer (I/O Operations)

**Location**: `src/boundary/platform/shell/pagination/`

#### `cursor.clj` - Cursor Encoding/Decoding
```clojure
(ns boundary.platform.shell.pagination.cursor
  (:require [clojure.data.json :as json]
            [clojure.data.codec.base64 :as b64]))

(defn encode-cursor
  "Encode cursor data to Base64 string.
   
   Args:
     cursor-data - Map with :id and :sort-value
     
   Returns:
     Base64-encoded string
     
   Shell: Uses JSON encoding"
  [cursor-data]
  (-> cursor-data
      json/write-str
      .getBytes
      b64/encode
      (String.)))

(defn decode-cursor
  "Decode Base64 cursor to data map.
   
   Args:
     cursor-str - Base64-encoded string
     
   Returns:
     Map with :id and :sort-value
     
   Shell: Uses JSON decoding"
  [cursor-str]
  (try
    (-> cursor-str
        .getBytes
        b64/decode
        (String.)
        (json/read-str :key-fn keyword))
    (catch Exception _
      nil)))
```

#### `link_headers.clj` - RFC 5988 Link Header Generation
```clojure
(ns boundary.platform.shell.pagination.link-headers)

(defn build-link-header
  "Build RFC 5988 Link header.
   
   Args:
     base-url - Base URL (e.g., '/api/v1/users')
     pagination - Pagination metadata
     query-params - Additional query params
     
   Returns:
     Link header string
     
   Shell: Builds external URLs"
  [base-url pagination query-params]
  (let [links []]
    ;; Build next, prev, first, last links
    (str/join ", " links)))
```

### 3.3 Schema Layer

**Location**: `src/boundary/platform/schema.clj`

```clojure
(def PaginationParams
  "Schema for pagination query parameters."
  [:map
   [:limit {:optional true} [:int {:min 1 :max 100}]]
   [:offset {:optional true} [:int {:min 0}]]
   [:cursor {:optional true} :string]
   [:sort {:optional true} :string]])

(def OffsetPaginationMeta
  "Schema for offset pagination metadata."
  [:map
   [:type [:= "offset"]]
   [:total :int]
   [:offset :int]
   [:limit :int]
   [:has-next? :boolean]
   [:has-prev? :boolean]
   [:page :int]
   [:pages :int]])

(def CursorPaginationMeta
  "Schema for cursor pagination metadata."
  [:map
   [:type [:= "cursor"]]
   [:limit :int]
   [:next-cursor {:optional true} [:maybe :string]]
   [:prev-cursor {:optional true} [:maybe :string]]
   [:has-next? :boolean]
   [:has-prev? :boolean]])

(def PaginatedResponse
  "Schema for paginated response."
  [:map
   [:data :any]
   [:pagination [:or OffsetPaginationMeta CursorPaginationMeta]]
   [:meta {:optional true} :map]])
```

---

## 4. Implementation Plan

### Phase 1: Core Logic (Days 1-2)
1. ✅ Design document (this document)
2. Create pagination core functions (`pagination.clj`)
3. Create versioning core functions (`versioning.clj`)
4. Write unit tests for core logic

### Phase 2: Shell Layer (Days 2-3)
5. Implement cursor encoding/decoding
6. Implement Link header generation (RFC 5988)
7. Create pagination middleware/interceptor
8. Write shell layer tests

### Phase 3: Router Integration (Days 3-4)
9. Add versioning to router configuration
10. Mount v1 routes under `/api/v1`
11. Add backward compatibility for `/api` → `/api/v1`
12. Update HTTP handlers to support pagination params

### Phase 4: User Module Example (Days 4-5)
13. Add pagination to `list-users` endpoint
14. Support both offset and cursor pagination
15. Add Link headers to responses
16. Write integration tests

### Phase 5: Documentation (Days 5-6)
17. API versioning guide
18. Pagination guide with examples
19. Migration guide (v0 → v1)
20. Update AGENTS.md and README.md

### Phase 6: Testing & Polish (Day 6-7)
21. Comprehensive test suite (unit + integration)
22. Performance testing (large datasets)
23. Create phase completion report
24. Final review and documentation polish

---

## 5. Backward Compatibility

### 5.1 Existing Routes

**Current routes remain functional**:
```
GET /api/users → GET /api/v1/users (internal redirect)
```

**Both formats supported**:
```
GET /api/users           (legacy, redirects to v1)
GET /api/v1/users        (explicit version)
```

### 5.2 Response Format Changes

**Current** (no pagination):
```json
{
  "users": [...]
}
```

**New** (with pagination, backward compatible):
```json
{
  "users": [...],
  "pagination": {
    "type": "offset",
    "total": 1000,
    "offset": 0,
    "limit": 20
  }
}
```

**Legacy Support**: If no pagination params provided, return all results (with warning header)

---

## 6. Configuration

**Location**: `resources/conf/dev/config.edn`

```clojure
{:boundary/api-versioning
 {:default-version :v1
  :latest-stable   :v1
  :deprecated-versions #{:v0}
  :sunset-dates    {:v0 "2026-06-01"}
  :supported-versions #{:v0 :v1}}

 :boundary/pagination
 {:default-limit 20
  :max-limit     100
  :default-type  :offset  ; or :cursor
  :cursor-ttl    3600     ; seconds
  :enable-link-headers true}}
```

---

## 7. Testing Strategy

### Unit Tests (Core)
- `pagination_test.clj` - Pure pagination logic
- `versioning_test.clj` - Version parsing and comparison
- `cursor_test.clj` - Cursor encoding/decoding

### Integration Tests
- `versioning_integration_test.clj` - Full versioning flow
- `pagination_integration_test.clj` - Paginated endpoints
- `link_headers_test.clj` - RFC 5988 compliance

### Performance Tests
- Large dataset pagination (1M+ records)
- Cursor vs offset performance comparison
- Link header generation overhead

---

## 8. Security Considerations

### Cursor Security
- ✅ Base64 encoding (not encryption, cursors are opaque not secret)
- ✅ Validation on decode (reject malformed cursors)
- ✅ No sensitive data in cursors (only IDs and sort values)
- ⚠️ Consider HMAC signing for tamper detection (future)

### Rate Limiting
- Pagination requests count toward rate limits
- Large `limit` values may trigger lower rate limits
- Cursor pagination preferred for high-volume clients

---

## 9. Success Criteria

✅ **Feature Complete**:
- URL-based versioning working
- Offset pagination working
- Cursor pagination working
- RFC 5988 Link headers
- Backward compatibility maintained

✅ **Quality**:
- 100% test coverage for core logic
- Integration tests pass
- Performance benchmarks acceptable
- Documentation complete

✅ **Compliance**:
- RFC 5988 Link headers
- REST best practices
- FC/IS architecture maintained
- Zero breaking changes

---

## 10. Future Enhancements

**Phase 4.5+**:
1. GraphQL-style field selection
2. Batch operations
3. Webhook versioning
4. API analytics dashboard
5. Client SDK generation (OpenAPI)

---

## References

- **RFC 5988**: Web Linking (Link headers)
  https://datatracker.ietf.org/doc/html/rfc5988

- **REST API Best Practices**:
  https://restfulapi.net/versioning/

- **Cursor Pagination**:
  https://slack.engineering/evolving-api-pagination-at-slack/

---

**Status**: 🚧 In Design  
**Next Step**: Create pagination core logic  
**ETA**: 7 days from design approval  
**Date**: 2026-01-04
