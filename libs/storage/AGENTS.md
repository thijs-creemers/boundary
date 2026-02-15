# Storage Library - Development Guide

> For general conventions, testing commands, and architecture patterns, see the [root AGENTS.md](../../AGENTS.md).

## Purpose

File storage abstraction with local filesystem and AWS S3 backends. Includes file validation, image processing (resize, thumbnails), and signed URL generation.

## Key Namespaces

| Namespace | Purpose |
|-----------|---------|
| `boundary.storage.core.validation` | Pure: file validation (size, type, extension), filename sanitization |
| `boundary.storage.ports` | Protocols: IFileStorage, IImageProcessor |
| `boundary.storage.schema` | Malli schemas: FileData, FileMetadata, StorageResult, ImageInfo |
| `boundary.storage.shell.service` | Service layer orchestrating validation + storage |
| `boundary.storage.shell.adapters.local` | Local filesystem adapter (content-addressed with SHA-256) |
| `boundary.storage.shell.adapters.s3` | AWS S3 / S3-compatible adapter (MinIO, DigitalOcean Spaces) |
| `boundary.storage.shell.adapters.image-processor` | Java AWT image processing (no external deps) |
| `boundary.storage.shell.http-handlers` | Ring handlers for upload/download/delete endpoints |

## Storage Operations

```clojure
(require '[boundary.storage.ports :as ports])

;; Store file
(ports/store-file storage
  {:bytes (.getBytes "content") :content-type "text/plain"}
  {:filename "test.txt"})
;=> {:key "ab/1234-uuid-hash.txt" :url "..." :size 7 :content-type "text/plain" :stored-at #inst"..."}

;; Retrieve, check existence, delete
(ports/retrieve-file storage "ab/1234-uuid-hash.txt")
(ports/file-exists? storage "ab/1234-uuid-hash.txt")
(ports/delete-file storage "ab/1234-uuid-hash.txt")

;; Signed URLs (S3 only, local returns public URL)
(ports/generate-signed-url storage key 3600)  ; expires in 1 hour
```

## Storage Key Format

**Local**: `{shard}/{timestamp}-{uuid}-{hash16}.{ext}` (shard = first 2 chars of SHA-256)

**S3**: `{prefix}/{timestamp}-{uuid}-{hash16}.{ext}`

## Validation

```clojure
(require '[boundary.storage.core.validation :as v])

;; Validate file (pure - returns result map, never throws)
(v/validate-file file-data metadata {:max-size 5242880
                                      :allowed-types ["image/jpeg" "image/png"]})
;=> {:valid? true :data {...}}
;=> {:valid? false :errors [{:code :file-too-large ...}]}

;; Sanitize filename (prevents path traversal)
(v/sanitize-filename "../../../etc/passwd")
;=> "etcpasswd"
```

## Image Processing

```clojure
(require '[boundary.storage.shell.adapters.image-processor :as img])

;; Built on Java AWT - no external dependencies
(def processor (img/create-image-processor))
(ports/resize-image processor image-bytes {:width 800})  ; maintains aspect ratio
(ports/create-thumbnail processor image-bytes {:width 150 :height 150})
(ports/get-image-info processor image-bytes)
;=> {:width 1920 :height 1080 :format "jpeg" :size 245760}
```

## Gotchas

1. **Validation never throws** - returns `{:valid? false :errors [...]}`. Service layer catches adapter exceptions
2. **Image processor is optional** - service checks `(when image-processor ...)` before using
3. **S3 resources need cleanup** - call `close-s3-storage` to close S3Client/S3Presigner
4. **Local adapter** uses directory sharding to avoid too many files in one directory
5. **S3 visibility**: `PUBLIC_READ` vs `PRIVATE` ACL based on config. Private files need signed URLs

## Testing

```bash
clojure -M:test:db/h2 :storage
```

Tests use `target/test-storage` temp directory with cleanup fixtures.
