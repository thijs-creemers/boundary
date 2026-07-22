# Storage Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

File storage abstraction with pluggable backends. Ships a local filesystem
adapter and an AWS S3 / S3-compatible adapter behind a single `IFileStorage`
port, plus pure file validation, a Java-AWT image processor (resize +
thumbnails, no native deps), signed-URL generation, and Ring upload/download
handlers.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.storage.ports` | Protocols: `IFileStorage`, `IImageProcessor` |
| `boundary.storage.core.validation` | Pure: size/type/extension validation, filename sanitization, MIME lookup |
| `boundary.storage.schema` | Malli schemas: `FileData`, `FileMetadata`, `StorageResult`, `ImageInfo`, `*StorageConfig` |
| `boundary.storage.shell.service` | `IStorageService` protocol + `StorageService` record — validation + storage + optional image processing |
| `boundary.storage.shell.adapters.local` | `LocalFileStorage` — filesystem, content-addressed keys with directory sharding |
| `boundary.storage.shell.adapters.s3` | `S3FileStorage` — AWS S3 / S3-compatible (MinIO, DO Spaces) via AWS SDK v2 |
| `boundary.storage.shell.adapters.image-processor` | `JavaImageProcessor` — Java AWT / `javax.imageio` |
| `boundary.storage.shell.http-handlers` | Ring handlers + `storage-routes` (Reitit) for upload/download/delete/url |

## Ports

### `IFileStorage` — the storage seam (both adapters implement it)

```clojure
(store-file          [this file-data metadata])       ; => {:key :url :size :content-type :stored-at}
(retrieve-file       [this file-key])                 ; => {:bytes :content-type :size} or nil
(delete-file         [this file-key])                 ; => boolean
(file-exists?        [this file-key])                 ; => boolean
(generate-signed-url [this file-key expiration-seconds]) ; => url string or nil
```

- `file-data` is `{:bytes <byte-array> :content-type <string>}`.
- `metadata` is `{:filename <string> :path <optional> :visibility :public|:private}`.
- Local `generate-signed-url` has no real signing — it returns the public URL
  (from `url-base`) if configured, else `nil`.

### `IImageProcessor` — optional image ops

```clojure
(resize-image     [this image-bytes dimensions])  ; dimensions {:width w :height h}, either nil => proportional
(create-thumbnail [this image-bytes size])        ; size is an INTEGER (max dimension), NOT a map
(get-image-info   [this image-bytes])             ; => {:width :height :format :size}
(is-image?        [this bytes content-type])      ; => boolean
```

## Adapters & config selection

### Local (`create-local-storage`)

```clojure
(require '[boundary.storage.shell.adapters.local :as local])

(def storage
  (local/create-local-storage
    {:base-path "uploads"          ; required — root dir
     :url-base  "https://cdn.example.com/files" ; optional — enables :url + signed-url fallback
     :create-directories? true     ; default true
     :logger    logger}))          ; optional
```

- Auto-generated key (no `:path`): `{shard}/{timestamp}-{uuid}-{hash16}.{ext}`
  where `shard` = first 2 hex chars of the content SHA-256 (directory sharding
  to avoid huge dirs). With an explicit `:path`, the key is
  `{sanitized-path}/{sanitized-filename}`.

### S3 (`create-s3-storage` / `close-s3-storage`)

```clojure
(require '[boundary.storage.shell.adapters.s3 :as s3])

(def storage
  (s3/create-s3-storage
    {:bucket      "my-bucket"      ; required
     :region      "us-east-1"      ; required
     :access-key  "..."            ; optional — falls back to DefaultCredentialsProvider
     :secret-key  "..."            ; optional
     :endpoint    "http://localhost:9000" ; optional — MinIO/Spaces; enables path-style access
     :prefix      "app/"           ; optional — key prefix
     :public-read? false           ; optional — PUBLIC_READ vs PRIVATE ACL
     :logger      logger}))

(s3/close-s3-storage storage)      ; releases S3Client + S3Presigner — call on shutdown
```

- S3 key: `{prefix}/{timestamp}-{uuid8}.{ext}`.
- Signed URLs use `S3Presigner` (`X-Amz-*` query params); public objects get a
  direct `https://{bucket}.s3.amazonaws.com/{key}` URL.

> Note: `schema.clj` also defines `GCSStorageConfig` and `:adapter :gcs` in the
> `StorageConfig` enum, but **there is no GCS adapter** — only `:local` and
> `:s3` are implemented.

## Wiring (`create-storage-service`)

The library ships **factory functions, not an Integrant `init-key`** — the host
app constructs and wires the service. `boundary-cli`'s module catalogue suggests
a `:boundary/storage` config key, but no `defmethod ig/init-key :boundary/storage`
exists in this lib; wire it yourself.

```clojure
(require '[boundary.storage.shell.service :as service])
(require '[boundary.storage.shell.adapters.image-processor :as img])

(def svc
  (service/create-storage-service
    {:storage         storage                 ; required — any IFileStorage
     :image-processor (img/create-image-processor {:logger logger}) ; optional
     :logger          logger}))               ; optional
```

`create-storage-service` throws if `:storage` is missing. The service is the
imperative shell: it sanitizes filenames, runs pure `validate-file`, then
delegates to the adapter, wrapping results as `{:success bool ...}`.

## Usage — service layer (`IStorageService`)

```clojure
(require '[boundary.storage.shell.service :as service])

;; Upload with validation options
(service/upload-file svc
  {:bytes (.getBytes "hi") :content-type "text/plain"}
  {:filename "note.txt"}
  {:max-size 5242880 :allowed-types ["text/plain"] :allowed-extensions ["txt"]})
;=> {:success true :data {:key "..." :url "..." :size 2 :content-type "text/plain" :stored-at #inst"..."}}
;=> {:success false :errors [{:code :file-too-large :message "..." :details {...}}]}

;; Upload an image, optionally producing a thumbnail
(service/upload-image svc image-bytes
  {:filename "pic.jpg"}
  {:create-thumbnail true :thumbnail-size 200})
;=> {:success true :original {...} :thumbnail {...}}

(service/download-file svc file-key)   ; => {:bytes :content-type :size} or nil
(service/remove-file   svc file-key)   ; => boolean
(service/get-file-url  svc file-key 3600) ; signed (S3 private) or public URL
```

To bypass the service, call the `boundary.storage.ports` fns directly on an
adapter (`store-file`, `retrieve-file`, `file-exists?`, `delete-file`,
`generate-signed-url`) — but you then lose validation and filename sanitization.

## Validation (pure — `boundary.storage.core.validation`)

```clojure
(require '[boundary.storage.core.validation :as v])

(v/validate-file file-data metadata {:max-size 5242880 :allowed-types ["image/jpeg"]})
;=> {:valid? true :data {...}} | {:valid? false :errors [{:code ... :message ...}]}

(v/sanitize-filename "../../etc/passwd")  ; => "etcpasswd" (strips .., separators, non-word chars)
```

- Never throws — returns result maps. Error codes: `:file-too-large`,
  `:invalid-content-type`, `:invalid-extension`, `:not-an-image`.
- `default-max-file-size` = 10 MB. `image-mime-types` / `common-mime-types` back
  the type checks and extension→MIME lookup.

## HTTP endpoints (`storage-routes`)

`(http-handlers/storage-routes svc {:base-path "/api/v1/storage"})` returns
**Reitit-style** route vectors (not the normalized module-`:api` map format —
mount them directly in a Reitit router, not via the module route mechanism):

| Method | Path | Handler |
|--------|------|---------|
| POST | `/upload` | multipart `file` + `path`/`visibility`; query `max-size`, `allowed-types`, `allowed-extensions` |
| POST | `/upload/image` | multipart `file` + `create-thumbnail`, `thumbnail-size` |
| GET | `/download/:file-key` | streams bytes with `Content-Disposition: attachment` |
| DELETE | `/delete/:file-key` | 204 on success, 404 otherwise |
| GET | `/url/:file-key` | query `expiration` (default 3600) → JSON `{:url :expiration-seconds}` |

Errors are emitted as RFC-7807 problem details via
`boundary.platform.core.http.problem-details`.

## Gotchas

1. **`create-thumbnail` takes an integer**, not a map — `size` is the max
   dimension (aspect ratio preserved). Passing a map will break.
2. **Validation never throws.** The service also catches adapter exceptions and
   returns `{:success false :errors [{:code :storage-error ...}]}`.
3. **Image processor is optional.** `upload-image` only builds a thumbnail
   `(when (and create-thumbnail image-processor) ...)`; a failed thumbnail does
   not fail the original upload.
4. **`upload-image` currently hard-codes `:content-type "image/jpeg"`** for the
   stored original (see the `; Will be detected properly` TODO) — content-type
   is not sniffed from the bytes there.
5. **S3 resources must be released** — call `close-s3-storage` on shutdown to
   close the `S3Client` and `S3Presigner`.
6. **Local signed URLs are not signed** — they return the public `url-base` URL,
   or `nil` if `:url-base` is unset. Use S3 for real time-limited access.
7. **No GCS adapter** despite `GCSStorageConfig` / `:gcs` existing in schema.
8. **No Integrant key** is shipped; wire the factories in the host system.

## Testing

```bash
clojure -M:test:db/h2 :storage
```

Adapter tests are tagged `^:integration`. The local-adapter suite writes to the
`target/test-storage` temp directory with cleanup fixtures. The image-processor
suite exercises real `javax.imageio` encode/decode.

## Links

- [Library README](README.md)
- [Root AGENTS Guide](../../AGENTS.md)
