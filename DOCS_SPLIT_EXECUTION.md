# Documentation Split - Execution Summary

## What We're Doing

Splitting the Hugo documentation site from the main Boundary repository into a separate `boundary-docs` repository.

## Files Affected

### Files to Move to `boundary-docs`:
- `docs/` (entire directory - 200+ files)
- `scripts/serve-docs.js`
- `.hugo_build.lock` (from docs/)
- `config.toml` (from docs/)
- Relevant parts of `package.json` and `package-lock.json`

### Files to Update in `boundary`:
- `README.md` - Update documentation links
- `package.json` - Remove `serve-docs` script
- `.gitignore` - Remove `/docs/**/*.html` entry
- `AGENTS.md` - Update documentation references

## Migration Process

### Step 1: Run Split Script
```bash
./scripts/split-docs-repo.sh
```

This will:
1. Clone the repo to a temp location
2. Use `git-filter-repo` to extract only docs files with full history
3. Restructure for Hugo (docs/ → content/)
4. Create proper Hugo config at root
5. Create new README for docs repo
6. Move to `../boundary-docs/`

### Step 2: Create GitHub Repo
1. Go to https://github.com/thijs-creemers
2. Click "New repository"
3. Name: `boundary-docs`
4. **DO NOT** initialize with README, .gitignore, or license
5. Click "Create repository"

### Step 3: Push Docs Repo
```bash
cd ../boundary-docs
git remote add origin git@github.com:thijs-creemers/boundary-docs.git
git branch -M main
git push -u origin main
```

### Step 4: Clean Up Code Repo
```bash
cd ../boundary
git checkout -b split-docs-to-separate-repo
git rm -r docs/
git rm scripts/serve-docs.js
```

Then update these files (see below for specifics).

### Step 5: Commit and Push
```bash
git add -A
git commit -m "Split documentation to separate repository"
git push -u origin split-docs-to-separate-repo
```

Then create a PR to merge into main.

## File Updates Required

### README.md Changes

Replace this section:
```markdown
**→ [Documentation](docs/README.md) ←** - Complete documentation index
```

With:
```markdown
**→ [Documentation](https://github.com/thijs-creemers/boundary-docs) ←** - Complete documentation (separate repository)
```

Replace documentation links throughout:
- `docs/README.md` → `https://github.com/thijs-creemers/boundary-docs`
- `docs/architecture/` → `https://github.com/thijs-creemers/boundary-docs/tree/main/content/architecture`
- `docs/guides/` → `https://github.com/thijs-creemers/boundary-docs/tree/main/content/guides`
- etc.

### package.json Changes

Remove this line:
```json
"serve-docs": "node scripts/serve-docs.js"
```

Update name to focus on code:
```json
{
  "name": "boundary-framework",
  ...
}
```

### .gitignore Changes

Remove this line:
```
/docs/**/*.html
```

## Verification Checklist

After migration:
- [ ] Docs repo exists at `../boundary-docs/`
- [ ] Docs repo has git history preserved
- [ ] Hugo builds successfully in docs repo: `cd ../boundary-docs && hugo`
- [ ] Docs repo pushed to GitHub
- [ ] Code repo tests still pass: `clojure -M:test:db/h2`
- [ ] README.md updated with new links
- [ ] package.json updated
- [ ] .gitignore updated
- [ ] No broken references to `docs/` in code repo
- [ ] AGENTS.md updated if needed

## Rollback Plan

If something goes wrong:
```bash
# In code repo
git checkout main
git branch -D split-docs-to-separate-repo

# Delete docs repo
rm -rf ../boundary-docs

# Delete GitHub repo (manual via web UI)
```

## Benefits

1. **Separation of Concerns**: Documentation evolves independently from code
2. **Cleaner Code Repo**: Smaller, focused repository
3. **Independent CI/CD**: Docs deploy separately
4. **Better Access Control**: Can give different permissions
5. **Flexible Hosting**: Can use GitHub Pages, Netlify, etc.

## Timeline

- **Step 1 (Script)**: ~2-3 minutes
- **Step 2 (GitHub)**: ~1 minute
- **Step 3 (Push)**: ~1 minute
- **Step 4 (Cleanup)**: ~5 minutes
- **Step 5 (PR)**: ~2 minutes
- **Total**: ~10-15 minutes

## Ready to Execute?

The script is ready at: `scripts/split-docs-repo.sh`

Execute with:
```bash
./scripts/split-docs-repo.sh
```

Then follow the on-screen instructions.
