# Boundary UI/UX Overhaul - Complete Implementation Plan

## Document Information
- **Created**: 2026-01-09
- **Branch**: `feature/ui-visuals` (to be created from `main`)
- **Scope**: All Boundary UIs
- **Estimated Duration**: 7-11 days

---

## Executive Summary

| Decision | Choice |
|----------|--------|
| **CSS Framework** | Pico CSS (keep, enhance with design tokens) |
| **Icons** | Lucide Icons (~50 initial, inline SVG in Clojure) |
| **Sidebar** | Collapsible + Pinnable, left drawer on mobile |
| **Dark Mode** | Priority (Phase 3) |
| **Style** | Minimal, functional, snappy (150ms transitions) |
| **Transitions** | 150ms (snappy), respects `prefers-reduced-motion` |
| **Testing** | Playwright visual regression screenshots |
| **Commit Strategy** | One commit per phase, ask before committing |

---

## Phase Overview

| Phase | Description | Est. Duration | Commit Message |
|-------|-------------|---------------|----------------|
| **1** | Design Foundation | 2-3 days | `feat(ui): Add design foundation - tokens, icons, CSS variables` |
| **2** | Admin Layout | 2-3 days | `feat(ui): Add admin layout with collapsible sidebar` |
| **3** | Dark Mode | 1 day | `feat(ui): Add dark mode support` |
| **4** | Component Polish | 1-2 days | `feat(ui): Polish shared UI components` |
| **5** | Main App Layout | 1-2 days | `feat(ui): Update main app layout and pages` |

---

## Phase 1: Design Foundation

### Files to Create/Update

| Action | File | Purpose |
|--------|------|---------|
| Create | `resources/public/css/tokens.css` | CSS custom properties (colors, spacing, typography) |
| Create | `src/boundary/shared/ui/core/icons.clj` | Lucide icon system (50 icons) |
| Update | `resources/public/css/app.css` | Refactor to use CSS variables |
| Update | `src/boundary/shared/ui/core/layout.clj` | Include tokens.css |
| Create | `test/boundary/visual/screenshots_test.clj` | Visual regression tests |

### Design Tokens Structure

```
Colors:
  - Primary: Blue scale (50-900)
  - Neutral: Zinc scale (0, 50-950)
  - Semantic: Success, Warning, Error, Info
  - Aliases: bg-*, text-*, border-*, sidebar-*

Spacing: --space-0 through --space-16 (0px to 64px)
Typography: Font families, sizes (xs to 3xl), weights
Effects: Shadows (sm to xl), border radii (sm to full)
Transitions: Fast (100ms), Normal (150ms), Slow (200ms)
Layout: Sidebar widths, topbar height
Z-Index: Dropdown (10) to Toast (60)
```

### Icon Set (50 Icons)

- **Navigation**: home, menu, x, chevrons (4 directions)
- **Actions**: plus, minus, edit, trash, save, search, filter, refresh, download, upload, copy, check
- **Status**: check-circle, x-circle, alert-circle, info, loader
- **Entities**: users, user, user-plus, settings, database, file, file-text, folder, inbox, mail, calendar, clock
- **Security**: lock, unlock, shield, key, eye, eye-off
- **UI**: more-horizontal, more-vertical, external-link, log-out, log-in
- **Theme**: sun, moon
- **Sidebar**: panel-left, panel-left-close, panel-left-open, pin, pin-off
- **Sorting**: arrow-up, arrow-down, arrow-up-down
- **Layout**: layout-grid, layout-list

### Success Criteria

- [ ] `tokens.css` created with all design tokens
- [ ] `icons.clj` created with 50 Lucide icons
- [ ] `app.css` refactored to use CSS variables
- [ ] Visual tests pass for login and user list pages
- [ ] Zero lint errors

---

## Phase 2: Admin Layout

### Files to Create/Update

| Action | File | Purpose |
|--------|------|---------|
| Create | `resources/public/css/admin.css` | Sidebar layout, collapsed/pinned states |
| Create | `resources/public/js/sidebar.js` | State management, localStorage persistence |
| Update | `src/boundary/admin/core/ui.clj` | New sidebar component structure |

### Admin Layout Features

- **Collapsible Sidebar**: 256px (expanded) → 64px (collapsed, icon-only)
- **Pinnable**: User can pin sidebar to stay expanded
- **Tooltips**: Show labels on hover when collapsed
- **Mobile Drawer**: Left slide-in with overlay (18rem width)
- **State Persistence**: localStorage for pin/collapse state

### HTML Structure

```html
<div class="admin-shell" 
     data-sidebar-state="expanded"
     data-sidebar-pinned="false"
     data-sidebar-open="false">
  <aside class="admin-sidebar">
    <div class="admin-sidebar-header">...</div>
    <nav class="admin-sidebar-nav">...</nav>
    <div class="admin-sidebar-footer">...</div>
  </aside>
  <div class="admin-overlay"></div>
  <div class="admin-main">
    <header class="admin-topbar">...</header>
    <main class="admin-content">...</main>
  </div>
</div>
```

### Success Criteria

- [ ] `admin.css` created with sidebar layout
- [ ] `sidebar.js` created with state persistence
- [ ] Admin sidebar collapses/expands correctly
- [ ] Pin toggle works and persists
- [ ] Mobile drawer works with overlay
- [ ] Visual tests pass for all admin pages

---

## Phase 3: Dark Mode

### Files to Create/Update

| Action | File | Purpose |
|--------|------|---------|
| Update | `src/boundary/shared/ui/core/components.clj` | Theme toggle component |
| Create | `resources/public/js/theme.js` | Theme toggle logic |
| Update | `resources/public/css/app.css` | Theme toggle styling |

### Dark Mode Features

- Respect `prefers-color-scheme: dark`
- Theme toggle button (sun/moon icons)
- Persist user preference in localStorage
- All colors use CSS variables for automatic dark mode support

### Success Criteria

- [ ] Theme toggle component works
- [ ] Dark mode displays correctly on all pages
- [ ] System preference respected
- [ ] User preference persists in localStorage
- [ ] Visual tests pass for dark mode variants

---

## Phase 4: Component Polish

### Focus Areas

- Replace emoji icons with Lucide icons
- Add HTMX loading indicators
- Improve focus states for accessibility
- Add `.sr-only` utility class
- Skeleton loading states
- Consistent button states

### Success Criteria

- [ ] All shared components use design tokens
- [ ] Emoji icons replaced with Lucide icons
- [ ] Loading indicators styled
- [ ] Focus states accessible
- [ ] Visual tests pass for component states

---

## Phase 5: Main App Layout

### Files to Update

- `src/boundary/shared/ui/core/layout.clj` - Main navigation with icons
- `src/boundary/user/core/ui.clj` - User management pages
- Login/register pages

### Success Criteria

- [ ] Main navigation updated with icons
- [ ] User pages styled consistently
- [ ] Login/register pages polished
- [ ] All pages work in dark mode
- [ ] Full visual test suite passes

---

## Visual Regression Test Matrix

| Page | Light | Dark | Mobile | Sidebar States |
|------|-------|------|--------|----------------|
| Login | ✓ | ✓ | ✓ | - |
| Register | ✓ | ✓ | ✓ | - |
| User List | ✓ | ✓ | ✓ | - |
| User Detail | ✓ | ✓ | ✓ | - |
| Admin Dashboard | ✓ | ✓ | ✓ | expanded, collapsed |
| Admin Entity List | ✓ | ✓ | ✓ | expanded, collapsed |
| Admin Entity Form | ✓ | ✓ | ✓ | - |
| Mobile Drawer | - | - | ✓ | open, closed |

**Total Screenshots**: ~30 baseline images

---

## Testing Commands

```bash
# Run all tests
clojure -M:test:db/h2

# Run visual tests only
clojure -M:test:db/h2 --focus-meta :visual

# Run unit tests (fast)
clojure -M:test:db/h2 --focus-meta :unit

# Lint
clojure -M:clj-kondo --lint src test
```

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Breaking existing UI | High | Visual regression tests, incremental changes |
| CSS specificity conflicts | Medium | Use design tokens, avoid `!important` |
| Dark mode color contrast | Medium | Test with WCAG contrast checker |
| Mobile sidebar bugs | Medium | Test on real devices, not just browser resize |
| HTMX compatibility | Low | Test all dynamic interactions after changes |

---

## Implementation Status

- [x] Plan created and saved
- [ ] Phase 1: Design Foundation
- [ ] Phase 2: Admin Layout
- [ ] Phase 3: Dark Mode
- [ ] Phase 4: Component Polish
- [ ] Phase 5: Main App Layout

---

**Document Version**: 1.0  
**Last Updated**: 2026-01-09
