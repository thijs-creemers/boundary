# Boundary Framework Documentation Site

This directory contains the Hugo-based documentation site for the Boundary Framework.

## Quick Start

### Prerequisites

- Hugo Extended v0.155.3 or later
- AsciiDoctor

On macOS:
```bash
brew install hugo asciidoctor
```

### Initial Setup

If you've just cloned the repository, initialize the Hugo Book theme submodule:

```bash
# From repository root
git submodule update --init --recursive
```

Or clone with submodules in one step:
```bash
git clone --recurse-submodules <repo-url>
```

### Development

```bash
cd docs-site
hugo server
```

Visit http://localhost:1313/boundary/

### Build

```bash
cd docs-site
hugo --gc --minify
```

Output will be in `public/` directory.

## Structure

```
docs-site/
├── content/              # All documentation content
│   ├── adr/             # Architecture Decision Records
│   ├── architecture/    # Architecture guides
│   ├── guides/          # How-to guides
│   ├── api/             # API reference
│   ├── reference/       # Reference documentation
│   ├── examples/        # Example applications
│   └── changelog/       # Version history
├── themes/
│   └── hugo-book/       # Hugo Book theme (submodule)
├── static/              # Static assets
├── layouts/             # Custom layouts (if needed)
├── hugo.toml            # Hugo configuration
└── README.md            # This file
```

## Theme

This site uses the [Hugo Book](https://github.com/alex-shpak/hugo-book) theme, which provides:

- Clean, minimal design for technical documentation
- Built-in search functionality
- Mobile responsive layout
- Native AsciiDoc and Markdown support
- Fast build times

## AsciiDoc Support

Hugo supports AsciiDoc files (`.adoc`) via the `asciidoctor` external processor. 

Key configuration in `hugo.toml`:
```toml
[security.exec]
  allow = ['^asciidoctor$', ...]

[markup.asciidocExt]
  backend = 'html5'
  workingFolderCurrent = true
```

## GitHub Pages Deployment

The site is automatically deployed to GitHub Pages via GitHub Actions when changes are pushed to the main branch.

See `.github/workflows/deploy.yml` for the deployment workflow.

## Content Guidelines

- Use AsciiDoc (`.adoc`) for technical documentation (architecture, ADRs, guides)
- Use Markdown (`.md`) for simple pages (index pages, changelogs)
- Each major section should have an `_index.md` file
- Follow the existing navigation structure

## Local Testing

Test the production build locally:

```bash
hugo --gc --minify
cd public
python3 -m http.server 8000
```

Visit http://localhost:8000/boundary/

## Maintenance

### Updating the Theme

The theme is included as a git submodule. To update to the latest version:

```bash
# From repository root
git submodule update --remote docs-site/themes/hugo-book
git add docs-site/themes/hugo-book
git commit -m "Update hugo-book theme"
```

Or manually:
```bash
# From repository root
cd docs-site/themes/hugo-book
git fetch origin
git checkout main
git pull
cd ../../..
git add docs-site/themes/hugo-book
git commit -m "Update hugo-book theme"
```

### Adding New Sections

1. Create a new directory under `content/`
2. Add an `_index.md` file with frontmatter:
   ```markdown
   ---
   title: "Section Title"
   weight: 50
   ---
   ```
3. Add content files to the directory

## Troubleshooting

### Build fails with "asciidoctor not found"

Install asciidoctor:
```bash
brew install asciidoctor  # macOS
```

### Build fails with "access denied: asciidoctor is not whitelisted"

Ensure `hugo.toml` includes asciidoctor in the security allowlist:
```toml
[security.exec]
  allow = ['^asciidoctor$', ...]
```

### Navigation not showing

Ensure each section has an `_index.md` file with proper frontmatter including `weight`.

## Links

- [Hugo Documentation](https://gohugo.io/documentation/)
- [Hugo Book Theme](https://github.com/alex-shpak/hugo-book)
- [AsciiDoc in Hugo](https://gohugo.io/content-management/formats/#asciidoc)
