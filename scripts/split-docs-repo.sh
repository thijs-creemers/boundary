#!/bin/bash
set -e

# Documentation Repository Split Script
# This script extracts the documentation into a separate repository
# while preserving git history for all docs-related files.

echo "======================================"
echo "Boundary Docs Repository Split Script"
echo "======================================"
echo ""

# Configuration
ORIGINAL_REPO="/Users/thijscreemers/Work/tcbv/boundary"
TEMP_CLONE="/tmp/boundary-docs-split-$(date +%s)"
DOCS_REPO="/Users/thijscreemers/Work/tcbv/boundary-docs"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Helper functions
error() {
    echo -e "${RED}ERROR: $1${NC}" >&2
    exit 1
}

success() {
    echo -e "${GREEN}✓ $1${NC}"
}

info() {
    echo -e "${YELLOW}→ $1${NC}"
}

# Check prerequisites
info "Checking prerequisites..."

if ! command -v git-filter-repo &> /dev/null; then
    error "git-filter-repo is not installed. Install with: brew install git-filter-repo"
fi

if ! command -v hugo &> /dev/null; then
    info "Hugo not found - will skip build test (optional)"
fi

# Verify we're in the right directory
if [ ! -d "$ORIGINAL_REPO/docs" ]; then
    error "docs/ directory not found in $ORIGINAL_REPO"
fi

success "Prerequisites checked"

# Step 1: Create temporary clone
info "Creating temporary clone at $TEMP_CLONE..."
git clone "$ORIGINAL_REPO" "$TEMP_CLONE"
success "Clone created"

# Step 2: Enter temporary clone and filter
cd "$TEMP_CLONE"
info "Filtering repository to extract docs-related files..."

# Use git-filter-repo to keep only docs-related paths
git-filter-repo --path docs/ \
                --path scripts/serve-docs.js \
                --path package.json \
                --path package-lock.json \
                --path .gitignore \
                --force

success "Repository filtered"

# Step 3: Restructure for Hugo conventions
info "Restructuring repository for Hugo..."

# Rename docs/ to content/
git mv docs content

# Create proper Hugo config at root
cat > config.toml << 'EOF'
baseURL = "/"
title = "Boundary Framework Documentation"
languageCode = "en-us"

# Content directory
contentDir = "content"

# Build output
publishDir = "public"

# Enable AsciiDoc
[markup.asciidocExt]
  workingFolderCurrent = true

# Permalinks
[permalinks]
  posts = "/:year/:month/:title/"

# Menu configuration
[menu]
  [[menu.main]]
    name = "Architecture"
    url = "/architecture/"
    weight = 10
  [[menu.main]]
    name = "Guides"
    url = "/guides/"
    weight = 20
  [[menu.main]]
    name = "Reference"
    url = "/reference/"
    weight = 30
  [[menu.main]]
    name = "ADR"
    url = "/adr/"
    weight = 40

# Taxonomies
[taxonomies]
  tag = "tags"
  category = "categories"
EOF

# Move Hugo config from content/ to root (if it exists)
if [ -f "content/config.toml" ]; then
    rm content/config.toml
fi

# Update .gitignore for docs repo
cat > .gitignore << 'EOF'
# Hugo
/public/
/resources/_gen/
/.hugo_build.lock

# Node
node_modules/
npm-debug.log*

# OS
.DS_Store
Thumbs.db

# IDE
.idea/
.vscode/
*.swp
*.swo
*~

# Temp
*.tmp
.tmp/
EOF

# Update package.json
cat > package.json << 'EOF'
{
  "name": "boundary-docs",
  "version": "1.0.0",
  "description": "Documentation for Boundary Framework",
  "scripts": {
    "serve": "node scripts/serve-docs.js",
    "build": "hugo --minify",
    "dev": "hugo server -D",
    "clean": "rm -rf public resources"
  },
  "keywords": [
    "boundary",
    "documentation",
    "hugo"
  ],
  "author": "Boundary Framework Team",
  "license": "MIT"
}
EOF

# Create README for docs repo
cat > README.md << 'EOF'
# Boundary Framework Documentation

This repository contains the documentation for the [Boundary Framework](https://github.com/thijs-creemers/boundary).

## Quick Start

### Prerequisites

- [Hugo](https://gohugo.io/installation/) (extended version recommended)
- Node.js (for local dev server)

### Local Development

```bash
# Serve with Hugo (rebuilds on changes)
npm run dev

# Or use custom dev server with port management
npm run serve

# Build static site
npm run build
```

The documentation will be available at http://localhost:1313/

## Structure

```
boundary-docs/
├── content/           # Documentation content (AsciiDoc/Markdown)
│   ├── adr/          # Architecture Decision Records
│   ├── architecture/ # Architecture documentation
│   ├── guides/       # How-to guides
│   ├── reference/    # Reference documentation
│   └── diagrams/     # PlantUML and image diagrams
├── static/           # Static assets (images, etc.)
├── layouts/          # Hugo templates
├── config.toml       # Hugo configuration
└── scripts/          # Utility scripts
```

## Contributing

### Writing Documentation

1. Create or edit files in `content/`
2. Use AsciiDoc (`.adoc`) or Markdown (`.md`)
3. Preview locally with `npm run dev`
4. Submit a pull request

### Adding Diagrams

- Source diagrams (PlantUML `.puml`) go in `content/diagrams/`
- Rendered images (PNG) also in `content/diagrams/`
- Reference with relative paths: `![Diagram](../diagrams/example.png)`

### Content Organization

- **Architecture**: System design, patterns, and high-level concepts
- **Guides**: Step-by-step tutorials and how-to articles
- **Reference**: API documentation, configuration, commands
- **ADR**: Architecture Decision Records (immutable history)

## Deployment

Documentation is automatically deployed to GitHub Pages on push to `main`.

### Manual Deployment

```bash
npm run build
# Output in public/ directory
```

## Links

- **Code Repository**: https://github.com/thijs-creemers/boundary
- **Published Docs**: https://thijs-creemers.github.io/boundary-docs/
- **Report Issues**: https://github.com/thijs-creemers/boundary-docs/issues

## License

Documentation licensed under [MIT License](LICENSE).
Code examples within documentation inherit the framework's license.
EOF

# Commit restructuring
git add -A
git commit -m "Restructure repository for Hugo documentation

- Moved docs/ to content/ (Hugo convention)
- Created root-level config.toml with proper Hugo settings
- Updated .gitignore for Hugo build artifacts
- Created README.md for documentation repository
- Updated package.json with docs-specific scripts

This repository was split from the main Boundary framework
repository to keep documentation and code separate."

success "Repository restructured"

# Step 4: Create final docs repo
info "Creating final docs repository at $DOCS_REPO..."

if [ -d "$DOCS_REPO" ]; then
    error "Directory $DOCS_REPO already exists. Please remove it first or choose a different location."
fi

# Move temp clone to final location
mv "$TEMP_CLONE" "$DOCS_REPO"

success "Docs repository created at $DOCS_REPO"

# Step 5: Test Hugo build (if available)
if command -v hugo &> /dev/null; then
    cd "$DOCS_REPO"
    info "Testing Hugo build..."
    if hugo --minify > /dev/null 2>&1; then
        success "Hugo build successful"
    else
        echo -e "${YELLOW}⚠ Hugo build failed - may need manual fixes${NC}"
    fi
fi

# Final instructions
echo ""
echo "======================================"
echo "Migration Complete!"
echo "======================================"
echo ""
echo "Next steps:"
echo ""
echo "1. Create GitHub repository:"
echo "   - Go to: https://github.com/thijs-creemers"
echo "   - Create repository: boundary-docs"
echo "   - DO NOT initialize with README"
echo ""
echo "2. Push docs repository:"
echo "   cd $DOCS_REPO"
echo "   git remote add origin git@github.com:thijs-creemers/boundary-docs.git"
echo "   git push -u origin main"
echo ""
echo "3. Update code repository:"
echo "   cd $ORIGINAL_REPO"
echo "   git checkout -b split-docs-to-separate-repo"
echo "   git rm -r docs/"
echo "   git rm scripts/serve-docs.js"
echo "   # Edit package.json to remove serve-docs script"
echo "   # Edit .gitignore to remove /docs/**/*.html"
echo "   # Update README.md with link to docs repo"
echo "   git add -A"
echo "   git commit -m 'Split documentation to separate repository'"
echo ""
echo "4. Set up GitHub Pages in boundary-docs repo settings"
echo ""
echo "See DOCS_SPLIT_MIGRATION.md for detailed instructions."
echo ""
