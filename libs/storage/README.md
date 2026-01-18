# boundary/storage

**Status:** In Development  
**Version:** 0.1.0-SNAPSHOT

File storage abstraction with local filesystem and S3 backends.

## Installation

```clojure
{:deps {boundary/storage {:mvn/version "0.1.0"}}}
```

## Features

- **Storage Protocol**: Abstract storage interface
- **Local Adapter**: Filesystem-based storage
- **S3 Adapter**: AWS S3 integration
- **Image Processing**: Basic image utilities
- **Upload Validation**: File type and size validation

## Quick Start

```clojure
(ns myapp.storage
  (:require [boundary.storage.core :as storage]))

;; Local storage
(def store (storage/local-storage {:base-path "/uploads"}))

;; S3 storage
(def store (storage/s3-storage {:bucket "my-bucket" :region "us-east-1"}))

(storage/put store "avatar.jpg" file-data)
```

## License

See root [LICENSE](../../LICENSE)
