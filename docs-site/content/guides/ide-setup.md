---
title: "IDE Setup Guide"
weight: 20
description: "Configure your development environment for Clojure development with Boundary Framework"
---

# IDE Setup Guide

Quick setup guide for popular Clojure development environments.

## Quick start

**Recommended**: VSCode + Calva (easiest setup, best beginner experience)

| IDE | Setup Time | Best For |
|-----|------------|----------|
| **VSCode + Calva** ⭐ | 2 min | Beginners, general use |
| **IntelliJ + Cursive** | 5 min | Java developers |
| **Emacs + CIDER** | 10 min | Emacs users |
| **Vim + vim-fireplace** | 10 min | Vim users |

---

## VSCode + Calva (Recommended) ⭐

### Installation

1. **Install VSCode**: Download from [code.visualstudio.com](https://code.visualstudio.com/)

2. **Install Calva Extension**:
   - Open VSCode
   - Press `Cmd+Shift+X` (Mac) or `Ctrl+Shift+X` (Windows/Linux)
   - Search for "Calva"
   - Click Install

### Project setup

1. **Open Project**:
   ```bash
   cd boundary
   code .
   ```bash

2. **Start REPL**:
   - Press `Cmd+Shift+P` (Mac) or `Ctrl+Shift+P` (Windows/Linux)
   - Type "Calva: Start a Project REPL"
   - Select "deps.edn"
   - Choose alias: `:repl-clj`

3. **Verify Connection**:
   - Status bar should show "nREPL Connected"
   - Try evaluating: `(+ 1 2)` → should return `3`

### Essential shortcuts

| Action | Mac | Windows/Linux |
|--------|-----|---------------|
| **Evaluate form** | `Cmd+Enter` | `Ctrl+Enter` |
| **Evaluate file** | `Cmd+Shift+C Enter` | `Ctrl+Shift+C Enter` |
| **Load namespace** | `Cmd+Shift+C N` | `Ctrl+Shift+C N` |
| **Run tests** | `Cmd+Shift+C T` | `Ctrl+Shift+C T` |
| **Format code** | `Cmd+Shift+I` | `Ctrl+Shift+I` |

### Recommended settings

Add to `.vscode/settings.json`:

```json
{
  "calva.paredit.defaultKeyMap": "strict",
  "calva.prettyPrintingOptions": {
    "enabled": true,
    "width": 80
  },
  "editor.formatOnSave": true,
  "editor.bracketPairColorization.enabled": true
}
```text

### Debugging

Use `tap>` for debugging:

```clojure
(def result (some-function x))
(tap> {:debug/result result})  ; View in Calva output
```bash

---

## IntelliJ IDEA + Cursive

### Installation

1. **Install IntelliJ IDEA**: Download Community or Ultimate from [jetbrains.com](https://www.jetbrains.com/idea/)

2. **Install Cursive Plugin**:
   - Settings → Plugins
   - Search "Cursive"
   - Install and restart

### Project setup

1. **Import Project**:
   - File → Open → Select `deps.edn`
   - Cursive auto-detects configuration

2. **Start REPL**:
   - Run → Edit Configurations
   - Add "Clojure REPL" → "Local"
   - Set alias: `:repl-clj`
   - Click Run

### Essential shortcuts

| Action | Shortcut |
|--------|----------|
| **Evaluate form** | `Cmd+Shift+P` / `Ctrl+Shift+P` |
| **Load file** | `Cmd+Shift+L` / `Ctrl+Shift+L` |
| **Run tests** | `Cmd+Shift+T` / `Ctrl+Shift+T` |

---

## Emacs + CIDER

### Installation

**Via package.el**:

```elisp
(require 'package)
(add-to-list 'package-archives
             '("melpa" . "https://melpa.org/packages/"))
(package-initialize)

;; Install packages
(package-install 'clojure-mode)
(package-install 'cider)
(package-install 'paredit)
```bash

### Project setup

```bash
cd boundary
emacs
M-x cider-jack-in
```sql

Select alias: `:repl-clj`

### Essential key bindings

| Action | Binding |
|--------|---------|
| **Evaluate form** | `C-x C-e` |
| **Evaluate buffer** | `C-c C-k` |
| **Load namespace** | `C-c C-n` |
| **Run tests** | `C-c C-t n` |

### Recommended configuration

Add to `~/.emacs.d/init.el`:

```elisp
;; Enable paredit for all Clojure buffers
(add-hook 'clojure-mode-hook #'paredit-mode)
(add-hook 'cider-repl-mode-hook #'paredit-mode)

;; Enable eldoc
(add-hook 'cider-mode-hook #'eldoc-mode)

;; Pretty printing
(setq cider-repl-use-pretty-printing t)
```bash

---

## Vim + vim-fireplace

### Installation

**Via vim-plug**:

```vim
call plug#begin('~/.vim/plugged')
Plug 'tpope/vim-fireplace'
Plug 'guns/vim-clojure-static'
Plug 'guns/vim-sexp'
call plug#end()
```bash

Then run: `:PlugInstall`

### Project setup

```bash
cd boundary
clojure -M:repl-clj  # Start REPL in terminal
vim
```bash

Vim-fireplace auto-connects to running nREPL.

### Essential key bindings

| Action | Binding |
|--------|---------|
| **Evaluate form** | `cpp` |
| **Evaluate file** | `:%Eval` |
| **Run tests** | `:RunTests` |

---

## General setup (all editors)

### Git configuration

```bash
git config --global core.autocrlf input
git config --global core.editor "code --wait"  # Or vim, emacs, etc.
```bash

### Code formatting

**Manual format** (all editors):
```bash
clojure -M:cljfmt check
clojure -M:cljfmt fix
```bash

**Auto-format on save**: Covered in editor-specific sections above

### Linting with clj-kondo

**Install**:
```bash
brew install borkdude/brew/clj-kondo  # Mac
# Or download from https://github.com/clj-kondo/clj-kondo
```text

**Run**:
```bash
clj-kondo --lint src test libs/*/src libs/*/test
```bash

### nREPL Configuration

Default nREPL port: **7888** (configured in `deps.edn`)

**Connect external editor**:
```bash
# Start REPL first
clojure -M:repl-clj

# Then connect from editor to localhost:7888
```bash

---

## Testing your setup

### 1. Start REPL

```bash
clojure -M:repl-clj
```bash

Should see: `nREPL server started on port 7888`

### 2. Connect from Editor

- VSCode: `Cmd+Shift+P` → "Calva: Connect to Running REPL"
- IntelliJ: Tools → REPL → Connect to Remote REPL
- Emacs: `M-x cider-connect`
- Vim: Auto-connects

### 3. Evaluate Code

Try in any Clojure file:

```clojure
(+ 1 2)  ; Should return 3

(require '[integrant.repl :as ig-repl])
(ig-repl/go)  ; Start system
```bash

If you see results, setup is complete! ✅

---

## Troubleshooting

### REPL Won't Start

**Check Java version**:
```bash
java -version  # Should be 11+
```text

**Check deps.edn**:
```bash
clojure -Stree  # Should show dependency tree
```bash

### Editor won't connect

**Verify nREPL port**:
```bash
lsof -i :7888  # Should show running process
```text

**Check .nrepl-port file**:
```bash
cat .nrepl-port  # Should contain "7888"
```bash

### Formatting not working

**Install cljfmt alias**:
```bash
clojure -M:cljfmt check  # Should run without errors
```

### Paredit issues

If parentheses feel "sticky":
- VSCode: Disable strict paredit in settings
- Emacs: `M-x paredit-mode` to toggle
- Practice with paredit tutorial: https://www.emacswiki.org/emacs/PareditCheatsheet

---

## Next steps

- **Read**: [AGENTS.md](../../AGENTS.md) - Development commands and patterns
- **Try**: [Quickstart](../getting-started/quickstart.md) - Build your first feature
- **Explore**: [Testing Guide](testing.md) - Write tests with REPL

---

**Last Updated**: 2026-02-15  
**Tested With**: VSCode 1.85, IntelliJ 2023.3, Emacs 29, Vim 9
