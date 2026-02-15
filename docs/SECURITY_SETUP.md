# Repository Security Setup Guide

**Last Updated**: 2026-02-15  
**Purpose**: Ensure maintainer-only control over PR merging and Clojars releases

---

## 1. Branch Protection Rules (GitHub Settings)

### Steps to Configure:

1. Go to: `https://github.com/thijs-creemers/boundary/settings/branches`
2. Click **"Add rule"** (or **"Add branch protection rule"**)
3. Configure the following:

#### Branch Name Pattern
```
main
```

#### Protection Rules to Enable

**✅ Require a pull request before merging**
- ✅ Require approvals: **1**
- ✅ Dismiss stale pull request approvals when new commits are pushed
- ✅ Require review from Code Owners (optional, see section 3)

**✅ Require status checks to pass before merging**
- ✅ Require branches to be up to date before merging
- Required status checks:
  - `lint`
  - `test-core`
  - `test-observability`
  - `test-platform`
  - `test-user`
  - `test-admin`
  - `test-storage`
  - `test-cache`
  - `test-jobs`
  - `test-tenant`
  - `test-email`
  - `test-realtime`
  - `test-scaffolder`
  - `test / Test All Libraries`

**✅ Require conversation resolution before merging**

**✅ Do not allow bypassing the above settings**
- ⚠️ **IMPORTANT**: Ensure "Allow specified actors to bypass required pull requests" is **NOT** checked
- Exception: If you want to allow yourself to force-push in emergencies, add your username but understand the risk

**✅ Restrict who can push to matching branches**
- Add rule: **Restrict pushes that create matching branches**
- Allowed actors: **Only you (thijs-creemers)** or leave empty for "no direct pushes"

**✅ Block force pushes**

**✅ Do not allow deletions**

---

## 2. Repository Collaborator Settings

### Current Access Control:
- Repository owner: **thijs-creemers** (you)
- Collaborators: (run `gh api repos/thijs-creemers/boundary/collaborators` to check)

### Steps to Verify/Configure:

1. Go to: `https://github.com/thijs-creemers/boundary/settings/access`
2. Review all collaborators
3. Remove or downgrade any users who should NOT have write access
4. For contributors who need to submit PRs:
   - **Do NOT add as collaborators**
   - They can fork the repo and submit PRs from forks
   - You retain full merge control

### Recommended Access Levels:
- **You (maintainer)**: Admin
- **Contributors**: No direct access (use forks + PRs)
- **Bots/CI**: No additional permissions needed (workflows use secrets)

---

## 3. GitHub Secrets (Clojars Publishing)

### Currently Required Secrets:
Your `publish.yml` workflow requires these secrets (already configured if publishing works):

```
CLOJARS_USERNAME - Your Clojars username
CLOJARS_PASSWORD - Your Clojars deploy token (NOT your account password)
```

### Steps to Verify:

1. Go to: `https://github.com/thijs-creemers/boundary/settings/secrets/actions`
2. Verify both secrets exist
3. **Do NOT share these secrets** with anyone

### To Generate Clojars Deploy Token:
1. Log in to https://clojars.org
2. Go to your profile → **Deploy Tokens**
3. Create a new deploy token with **minimal scope** (publish only)
4. Use this token as `CLOJARS_PASSWORD`, NOT your account password

---

## 4. Clojars Group Ownership

### Steps to Secure Clojars Publishing:

1. Log in to https://clojars.org
2. Navigate to your group: `io.github.thijs-creemers`
3. Go to **Group Settings**
4. Verify **Group Members**:
   - Ensure only YOU are listed as a member
   - Remove any other users if present
5. Verify **Deploy Permissions**:
   - Set to "Members only" (not "Anyone")

### How to Check Current Clojars Group Members:
```bash
# Install clj-watson or use Clojars web UI
# Web UI: https://clojars.org/groups/io.github.thijs-creemers
```

---

## 5. Optional: CODEOWNERS File

Create a `CODEOWNERS` file to automatically request your review on all PRs.

### Create `.github/CODEOWNERS`:
```
# Global owner - all files require review from @thijs-creemers
* @thijs-creemers

# Critical files - extra scrutiny
/.github/workflows/ @thijs-creemers
/libs/*/build.clj @thijs-creemers
/deps.edn @thijs-creemers
```

This ensures:
- GitHub automatically assigns you as reviewer on all PRs
- Branch protection can enforce your approval

---

## 6. Workflow Permissions

### Steps to Restrict GitHub Actions:

1. Go to: `https://github.com/thijs-creemers/boundary/settings/actions`
2. Under **Workflow permissions**:
   - Select: **Read repository contents and packages permissions**
   - ✅ **Do NOT** select "Read and write permissions"
3. Under **Fork pull request workflows**:
   - ✅ Require approval for first-time contributors

### Why This Matters:
- Prevents workflows in PRs from malicious forks from accessing secrets
- Your `publish.yml` workflow will still work because it runs on `push` to tags (not on PRs)

---

## 7. Verification Checklist

After completing the above steps, verify your security:

### Branch Protection:
- [ ] Create a test branch
- [ ] Try to push directly to `main` → Should be blocked
- [ ] Open a PR from test branch
- [ ] Try to merge without approval → Should be blocked
- [ ] Try to merge with failing tests → Should be blocked

### Clojars Publishing:
- [ ] Only workflows triggered by YOU can publish
- [ ] Publishing only happens on:
  - Manual workflow dispatch (by you)
  - Tag pushes (by you)
- [ ] Verify no one else can trigger `publish.yml`

### Test Security:
```bash
# As repository owner, test branch protection
git checkout -b test-security
echo "test" > test.txt
git add test.txt
git commit -m "test: security check"
git push origin test-security

# Try to merge via GitHub UI:
# 1. Should require PR
# 2. Should require CI to pass
# 3. Should require your approval
```

---

## 8. Emergency Procedures

### If Someone Publishes Unauthorized Release:

1. **Immediately revoke Clojars deploy token**:
   - Log in to https://clojars.org
   - Delete the compromised deploy token
   - Generate a new token
   - Update GitHub secret `CLOJARS_PASSWORD`

2. **Contact Clojars support**:
   - Email: contact@clojars.org
   - Request removal of unauthorized version

3. **Rotate all secrets**:
   - Generate new deploy tokens
   - Update all GitHub repository secrets

### If Branch Protection is Bypassed:

1. **Revert the commit**:
   ```bash
   git revert <bad-commit-sha>
   git push origin main
   ```

2. **Review access logs**:
   - Go to: `https://github.com/thijs-creemers/boundary/settings/security-log`
   - Identify who bypassed protection

3. **Review and tighten branch protection rules**

---

## Summary

After completing this guide:

✅ **PR Merging**: Only you can approve and merge PRs  
✅ **Direct Pushes**: Blocked to `main` branch  
✅ **CI Required**: All tests must pass before merge  
✅ **Clojars Publishing**: Only your credentials can publish  
✅ **Workflow Security**: Secrets protected from fork PRs  
✅ **Group Ownership**: Only you control `io.github.thijs-creemers` on Clojars

---

## Quick Commands Reference

```bash
# Check branch protection status
gh api repos/thijs-creemers/boundary/branches/main/protection

# List collaborators
gh api repos/thijs-creemers/boundary/collaborators

# List repository secrets (names only, values hidden)
gh secret list

# Check workflow runs
gh run list --workflow=publish.yml

# View security log (requires web browser)
# https://github.com/thijs-creemers/boundary/settings/security-log
```

---

**Next Steps**: Follow sections 1-7 in order, then complete the verification checklist in section 7.
