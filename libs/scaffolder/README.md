# boundary/scaffolder

**Status:** In Development  
**Version:** 0.1.0-SNAPSHOT

Code generation tool for creating new Boundary modules.

## Installation

```clojure
;; As dev dependency
{:aliases {:dev {:extra-deps {boundary/scaffolder {:mvn/version "0.1.0"}}}}}
```

## Features

- **Module Generation**: Scaffold complete modules with FC/IS architecture
- **Entity Templates**: Generate entity CRUD code
- **Migration Generation**: Create database migrations
- **Test Scaffolding**: Generate unit and integration tests
- **CLI Interface**: Easy-to-use command-line tool

## Quick Start

```bash
clojure -M:dev -m boundary.scaffolder.shell.cli-entry generate \
  --module-name product \
  --entity Product \
  --field name:string:required \
  --field price:decimal:required
```

## License

See root [LICENSE](../../LICENSE)
