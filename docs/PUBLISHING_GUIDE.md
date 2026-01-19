# Boundary Libraries Publishing Guide

This guide documents how to build, test, and publish the Boundary framework libraries to Clojars.

## Library Overview

| Library | Artifact ID | Dependencies |
|---------|-------------|--------------|
| **core** | `io.github.thijs-creemers/boundary-core` | None |
| **observability** | `io.github.thijs-creemers/boundary-observability` | core |
| **platform** | `io.github.thijs-creemers/boundary-platform` | core, observability |
| **user** | `io.github.thijs-creemers/boundary-user` | platform |
| **admin** | `io.github.thijs-creemers/boundary-admin` | platform, user |
| **storage** | `io.github.thijs-creemers/boundary-storage` | platform |
| **scaffolder** | `io.github.thijs-creemers/boundary-scaffolder` | core |

## Prerequisites

### 1. Clojars Account

Create an account at [clojars.org](https://clojars.org) if you don't have one.

### 2. Deploy Token

Generate a deploy token:
1. Go to [Clojars Dashboard](https://clojars.org/tokens)
2. Click "Generate Token"
3. Name it (e.g., "boundary-deploy")
4. Copy the token (shown only once)

### 3. Environment Variables

Set your Clojars credentials:

```bash
export CLOJARS_USERNAME=your-username
export CLOJARS_PASSWORD=your-deploy-token
```

Or add to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>clojars</id>
      <username>your-username</username>
      <password>your-deploy-token</password>
    </server>
  </servers>
</settings>
```

## Version Management

### Version Format

Libraries use semantic versioning: `MAJOR.MINOR.PATCH`

- **MAJOR**: Breaking changes
- **MINOR**: New features (backward compatible)
- **PATCH**: Bug fixes (backward compatible)

### Current Versioning Strategy

The `build.clj` files use git-based versioning:

```clojure
(def version (format "0.1.%s" (b/git-count-revs nil)))
```

This creates versions like `0.1.42` based on git commit count.

### Manual Version Override

For release versions, update `build.clj`:

```clojure
;; Replace dynamic version with fixed version
(def version "1.0.0")
```

## Building Libraries

### Build a Single Library

```bash
cd libs/core
clojure -T:build clean
clojure -T:build jar
```

Output: `libs/core/target/boundary-core-0.1.X.jar`

### Build All Libraries

```bash
for lib in core observability platform user admin storage scaffolder; do
  echo "Building $lib..."
  (cd libs/$lib && clojure -T:build clean && clojure -T:build jar)
done
```

## Testing Before Publishing

### Run All Tests

```bash
# From repository root
clojure -M:test:db/h2
```

### Lint All Libraries

```bash
clojure -M:clj-kondo --lint libs/*/src libs/*/test
```

### Test Local Installation

Install to local Maven repository:

```bash
cd libs/core
clojure -T:build install
```

Verify installation:

```bash
ls ~/.m2/repository/io/github/thijs-creemers/boundary-core/
```

## Publishing to Clojars

### Publish Order (IMPORTANT!)

Libraries must be published in dependency order:

1. **core** (no dependencies)
2. **observability** (depends on core)
3. **platform** (depends on core, observability)
4. **scaffolder** (depends on core)
5. **user** (depends on platform)
6. **storage** (depends on platform)
7. **admin** (depends on platform, user)

### Publish a Single Library

```bash
cd libs/core
clojure -T:build deploy
```

### Publish All Libraries (In Order)

```bash
#!/bin/bash
set -e  # Exit on error

LIBS_ORDER="core observability platform scaffolder user storage admin"

for lib in $LIBS_ORDER; do
  echo "========================================="
  echo "Publishing boundary-$lib..."
  echo "========================================="
  (cd libs/$lib && clojure -T:build deploy)
  echo "✅ boundary-$lib published successfully"
  echo ""
  # Wait for Clojars to index the library
  sleep 30
done

echo "🎉 All libraries published!"
```

### Verify Publication

Check Clojars:
- https://clojars.org/io.github.thijs-creemers/boundary-core
- https://clojars.org/io.github.thijs-creemers/boundary-observability
- etc.

## Updating Dependencies Between Libraries

When publishing new versions, update inter-library dependencies:

### Current: Local Development

```clojure
;; libs/observability/deps.edn
{:deps {boundary/core {:local/root "../core"}}}
```

### For Publishing: Maven Coordinates

```clojure
;; libs/observability/deps.edn (for release)
{:deps {io.github.thijs-creemers/boundary-core {:mvn/version "0.1.42"}}}
```

### Switching Between Local and Published

Use Clojure CLI aliases:

```clojure
;; deps.edn
{:deps {io.github.thijs-creemers/boundary-core {:mvn/version "0.1.42"}}
 
 :aliases
 {:local {:override-deps {io.github.thijs-creemers/boundary-core {:local/root "../core"}}}}}
```

Development: `clojure -M:local:test`  
Production: `clojure -M:test`

## Release Checklist

Before releasing a new version:

- [ ] All tests pass: `clojure -M:test:db/h2`
- [ ] No lint errors: `clojure -M:clj-kondo --lint libs/*/src`
- [ ] Update CHANGELOG.md
- [ ] Update version in build.clj (if not using git-based)
- [ ] Commit all changes
- [ ] Create git tag: `git tag v0.1.0`
- [ ] Push tag: `git push origin v0.1.0`
- [ ] Publish in dependency order
- [ ] Verify on Clojars
- [ ] Update README installation instructions

## Troubleshooting

### "Unauthorized" Error

```
Error deploying artifact: Unauthorized
```

**Solution**: Check CLOJARS_USERNAME and CLOJARS_PASSWORD environment variables.

### "Version Already Exists" Error

```
Error: version 0.1.42 already exists
```

**Solution**: Bump version number or delete the existing version on Clojars (only possible within 24 hours of upload).

### Dependency Resolution Errors

```
Could not find artifact io.github.thijs-creemers/boundary-core
```

**Solution**: 
1. Publish core library first
2. Wait for Clojars indexing (~1 minute)
3. Clear local cache: `rm -rf ~/.m2/repository/io/github/thijs-creemers/`

### POM Generation Issues

If POM is malformed:

```bash
cd libs/core
clojure -T:build clean
clojure -T:build jar
# Check generated POM
cat target/classes/META-INF/maven/io.github.thijs-creemers/boundary-core/pom.xml
```

## GitHub Actions (Future)

For automated publishing, create `.github/workflows/publish.yml`:

```yaml
name: Publish to Clojars

on:
  push:
    tags:
      - 'v*'

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0  # For git-count-revs
      
      - uses: DeLaGuardo/setup-clojure@master
        with:
          cli: 'latest'
      
      - name: Publish libraries
        env:
          CLOJARS_USERNAME: ${{ secrets.CLOJARS_USERNAME }}
          CLOJARS_PASSWORD: ${{ secrets.CLOJARS_PASSWORD }}
        run: |
          for lib in core observability platform scaffolder user storage admin; do
            (cd libs/$lib && clojure -T:build deploy)
            sleep 30
          done
```

## Support

For issues with publishing:
- Check [Clojars documentation](https://github.com/clojars/clojars-web/wiki)
- Open an issue on the Boundary repository
