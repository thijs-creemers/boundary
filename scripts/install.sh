#!/usr/bin/env bash
# Boundary Framework installer
# Usage: curl -fsSL https://get.boundary-app.org | sh
# Fallback: curl -fsSL https://raw.githubusercontent.com/thijs-creemers/boundary/main/scripts/install.sh | sh

set -euo pipefail

GREEN='\033[0;32m'; RED='\033[0;31m'; DIM='\033[2m'; RESET='\033[0m'
ok()   { echo -e "${GREEN}✓${RESET} $1"; }
fail() { echo -e "${RED}✗${RESET} $1"; exit 1; }
info() { echo -e "${DIM}  $1${RESET}"; }

echo ""
echo "━━━ Boundary Framework Installer ━━━━━━━━━━━━━━━━━━━━━"
echo ""

# ── Detect OS ────────────────────────────────────────────────
if [[ "$OSTYPE" == "darwin"* ]]; then
  OS="macos"
elif grep -qi microsoft /proc/version 2>/dev/null; then
  OS="wsl"
elif [[ -f /etc/debian_version ]]; then
  OS="debian"
elif [[ -f /etc/arch-release ]]; then
  OS="arch"
else
  fail "Unsupported OS. Boundary supports macOS, Debian/Ubuntu, Arch, and WSL2.
  Windows users: install WSL2 first — https://learn.microsoft.com/en-us/windows/wsl/install"
fi
ok "Detected OS: $OS"

# ── Homebrew (macOS only) ─────────────────────────────────────
if [[ "$OS" == "macos" ]]; then
  if command -v brew &>/dev/null; then
    ok "Homebrew already installed"
  else
    info "Installing Homebrew..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)" \
      || fail "Failed to install Homebrew. Install it manually from https://brew.sh and re-run."
    ok "Homebrew installed"
  fi
fi

# ── JVM ──────────────────────────────────────────────────────
if java -version 2>&1 | grep -q "version"; then
  ok "JVM already installed"
else
  info "Installing JVM..."
  if [[ "$OS" == "macos" ]]; then
    brew install --cask temurin 2>/dev/null || fail "Failed to install JVM via brew"
  elif [[ "$OS" == "debian" || "$OS" == "wsl" ]]; then
    if ! command -v sdk &>/dev/null; then
      info "Installing sdkman..."
      curl -s "https://get.sdkman.io" | bash
      # shellcheck disable=SC1090
      source "$HOME/.sdkman/bin/sdkman-init.sh"
    fi
    sdk install java || fail "Failed to install JVM via sdkman"
  elif [[ "$OS" == "arch" ]]; then
    sudo pacman -S --noconfirm jdk-openjdk || fail "Failed to install JVM via pacman"
  fi
  ok "JVM installed"
fi

# ── Clojure CLI ───────────────────────────────────────────────
if command -v clojure &>/dev/null; then
  ok "Clojure CLI already installed"
else
  info "Installing Clojure CLI..."
  if [[ "$OS" == "macos" ]]; then
    brew install clojure 2>/dev/null || fail "Failed to install Clojure via brew"
  else
    curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
    chmod +x linux-install.sh
    sudo ./linux-install.sh && rm linux-install.sh
  fi
  ok "Clojure CLI installed"
fi

# ── Babashka ─────────────────────────────────────────────────
if command -v bb &>/dev/null; then
  ok "Babashka already installed"
else
  info "Installing Babashka..."
  if [[ "$OS" == "macos" ]]; then
    brew install borkdude/brew/babashka 2>/dev/null || fail "Failed to install Babashka via brew"
  else
    curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
    chmod +x install
    sudo ./install && rm install
  fi
  ok "Babashka installed"
fi

# ── bbin ─────────────────────────────────────────────────────
if command -v bbin &>/dev/null; then
  ok "bbin already installed"
else
  info "Installing bbin..."
  bb -e "(babashka.deps/add-deps {:deps '{io.github.babashka/bbin {:git/url \"https://github.com/babashka/bbin\" :git/sha \"HEAD\"}}}) (require 'bbin.cli) (bbin.cli/install! \"bbin\")" 2>/dev/null \
    || { curl -fsSL https://raw.githubusercontent.com/babashka/bbin/master/bbin > /tmp/bbin && chmod +x /tmp/bbin && sudo mv /tmp/bbin /usr/local/bin/bbin; } \
    || fail "Failed to install bbin"
  ok "bbin installed"
fi

# ── PATH ─────────────────────────────────────────────────────
BBIN_BIN="$HOME/.babashka/bbin/bin"
SHELL_RC="$HOME/.zshrc"
[[ "${SHELL:-}" == *"bash"* ]] && SHELL_RC="$HOME/.bashrc"

if [[ ":$PATH:" != *":$BBIN_BIN:"* ]]; then
  echo "export PATH=\"$BBIN_BIN:\$PATH\"" >> "$SHELL_RC"
  ok "Added $BBIN_BIN to PATH in $SHELL_RC"
  info "Run: source $SHELL_RC   (or open a new terminal)"
fi

export PATH="$BBIN_BIN:$PATH"

# ── boundary CLI ──────────────────────────────────────────────
info "Fetching latest boundary release tag..."
BOUNDARY_TAG=$(curl -fsSL https://api.github.com/repos/thijs-creemers/boundary/releases/latest \
  | grep '"tag_name"' \
  | sed 's/.*"tag_name": "\(.*\)".*/\1/') \
  || fail "Failed to fetch latest release tag. Check your internet connection."

if [[ -z "$BOUNDARY_TAG" ]]; then
  fail "Could not determine latest boundary release tag."
fi

info "Installing boundary CLI @ $BOUNDARY_TAG..."
bbin install https://github.com/thijs-creemers/boundary \
  --tag "$BOUNDARY_TAG" \
  --git/root libs/boundary-cli \
  --main-opts '["-m" "boundary.cli.main"]' \
  --as boundary \
  || fail "Failed to install boundary CLI via bbin.
  If --git/root is not supported by your bbin version, upgrade bbin and retry."

ok "boundary CLI installed"

echo ""
echo -e "${GREEN}━━━ Install complete ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""
echo "  Next step:"
echo ""
echo "    boundary new <your-app-name>"
echo ""
