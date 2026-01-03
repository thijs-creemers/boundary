# Documentation Repository Split Migration Guide

## Overview

This guide documents the process of splitting the Hugo documentation site from the Boundary framework repository into a separate dedicated repository.

## Current Structure

**In boundary repo:**
- `docs/` - All documentation content (AsciiDoc)
- `docs/config.toml` - Hugo configuration
- `docs/.hugo_build.lock` - Hugo build lock
- `docs/resources/public/docs/` - Generated Hugo output
- `scripts/serve-docs.js` - Documentation server script
- `package.json` - Contains `serve-docs` script
- `.gitignore` - Ignores `/docs/**/*.html`

## New Structure

**boundary repo (code only):**
```
boundary/
├── src/
├── test/
├── resources/
├── README.md (updated with link to docs)
├── AGENTS.md (developer guide - kept)
├── BUILD.md (build instructions - kept)
├── CONTRIBUTING.md (contribution guide - kept)
└── ... (all Clojure code)
```

**boundary-docs repo (new):**
```
boundary-docs/
├── config.toml (Hugo config)
├── content/ (renamed from docs/)
│   ├── adr/
│   ├── architecture/
│   ├── guides/
│   ├── reference/
│   ├── diagrams/
│   └── ...
├── static/ (for images, if needed)
├── layouts/ (Hugo templates, if added later)
├── scripts/
│   └── serve-docs.js
├── public/ (Hugo output - gitignored)
├── .gitignore
├── README.md (docs-specific)
├── package.json (for serve-docs script)
└── .github/
    └── workflows/
        └── deploy-docs.yml (CI/CD)
```

## Migration Steps

### Prerequisites

1. Install `git-filter-repo`:
   ```bash
   # macOS
   brew install git-filter-repo
   
   # Or via pip
   pip install git-filter-repo
   ```

2. Ensure you have a clean working directory:
   ```bash
   cd /Users/thijscreemers/Work/tcbv/boundary
   git status
   ```

### Step 1: Create Docs Repository with History

Execute the migration script:

```bash
./scripts/split-docs-repo.sh
```

This script will:
1. Create a fresh clone of the boundary repo
2. Use `git-filter-repo` to extract only docs-related files with full history
3. Restructure the repository for Hugo conventions
4. Create a new remote repository (you'll need to create it on GitHub first)

### Step 2: Verify Docs Repository

```bash
cd ../boundary-docs
git log --oneline  # Verify history preserved
hugo server -D     # Test Hugo builds correctly
```

### Step 3: Create GitHub Repository

1. Go to https://github.com/thijs-creemers
2. Create new repository: `boundary-docs`
3. Do NOT initialize with README, .gitignore, or license
4. Copy the remote URL

### Step 4: Push Docs Repository

```bash
cd ../boundary-docs
git remote add origin git@github.com:thijs-creemers/boundary-docs.git
git push -u origin main
```

### Step 5: Update Code Repository

Back in the boundary repo:

```bash
cd ../boundary
git checkout -b split-docs-to-separate-repo
```

Remove docs files:
```bash
git rm -r docs/
git rm scripts/serve-docs.js
```

Update package.json (remove serve-docs script):
```bash
# Edit package.json to remove:
# "serve-docs": "node scripts/serve-docs.js"
```

Update .gitignore (remove docs entries):
```bash
# Edit .gitignore to remove:
# /docs/**/*.html
```

Update README.md:
```bash
# Add section pointing to docs repository
```

Commit changes:
```bash
git add -A
git commit -m "Split documentation to separate repository

Documentation has been moved to: https://github.com/thijs-creemers/boundary-docs

This keeps the code repository focused on framework implementation
while allowing documentation to evolve independently with its own
CI/CD pipeline and deployment strategy.

Changes:
- Removed docs/ directory
- Removed scripts/serve-docs.js
- Updated README.md with link to docs repo
- Cleaned up package.json and .gitignore"
```

### Step 6: Set Up Docs CI/CD

In the boundary-docs repository, create `.github/workflows/deploy-docs.yml`:

```yaml
name: Deploy Hugo Docs

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

permissions:
  contents: read
  pages: write
  id-token: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup Hugo
        uses: peaceiris/actions-hugo@v2
        with:
          hugo-version: 'latest'
          extended: true
      
      - name: Build
        run: hugo --minify
      
      - name: Upload artifact
        uses: actions/upload-pages-artifact@v2
        with:
          path: ./public

  deploy:
    if: github.ref == 'refs/heads/main'
    needs: build
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - name: Deploy to GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v3
```

### Step 7: Enable GitHub Pages

1. Go to repository Settings → Pages
2. Set Source to "GitHub Actions"
3. Save

### Step 8: Verify Everything Works

**Docs repository:**
```bash
cd ../boundary-docs
hugo server -D
# Visit http://localhost:1313/docs/
```

**Code repository:**
```bash
cd ../boundary
clojure -M:test:db/h2
# Ensure tests still pass
```

## Rollback Plan

If issues arise, you can rollback:

```bash
cd /Users/thijscreemers/Work/tcbv/boundary
git checkout main
git branch -D split-docs-to-separate-repo
```

The docs repository can be deleted and recreated if needed since it's a fresh split.

## Post-Migration Checklist

- [ ] Docs build successfully with Hugo in new repo
- [ ] GitHub Pages deployment works
- [ ] Links in code README point to published docs
- [ ] Code repository tests pass without docs/
- [ ] CI/CD for docs repository is configured
- [ ] Team has access to boundary-docs repository
- [ ] Documentation on how to contribute to docs is updated

## Benefits of Split

1. **Clean separation**: Code and docs evolve independently
2. **Focused repos**: Each repository has single responsibility
3. **Independent CI/CD**: Docs can be deployed without code changes
4. **Better access control**: Different permissions for code vs docs
5. **Smaller code repo**: Faster clones and cleaner history
6. **Flexible hosting**: Docs can be hosted on GitHub Pages, Netlify, etc.

## Future Improvements

- Add link checking in docs CI
- Add preview deployments for docs PRs
- Consider adding docs versioning (per release)
- Add automated changelog generation
