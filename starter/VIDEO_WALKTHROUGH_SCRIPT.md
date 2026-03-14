# Boundary Starter - Video Walkthrough Script

**Duration:** 5 minutes  
**Target Audience:** Developers new to Boundary Framework  
**Goal:** Show how fast you can go from zero to running Clojure web application

---

## Scene 1: Introduction (30 seconds)

### Voiceover
"Boundary Starter is the fastest way to bootstrap production-ready Clojure web applications. In the next 5 minutes, I'll show you how to go from zero to a running application with authentication, admin UI, and database migrations—all in under 60 seconds."

### Screen Actions
- Show README.md header
- Briefly scroll through feature list:
  - ✅ Production-grade architecture (FC/IS)
  - ✅ 18 curated libraries
  - ✅ Pre-configured templates
  - ✅ Custom template wizard
  - ✅ Zero build step required

### Key Points
- Production-ready from day one
- No boilerplate configuration
- Battle-tested architecture

---

## Scene 2: Quick Start Demo (1 minute 30 seconds)

### Voiceover
"Let's see it in action. I'll start with nothing but a terminal."

### Screen Actions

**Step 1: Clone the repository (5s)**
```bash
# Terminal shows empty directory
pwd
# /Users/demo/projects

git clone https://github.com/boundary-dev/boundary-starter.git
cd boundary-starter
```

**Step 2: Run setup wizard (30s)**
```bash
bb setup
```

**Show terminal output:**
```
Welcome to Boundary Starter Setup! 🚀

Available templates:
1. minimal        - Core libraries only (learning/prototyping)
2. api-only       - RESTful API with auth (backend services)
3. microservice   - API + jobs + observability (Kubernetes-ready)
4. web-app        - Full-stack web app with admin UI
5. saas           - Multi-tenant SaaS (web-app + tenant isolation)

[?] Select template: 
```

**Choose web-app:**
```
[?] Select template: web-app
[?] Project name (my-project): demo-app
[?] Project description: Demo application for video walkthrough
[?] Author name (Your Name): Boundary Team
[?] Author email (you@example.com): team@boundary.dev

✓ Template: web-app
✓ Project: demo-app
✓ Author: Boundary Team <team@boundary.dev>

Selected libraries (10):
  - core, observability, platform, user, admin
  - storage, cache, jobs, email, search

[?] Proceed? (Y/n): Y
```

**Step 3: Watch generation (10s)**
```
Creating project structure...
✓ Created src/demo_app/core.clj
✓ Created deps.edn
✓ Created resources/conf/dev/config.edn
✓ Generated 47 files in 0.05 seconds

Next steps:
  cd /Users/demo/projects/demo-app
  export JWT_SECRET="dev-secret-minimum-32-characters"
  clojure -M:repl-clj
```

**Step 4: Start the application (15s)**
```bash
cd ../demo-app
export JWT_SECRET="dev-secret-minimum-32-characters"
clojure -M:repl-clj
```

**Show REPL output:**
```
nREPL server started on port 7888
Loading system configuration...
Starting Boundary application...
✓ Database migrations applied
✓ HTTP server started on http://localhost:3000
✓ Admin UI available at http://localhost:3000/admin

user=>
```

**Step 5: Show running application (15s)**
```clojure
;; In REPL
(require '[integrant.repl :as ig-repl])
(ig-repl/go)
```

**Switch to browser:**
- Open http://localhost:3000/admin
- Show login page (auto-generated)
- Show admin dashboard (auto-generated CRUD UI)
- Briefly show users table

### Key Points
- **47 files generated in 0.05 seconds**
- **Zero configuration required**
- **Running application in under 60 seconds**

### Voiceover Highlight
"Notice we didn't write a single line of code. We have authentication, admin UI, database migrations, and a full REPL-driven development environment—ready to go."

---

## Scene 3: Template Tour (1 minute 30 seconds)

### Voiceover
"Boundary Starter includes 5 pre-configured templates. Let's see what each one gives you."

### Screen Actions

**Show templates/ directory:**
```bash
ls -1 templates/
```

**Output:**
```
_base.edn
minimal.edn
api-only.edn
microservice.edn
web-app.edn
saas.edn
```

**Open each template briefly (15s each):**

**1. minimal.edn (15s)**
```bash
cat templates/minimal.edn
```

**Voiceover:**
"Minimal template—just core, observability, and platform. Perfect for learning Boundary or building a proof-of-concept."

**Show key line:**
```edn
:libraries [:core :observability :platform]
```

**2. api-only.edn (15s)**
```bash
cat templates/api-only.edn
```

**Voiceover:**
"API-only template adds authentication and user management. Ideal for RESTful APIs or mobile backends."

**Show key line:**
```edn
:libraries [:core :observability :platform :user]
```

**3. microservice.edn (15s)**
```bash
cat templates/microservice.edn
```

**Voiceover:**
"Microservice template adds background jobs, caching, and email. Kubernetes-ready with health checks and metrics."

**Show key lines:**
```edn
:libraries [:core :observability :platform :user :cache :jobs :email]
:features {:health-checks true :metrics true}
```

**4. web-app.edn (15s)**
```bash
cat templates/web-app.edn
```

**Voiceover:**
"Web-app template—the one we just used. Full-stack with admin UI, file storage, and search."

**Show key line:**
```edn
:libraries [:core :observability :platform :user :admin :storage :cache :jobs :email :search]
```

**5. saas.edn (15s)**
```bash
cat templates/saas.edn
```

**Voiceover:**
"SaaS template adds multi-tenancy, realtime features, and workflows. Production-ready for SaaS applications."

**Show key lines:**
```edn
:libraries [:core :observability :platform :user :admin :storage :cache :jobs :email :tenant :realtime :workflow :search]
:features {:multi-tenant true}
```

### Key Points
- 5 templates from minimal to full SaaS
- Each template = curated library combination
- Pick the closest match, customize later

---

## Scene 4: Custom Template Wizard (1 minute)

### Voiceover
"Don't see exactly what you need? Use the interactive library wizard to build your own template."

### Screen Actions

**Step 1: Start wizard (5s)**
```bash
cd ../boundary-starter  # Back to starter repo
bb setup
```

**Choose option 6:**
```
[?] Select template: 6 (Custom - choose libraries interactively)
```

**Step 2: Interactive library selection (30s)**
```
=== Custom Template: Library Selection ===

Select libraries to include:

Foundation (required):
  [✓] core           - Validation, case conversion, interceptors
  [✓] observability  - Logging, metrics, error reporting
  [✓] platform       - HTTP, database, CLI infrastructure

Authentication & Authorization:
  [ ] user           - JWT auth, MFA, password reset

UI & Content:
  [✓] admin          - Auto-CRUD admin interface
  [ ] storage        - File storage (local & S3)
  [ ] search         - Full-text search

Infrastructure:
  [✓] cache          - Distributed caching (Redis)
  [✓] jobs           - Background job processing
  [ ] email          - SMTP email sending

Advanced:
  [ ] tenant         - Multi-tenancy (schema-per-tenant)
  [ ] realtime       - WebSocket/SSE for real-time features
  [ ] workflow       - State machine workflows
  [ ] external       - Stripe, Twilio, IMAP/SMTP adapters
  [ ] reports        - PDF/CSV reports with scheduling
  [ ] calendar       - Calendar events with recurrence
  [ ] geo            - Geocoding and distance calculations

[?] Toggle selection (space), confirm (enter):
```

**Select: core, observability, platform, admin, cache, jobs**

**Step 3: Save custom template (10s)**
```
[?] Save this template for reuse? (Y/n): Y
[?] Template name: admin-dashboard
[?] Template description: Admin dashboard with caching and jobs

✓ Saved to saved-templates/admin-dashboard.edn

Next time, select this template from the list!
```

**Step 4: Show saved template (10s)**
```bash
cat saved-templates/admin-dashboard.edn
```

**Output:**
```edn
{:name "admin-dashboard"
 :description "Admin dashboard with caching and jobs"
 :libraries [:core :observability :platform :admin :cache :jobs]
 :template-type :custom
 :created-at "2026-03-14T10:30:00Z"}
```

### Key Points
- 18 libraries to choose from
- Interactive selection with descriptions
- Save custom templates for reuse
- Mix and match as needed

### Voiceover Highlight
"Your custom template is now available in the template list. Use it to generate new projects instantly."

---

## Scene 5: Next Steps & Resources (30 seconds)

### Voiceover
"That's Boundary Starter. Let's recap what you can do next."

### Screen Actions

**Show README.md "Next Steps" section:**

**Scroll through:**
1. **Explore the codebase**
   - `src/` - Your application code
   - `libs/` - Boundary libraries (read-only reference)
   - `test/` - Your tests

2. **Start REPL-driven development**
   - `(ig-repl/go)` - Start system
   - `(ig-repl/reset)` - Reload changes
   - Hot-reload everything without restarting

3. **Read the documentation**
   - Architecture guide (FC/IS pattern)
   - Library-specific guides (18 libraries)
   - ADRs (architectural decisions)

4. **Join the community**
   - GitHub Discussions
   - Issue tracker
   - Contributing guide

**Show final terminal command:**
```bash
# View all available documentation
ls docs-site/content/

architecture/
guides/
api/
libraries/
```

### Voiceover Closing
"Boundary Starter gives you a production-ready foundation in under 60 seconds. Clone the repo, run `bb setup`, and start building. Links to everything are in the description below. Happy coding!"

### Screen Actions
- Fade to Boundary logo
- Show GitHub URL: https://github.com/boundary-dev/boundary-starter
- Show docs URL: https://boundary.dev/docs

---

## Technical Setup Notes

### Recording Setup
- **Resolution:** 1920x1080 (1080p)
- **Terminal:** iTerm2 or macOS Terminal
  - Font: Menlo 14pt or Monaco 14pt
  - Theme: High contrast (dark background, light text)
  - Window size: 120x30 characters
- **Browser:** Chrome or Firefox
  - Window size: 1280x720
  - Zoom: 125% (for readability)
- **Screen Recording:** QuickTime, OBS, or ScreenFlow
  - Frame rate: 30 fps
  - Audio: Voiceover (record separately, sync in editing)

### Pre-Recording Checklist
- [ ] Clean terminal history (`history -c`)
- [ ] Close unnecessary applications
- [ ] Disable notifications (Do Not Disturb mode)
- [ ] Prepare demo environment:
  - [ ] Fresh clone of boundary-starter
  - [ ] JWT_SECRET environment variable set
  - [ ] Pre-generated demo-app (for fallback)
  - [ ] Browser bookmarks for localhost:3000/admin
- [ ] Test all commands once before recording
- [ ] Verify timing (5-minute target)

### Commands to Pre-Test

**Terminal commands:**
```bash
# Scene 2
git clone https://github.com/boundary-dev/boundary-starter.git
cd boundary-starter
bb setup
cd ../demo-app
export JWT_SECRET="dev-secret-minimum-32-characters"
clojure -M:repl-clj

# Scene 3
ls -1 templates/
cat templates/minimal.edn
cat templates/api-only.edn
cat templates/microservice.edn
cat templates/web-app.edn
cat templates/saas.edn

# Scene 4
bb setup  # Choose option 6
cat saved-templates/admin-dashboard.edn

# Scene 5
ls docs-site/content/
```

**REPL commands:**
```clojure
(require '[integrant.repl :as ig-repl])
(ig-repl/go)
```

**Browser URLs:**
```
http://localhost:3000/admin
```

### Timing Breakdown

| Scene | Duration | Content |
|-------|----------|---------|
| 1. Introduction | 30s | Feature overview |
| 2. Quick Start | 1m 30s | Clone → running app |
| 3. Template Tour | 1m 30s | 5 templates |
| 4. Custom Wizard | 1m | Library selection |
| 5. Next Steps | 30s | Resources |
| **Total** | **5m 0s** | |

### Editing Notes

**Cuts/Edits:**
- Speed up `git clone` if slow (2x speed)
- Speed up `clojure -M:repl-clj` startup (2x speed)
- Cut pauses between commands (keep under 2 seconds)
- Add "Loading..." overlay during slow operations

**Graphics/Overlays:**
- Title card: "Boundary Starter - 5-Minute Walkthrough"
- Scene transitions: Fade 0.5s
- Annotations: Arrow pointing to terminal output when highlighting specific lines
- End card: GitHub URL, docs URL, Discord/community link

**Audio:**
- Voiceover: Clear, energetic, conversational tone
- Background music: Subtle, non-distracting (optional)
- Sound effects: Terminal typing sounds (optional)

### Fallback Plan

If live demo fails during recording:

1. **Use pre-generated project** - Have `demo-app/` ready
2. **Use edited footage** - Record Scene 2 separately, edit together
3. **Use screenshots** - Fallback to screenshot slideshow with voiceover

### Post-Production Checklist

- [ ] Verify 5-minute duration (±15 seconds)
- [ ] Check audio sync
- [ ] Add captions/subtitles (for accessibility)
- [ ] Test playback at 1080p
- [ ] Export formats:
  - [ ] YouTube (1080p, MP4)
  - [ ] Embedded player (720p, MP4)
  - [ ] GIF preview (15s, first 30s of Scene 2)
- [ ] Upload to YouTube
- [ ] Update README.md with video link

---

## YouTube Metadata

### Title
```
Boundary Starter - From Zero to Running Clojure Web App in 60 Seconds
```

### Description
```
Boundary Starter is the fastest way to bootstrap production-ready Clojure web applications.

In this 5-minute walkthrough, you'll see how to:
✅ Generate a full-stack web application in under 60 seconds
✅ Choose from 5 pre-configured templates (minimal to SaaS)
✅ Use the interactive library wizard to build custom templates
✅ Start REPL-driven development with zero configuration

🔗 Links:
- GitHub: https://github.com/boundary-dev/boundary-starter
- Documentation: https://boundary.dev/docs
- Boundary Framework: https://github.com/boundary-dev/boundary

⏱️ Timestamps:
0:00 Introduction
0:30 Quick Start Demo (Clone → Running App)
2:00 Template Tour (5 Templates)
3:30 Custom Template Wizard
4:30 Next Steps & Resources

🏷️ What is Boundary?
Boundary is a production-grade Clojure web framework with:
- Functional Core / Imperative Shell architecture
- 18 curated libraries (auth, admin UI, multi-tenancy, jobs, etc.)
- REPL-driven development with hot-reload
- PostgreSQL, SQLite, H2 support
- No build step required (just Clojure)

💬 Questions? Join our community:
- GitHub Discussions: https://github.com/boundary-dev/boundary-starter/discussions
- Issues: https://github.com/boundary-dev/boundary-starter/issues

#Clojure #WebDevelopment #BoundaryFramework #REPL #Tutorial
```

### Tags
```
Clojure, Web Development, Boundary Framework, REPL, Tutorial, Functional Programming, Backend Development, Full Stack, REST API, Microservices, SaaS, Open Source
```

### Thumbnail Ideas

**Option 1: Terminal Screenshot**
- Background: Terminal showing `bb setup` output
- Overlay text: "0 → Running App in 60s"
- Logo: Boundary Starter logo

**Option 2: Before/After**
- Left side: Empty terminal
- Right side: Browser showing admin UI
- Center text: "60 seconds"

**Option 3: Template Grid**
- Show 5 template icons
- Text: "5 Templates | 18 Libraries | 0 Config"

---

## Script Version History

**v1.0** (2026-03-14)
- Initial script creation
- 5-minute duration
- 5 scenes: Intro, Quick Start, Templates, Wizard, Next Steps
- Technical setup notes
- YouTube metadata

---

**Ready for recording.** Follow pre-recording checklist, test all commands, then record scenes 1-5 in sequence.
