# Problems - Boundary Polish & Launch

## Unresolved Blockers

_Subagents append findings here - never overwrite_

---

### Task 1: Clojars Credentials & GPG Setup (Feb 14)

**Status:** ✅ VALIDATION COMPLETE - Infrastructure ready, 2 blockers identified

**Blockers Found:**

1. **Clojars Account Not Created** (CRITICAL)
   - Group `io.github.thijs-creemers` not claimed on Clojars
   - Verified: `curl https://clojars.org/groups/io.github.thijs-creemers` → 404
   - Resolution: Create Clojars account, claim group (see evidence document)

2. **Credentials Not Set** (CRITICAL)
   - Environment variables missing: CLOJARS_USERNAME, CLOJARS_PASSWORD
   - Maven settings (~/.m2/settings.xml) not configured
   - Verified: `env | grep -i clojars` → no output
   - Resolution: Generate deploy token on Clojars, set env vars (see evidence document)

3. **GPG Keys Not Generated** (OPTIONAL - for production)
   - No GPG secret keys configured
   - Verified: `gpg --list-secret-keys` → no output
   - Impact: Can use unsigned deployments for now, GPG recommended for production
   - Resolution: Run `gpg --full-generate-key` when ready (see evidence document)

**What's Working:**
- ✅ JAR builds: `clojure -T:build jar` → 46KB boundary-core-0.1.247.jar
- ✅ POM generation: Valid XML with correct fields
- ✅ Deploy task: `clojure -T:build deploy` executes, fails at auth (expected)
- ✅ All 7 libraries ready to publish (same setup as core)

**Evidence Document:**
- Location: `.sisyphus/evidence/task-1-clojars-validation.md`
- Contains: 4000+ words, detailed resolution steps, publishing sequence
- Provides: Step-by-step instructions for user to resolve blockers

**Dependencies:**
- Task 2 (JAR signing) depends on blockers 1-3 being resolved
- Publishing can begin once credentials are set up

**Next Action:** User must create Clojars account and claim group (estimated: 10 minutes)

---

### Clojars Credentials Resolved (Feb 14)

**Status**: ✅ BLOCKER REMOVED

**Credentials Provided**:
- Username: `thijs-creemers`
- Password: `W4oCbdEmixeYtdoTHCjs` (deploy token)

**Impact**:
- Tasks 5, 6, 10 now unblocked
- Can proceed with Week 2a (build.clj + publish.yml)
- Publishing workflow ready for execution

**Next Actions**:
- Set environment variables for local testing
- Add GitHub secrets for CI/CD workflow
- Test publish workflow with boundary-core

---
