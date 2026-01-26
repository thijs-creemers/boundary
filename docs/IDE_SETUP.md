# IDE Setup Guide

Get your development environment optimized for Boundary in **under 10 minutes**.

---

## Table of Contents

- [VSCode + Calva](#vscode--calva-recommended) ⭐ **Recommended**
- [IntelliJ IDEA + Cursive](#intellij-idea--cursive)
- [Emacs + CIDER](#emacs--cider)
- [Vim + vim-fireplace](#vim--vim-fireplace)
- [General Setup](#general-setup-all-editors)

---

## VSCode + Calva (Recommended) ⭐

**Why Calva?**
- Free and open source
- Excellent REPL integration
- Structural editing (Paredit)
- Inline evaluation
- Great for beginners

### Installation (2 minutes)

**1. Install VSCode**
```bash
# macOS
brew install --cask visual-studio-code

# Linux
snap install code --classic

# Windows
# Download from https://code.visualstudio.com/
```

**2. Install Calva Extension**
```bash
# Command line
code --install-extension betterthantomorrow.calva

# Or in VSCode:
# Ctrl+Shift+X (Cmd+Shift+X on Mac)
# Search for "Calva"
# Click Install
```

**3. Install Recommended Extensions**
```bash
code --install-extension eamodio.gitlens        # Git integration
code --install-extension ms-azuretools.vscode-docker  # Docker support
code --install-extension esbenp.prettier-vscode  # Code formatting
```

### Project Setup (3 minutes)

**1. Open Boundary Project**
```bash
cd /path/to/boundary
code .
```

**2. Connect to REPL**
```
Ctrl+Alt+C Ctrl+Alt+J (Windows/Linux)
Cmd+Alt+C Cmd+Alt+J (Mac)
```

**Steps:**
1. Choose: **deps.edn + Clojure CLI**
2. Select alias: **:dev** (or **:repl-clj** for nREPL)
3. Wait for REPL to start (~10 seconds)
4. You'll see "REPL connected" in bottom right

**3. Start Boundary System**

Once connected, you can start the system using the convenience functions in the `user` namespace (automatically loaded via `dev/repl/user.clj`):

```clojure
(go)
;; => :started
```

**Useful system commands:**
- `(go)` - Start the system
- `(reset)` - Reload changed code and restart the system
- `(halt)` - Stop the system
- `(system)` - View the running system components

**Verify it works:**
```bash
curl http://localhost:3000/health
# Should return 200 OK
```

### Essential Keyboard Shortcuts

| Action | Shortcut (Mac) | Shortcut (Win/Linux) |
|--------|----------------|----------------------|
| **REPL** |
| Connect to REPL | `Cmd+Alt+C Cmd+Alt+J` | `Ctrl+Alt+C Ctrl+Alt+J` |
| Load current file | `Cmd+Alt+C Enter` | `Ctrl+Alt+C Enter` |
| Evaluate top-level form | `Cmd+Alt+C Space` | `Ctrl+Alt+C Space` |
| Evaluate current form | `Cmd+Alt+C Cmd+Alt+E` | `Ctrl+Alt+C Ctrl+Alt+E` |
| Show last result | `Cmd+Alt+C Cmd+Alt+V` | `Ctrl+Alt+C Ctrl+Alt+V` |
| Clear REPL | `Cmd+Alt+C Cmd+Alt+L` | `Ctrl+Alt+C Ctrl+Alt+L` |
| **Editing** |
| Slurp forward | `Cmd+Alt+.` | `Ctrl+Alt+.` |
| Barf forward | `Cmd+Alt+,` | `Ctrl+Alt+,` |
| Raise form | `Cmd+Alt+P` | `Ctrl+Alt+P` |
| Select current form | `Cmd+Alt+S` | `Ctrl+Alt+S` |
| **Navigation** |
| Go to definition | `F12` | `F12` |
| Find references | `Shift+F12` | `Shift+F12` |
| Go back | `Ctrl+-` | `Alt+Left` |

### Recommended Settings

Create `.vscode/settings.json`:
```json
{
  "calva.fmt.configPath": ".cljfmt.edn",
  "calva.prettyPrintingOptions": {
    "enabled": true,
    "printEngine": "pprint",
    "width": 80
  },
  "calva.showDocstringInParameterHelp": true,
  "calva.showCalvaButton": true,

  "editor.formatOnSave": true,
  "editor.rulers": [80, 120],
  "editor.renderWhitespace": "boundary",

  "files.exclude": {
    "**/.cpcache": true,
    "**/target": true,
    "**/.nrepl-port": true
  },

  "search.exclude": {
    "**/target": true,
    "**/.cpcache": true
  }
}
```

### Debugging

**Inline Evaluation:**
```clojure
;; Put cursor after form and press Cmd+Alt+C Cmd+Alt+E
(+ 1 2 3)
;; => 6 (shown inline)

;; Debug a function
(defn my-function [x]
  (let [result (* x 2)]
    result  ;; Evaluate this line to see intermediate value
    (+ result 10)))
```

**REPL Debugging:**
```clojure
;; In REPL output, click stack trace links to jump to source
;; Use `tap>` for debugging:
(tap> {:debug "value" :x 123})

;; View tapped values in Calva
;; Cmd+Alt+C Cmd+Alt+T
```

### Common Issues

**Issue: REPL won't connect**
```bash
# Clear cache and try again
rm -rf .cpcache/
# In VSCode: Ctrl+Alt+C Ctrl+Alt+J
```

**Issue: "No project type detected"**
- Make sure you have `deps.edn` in project root
- Check that you're in the project directory

**Issue: Code not loading**
```clojure
;; Reload namespace
(require 'boundary.user.core.user :reload)

;; Or use refresh (already included in dev/repl/user.clj)
(reset)
```

---

## IntelliJ IDEA + Cursive

**Why Cursive?**
- Professional IDE with advanced features
- Excellent refactoring tools
- Built-in debugger
- Great for Java interop

### Installation (5 minutes)

**1. Install IntelliJ IDEA**
```bash
# macOS
brew install --cask intellij-idea-ce  # Community Edition (free)
# or
brew install --cask intellij-idea     # Ultimate Edition (paid)

# Download from https://www.jetbrains.com/idea/
```

**2. Install Cursive Plugin**
1. Open IntelliJ IDEA
2. `Settings/Preferences` → `Plugins`
3. Search for "Cursive"
4. Click `Install`
5. Restart IntelliJ

**Note:** Cursive is free for non-commercial use. For commercial use, purchase a license at https://cursive-ide.com/

### Project Setup (3 minutes)

**1. Import Boundary Project**
1. `File` → `Open`
2. Select `boundary` directory
3. Choose "Import project from external model" → **Clojure deps.edn**
4. Click `Finish`

**2. Configure REPL**
1. `Run` → `Edit Configurations`
2. Click `+` → `Clojure REPL` → `Local`
3. Name: "Boundary Dev REPL"
4. Type: **nREPL**
5. Additional options: `-A:repl-clj`
6. Click `OK`

**3. Start REPL**
1. `Run` → `Run 'Boundary Dev REPL'`
2. Wait for REPL to start
3. In REPL window:
```clojure
(go)
```

### Essential Keyboard Shortcuts

| Action | macOS | Windows/Linux |
|--------|-------|---------------|
| **REPL** |
| Send form to REPL | `Cmd+Shift+P` | `Ctrl+Shift+P` |
| Load file in REPL | `Cmd+Shift+L` | `Ctrl+Shift+L` |
| Switch to REPL | `Cmd+Shift+N` | `Ctrl+Shift+N` |
| **Editing** |
| Slurp forward | `Cmd+Shift+K` | `Ctrl+Shift+K` |
| Barf forward | `Cmd+Shift+J` | `Ctrl+Shift+J` |
| Raise | `Cmd+Alt+Up` | `Ctrl+Alt+Up` |
| **Navigation** |
| Go to definition | `Cmd+B` | `Ctrl+B` |
| Find usages | `Alt+F7` | `Alt+F7` |
| Go to symbol | `Cmd+Alt+Shift+N` | `Ctrl+Alt+Shift+N` |
| **Refactoring** |
| Rename | `Shift+F6` | `Shift+F6` |
| Extract function | `Cmd+Alt+M` | `Ctrl+Alt+M` |
| Inline | `Cmd+Alt+N` | `Ctrl+Alt+N` |

### Debugging

**1. Set Breakpoints**
- Click in gutter next to line number
- Red dot appears

**2. Start Debug Session**
```clojure
;; In REPL:
(require '[clojure.tools.namespace.repl :refer [refresh]])
(refresh)

;; Run your code - execution will pause at breakpoints
```

**3. Debug Controls**
- `F8` - Step over
- `F7` - Step into
- `Shift+F8` - Step out
- `F9` - Resume

### Recommended Settings

**Editor Settings:**
1. `Settings` → `Editor` → `Code Style` → `Clojure`
2. Set indentation: 2 spaces
3. Enable `Align map values`
4. Enable `Align let bindings`

**Structural Editing:**
1. `Settings` → `Editor` → `General` → `Smart Keys`
2. Enable `Use structural editing for Clojure`

---

## Emacs + CIDER

**Why Emacs + CIDER?**
- Extremely powerful and customizable
- Veteran Lisp editor
- Unmatched keyboard-driven workflow
- Best-in-class structural editing

### Installation (5 minutes)

**1. Install Emacs**
```bash
# macOS
brew install --cask emacs

# Linux (Ubuntu/Debian)
sudo apt install emacs

# Or use Emacs Mac Port for better macOS integration
brew tap railwaycat/emacsmacport
brew install emacs-mac --with-modules
```

**2. Install CIDER**

**Option A: Using package.el (Recommended)**
```elisp
;; Add to ~/.emacs.d/init.el:
(require 'package)
(add-to-list 'package-archives
             '("melpa" . "https://melpa.org/packages/"))
(package-initialize)

;; M-x package-refresh-contents
;; M-x package-install RET cider RET
```

**Option B: Using use-package**
```elisp
;; Add to ~/.emacs.d/init.el:
(use-package cider
  :ensure t
  :config
  (setq cider-repl-display-help-banner nil)
  (setq cider-repl-pop-to-buffer-on-connect nil)
  (setq cider-show-error-buffer 'only-in-repl)
  (setq cider-font-lock-dynamically '(macro core function var)))
```

**3. Install Helpful Packages**
```elisp
(use-package paredit :ensure t)      ; Structural editing
(use-package rainbow-delimiters :ensure t)  ; Colorful parens
(use-package company :ensure t)       ; Autocompletion
(use-package flycheck-clj-kondo :ensure t)  ; Linting
```

### Project Setup (2 minutes)

**1. Open Boundary Project**
```bash
emacs /path/to/boundary
```

**2. Start CIDER REPL**
```
M-x cider-jack-in
```

**Steps:**
1. Choose: **deps.edn**
2. Enter alias: `:dev`
3. Wait for REPL to start
4. REPL buffer opens automatically

**3. Start Boundary System**
```clojure
;; In any .clj file, evaluate:
(go)
```

### Essential Key Bindings

| Action | Key Binding |
|--------|-------------|
| **REPL** |
| Start REPL | `M-x cider-jack-in` |
| Load current file | `C-c C-k` |
| Eval last sexp | `C-x C-e` |
| Eval defun | `C-c C-c` |
| Switch to REPL | `C-c C-z` |
| Clear REPL | `C-c M-o` |
| **Editing (with Paredit)** |
| Slurp forward | `C-)` |
| Barf forward | `C-}` |
| Raise | `M-r` |
| Splice | `M-s` |
| **Navigation** |
| Go to definition | `M-.` |
| Return from definition | `M-,` |
| Find references | `M-?` |
| **Documentation** |
| Show docs | `C-c C-d C-d` |
| Show source | `C-c C-d C-s` |
| Show JavaDoc | `C-c C-d C-j` |

### Recommended Configuration

**Complete ~/.emacs.d/init.el example:**
```elisp
;;; init.el --- Boundary Development Setup

(require 'package)
(setq package-archives '(("melpa" . "https://melpa.org/packages/")
                         ("gnu" . "https://elpa.gnu.org/packages/")))
(package-initialize)

;; CIDER configuration
(use-package cider
  :ensure t
  :config
  (setq cider-repl-display-help-banner nil)
  (setq cider-repl-pop-to-buffer-on-connect nil)
  (setq cider-show-error-buffer 'only-in-repl)
  (setq cider-auto-select-error-buffer t)
  (setq cider-repl-use-pretty-printing t)
  (setq cider-repl-result-prefix ";; => ")
  (setq nrepl-log-messages t))

;; Paredit for structural editing
(use-package paredit
  :ensure t
  :hook ((clojure-mode . paredit-mode)
         (emacs-lisp-mode . paredit-mode)
         (cider-repl-mode . paredit-mode)))

;; Rainbow delimiters
(use-package rainbow-delimiters
  :ensure t
  :hook (prog-mode . rainbow-delimiters-mode))

;; Company for autocompletion
(use-package company
  :ensure t
  :config
  (global-company-mode)
  (setq company-idle-delay 0.2)
  (setq company-minimum-prefix-length 1))

;; Clojure mode
(use-package clojure-mode
  :ensure t
  :config
  (setq clojure-indent-style 'always-indent))

;; General settings
(setq-default indent-tabs-mode nil)
(setq-default tab-width 2)
(show-paren-mode 1)
(column-number-mode 1)

;; Enable line numbers
(global-display-line-numbers-mode 1)

;; Theme (optional)
(load-theme 'tango-dark t)
```

### Debugging with CIDER

**1. Instrumented Functions**
```clojure
;; Place cursor on defn and press C-u C-c C-c
(defn my-function [x]
  (let [result (* x 2)]
    (+ result 10)))

;; Call function - debugger activates
(my-function 5)
```

**Debugger commands:**
- `n` - Next
- `i` - Step in
- `o` - Step out
- `c` - Continue
- `q` - Quit debugger

**2. Breakpoints**
```clojure
(defn my-function [x]
  #break  ;; Execution pauses here
  (let [result (* x 2)]
    (+ result 10)))
```

---

## Vim + vim-fireplace

**Why Vim?**
- Lightning fast editing
- Modal editing paradigm
- Minimal resource usage
- Works over SSH

### Installation (5 minutes)

**1. Install Vim/Neovim**
```bash
# Neovim (recommended)
brew install neovim

# Vim 8+
brew install vim
```

**2. Install vim-plug (Plugin Manager)**
```bash
curl -fLo ~/.vim/autoload/plug.vim --create-dirs \
    https://raw.githubusercontent.com/junegunn/vim-plug/master/plug.vim
```

**3. Install vim-fireplace**

Add to `~/.vimrc` (or `~/.config/nvim/init.vim` for Neovim):
```vim
call plug#begin('~/.vim/plugged')

" Clojure support
Plug 'tpope/vim-fireplace'          " REPL integration
Plug 'guns/vim-clojure-static'      " Syntax highlighting
Plug 'guns/vim-sexp'                " S-expression editing
Plug 'tpope/vim-sexp-mappings-for-regular-people'  " Better keybindings
Plug 'tpope/vim-salve'              " Leiningen/deps.edn support
Plug 'guns/vim-clojure-highlight'   " Better highlighting

" General improvements
Plug 'junegunn/fzf', { 'do': { -> fzf#install() } }
Plug 'junegunn/fzf.vim'
Plug 'airblade/vim-gitgutter'

call plug#end()
```

**Install plugins:**
```vim
:PlugInstall
```

### Project Setup (2 minutes)

**1. Start REPL in Terminal**
```bash
cd /path/to/boundary
clojure -M:repl-clj
```

**2. Open Vim in Another Terminal**
```bash
vim src/boundary/user/core.clj
```

**3. Connect to REPL**
```vim
" vim-fireplace auto-connects if nREPL port file exists
" Or manually connect:
:Connect nrepl://localhost:7888
```

**4. Start Boundary System**
```vim
" Evaluate in REPL (cpp = eval paragraph/top-level form):
(go)
```

### Essential Key Bindings

| Action | Command | Description |
|--------|---------|-------------|
| **Evaluation** |
| Eval outer form | `cpp` | Eval form cursor is in |
| Eval file | `:Eval (load-file "%")` | Load current file |
| Eval line | `cqq` | Eval current line |
| **Documentation** |
| Show docs | `K` | Show docs for symbol |
| Go to definition | `[d` | Jump to definition |
| Go to source | `]d` | Jump to source |
| **Navigation** |
| Find symbol | `:Apropos <symbol>` | Search for symbol |
| **REPL** |
| Open REPL | `:Repl` | Open REPL in split |

### Recommended Configuration

**~/.vimrc additions:**
```vim
" Clojure-specific settings
autocmd FileType clojure setlocal expandtab
autocmd FileType clojure setlocal shiftwidth=2
autocmd FileType clojure setlocal tabstop=2

" Better REPL integration
let g:fireplace_no_maps = 0

" Rainbow parentheses (install kien/rainbow_parentheses.vim)
au VimEnter * RainbowParenthesesToggle
au Syntax * RainbowParenthesesLoadRound
au Syntax * RainbowParenthesesLoadSquare
au Syntax * RainbowParenthesesLoadBraces
```

---

## General Setup (All Editors)

### 1. Git Configuration

```bash
# Ignore REPL artifacts
cat >> .git/info/exclude <<EOF
.nrepl-port
.cpcache/
target/
.lsp/
.calva/
EOF
```

### 2. Code Formatting

**Install cljfmt:**
```bash
# Already in deps.edn
clojure -M:cljfmt check
clojure -M:cljfmt fix
```

**Create `.cljfmt.edn`:**
```clojure
{:indents {defroutes [[:inner 0]]
           GET [[:inner 0]]
           POST [[:inner 0]]
           PUT [[:inner 0]]
           DELETE [[:inner 0]]}
 :remove-consecutive-blank-lines? false
 :remove-trailing-whitespace? true
 :insert-missing-whitespace? true
 :align-associative? true}
```

### 3. Linting with clj-kondo

```bash
# Run linter
clojure -M:clj-kondo --lint src test

# Auto-fix issues
clojure -M:clj-kondo --lint src --auto-fix
```

### 4. nREPL Configuration

**Create `~/.nrepl/nrepl.edn`:**
```clojure
{:middleware [cider.nrepl/cider-middleware]}
```

---

## Performance Tips

### Increase JVM Memory
```bash
# Add to shell profile (.bashrc, .zshrc, etc.)
export CLJ_JVM_OPTS="-Xmx2g -Xms512m"
```

### Speed Up REPL Startup
```bash
# Use rebel-readline for better REPL experience
# Already included in :repl-clj alias
clojure -M:repl-clj
```

### Cache Dependencies
```bash
# Pre-download all dependencies
clojure -P -M:dev:test
```

---

## Troubleshooting

### REPL Won't Connect

**Check nREPL port:**
```bash
cat .nrepl-port
# Should show port number (e.g., 7888)
```

**Verify REPL is running:**
```bash
lsof -i :7888
# Should show java process
```

**Restart REPL:**
```bash
# Kill existing REPL
pkill -f "clojure.*nrepl"

# Start new REPL
clojure -M:repl-clj
```

### Code Not Reloading

**Clear namespace cache:**
```clojure
(require '[clojure.tools.namespace.repl :refer [refresh]])
(refresh)
```

**Reset Integrant system:**
```clojure
(ig-repl/halt)
(ig-repl/go)
```

### Syntax Errors

**Check parentheses balance:**
- Most editors show matching parens
- Use structural editing to avoid imbalance

**Common mistakes:**
```clojure
;; Missing closing paren
(defn my-fn [x]
  (+ x 1)  ; <- Missing )

;; Extra closing paren
(defn my-fn [x]
  (+ x 1)))  ; <- Extra )
```

---

## Next Steps

Once your IDE is set up:

1. **Try the REPL workflow:**
   ```clojure
   ;; Edit code -> Evaluate -> See results -> Iterate
   ```

2. **Learn structural editing:**
   - Practice slurp/barf for refactoring
   - Use raise/splice to manipulate forms

3. **Explore REPL-driven development:**
   - [REPL Driven Development Guide](REPL_WORKFLOW.md)

4. **Read the quickstart:**
   - [5-Minute Quickstart](QUICKSTART.md)

---

**Your IDE is ready! Start building with Boundary.** 🚀

*Last updated: 2026-01-26*
