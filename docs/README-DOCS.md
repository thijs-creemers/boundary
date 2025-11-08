# Boundary Framework Documentation

This directory contains the source files for the Boundary Framework documentation, built using [Antora](https://antora.org/).

## Quick Start

1. **Install dependencies:**
   ```bash
   npm install
   ```

2. **Build the documentation:**
   ```bash
   npm run build-docs
   # or
   ./scripts/build-docs.sh
   ```

3. **Serve locally:**
   ```bash
   npm run serve-docs
   # or
   cd resources/public/docs && python3 -m http.server 8080
   ```

## Structure

The documentation is organized into several Antora components:

- **user-guide** (`docs/user-guide/`) - User-focused documentation with tutorials, how-to guides, and reference
- **architecture** (`docs/architecture/`) - Technical architecture documentation  
- **api** (`docs/api/`) - API reference documentation
- **reference** (`docs/`) - Additional reference materials, ADRs, and templates

## Building Documentation

### Using npm scripts:
```bash
npm run build-docs    # Build the site
npm run serve-docs    # Serve locally on port 8080
npm run dev-docs      # Build and serve
```

### Using shell script:
```bash
./scripts/build-docs.sh
```

### Using Clojure tools (if integrated):
```bash
clojure -X:docs
```

## Output

The generated HTML site is placed in `resources/public/docs/` and can be:
- Served by your application's web server
- Deployed to static hosting (GitHub Pages, Netlify, etc.)
- Served locally for development

## Configuration

The main configuration is in `antora-playbook.yml` which defines:
- Site metadata (title, URL)
- Content sources (documentation components)
- UI bundle (theme)
- Output directory (`resources/public/docs`)

Each component has its own `antora.yml` file defining:
- Component name and version
- Navigation structure
- Start page

## Adding Content

1. **Add pages** to the appropriate `modules/ROOT/pages/` directory
2. **Update navigation** in the corresponding `modules/ROOT/nav.adoc` file
3. **Include images** in `modules/ROOT/images/`
4. **Add examples** in `modules/ROOT/examples/`
5. **Use partials** for reusable content in `modules/ROOT/partials/`

## Cross-References

Use Antora's cross-reference syntax to link between components:
```asciidoc
xref:user-guide::quickstart.adoc[Quickstart Guide]
xref:architecture::overview.adoc[Architecture Overview]
```

## Prerequisites

- Node.js 16+ and npm
- For local development: Python 3 (for simple HTTP server) or any static file server