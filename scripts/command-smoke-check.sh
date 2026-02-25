#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "[smoke] Verifying required aliases exist in deps.edn"
required_aliases=(":migrate" ":test" ":repl-clj" ":docs-lint")

has_alias() {
  local alias_name="$1"
  local pattern="^[[:space:]]*${alias_name}[[:space:]]*\\{"
  if command -v rg >/dev/null 2>&1; then
    rg -q "${pattern}" deps.edn
  else
    grep -Eq "${pattern}" deps.edn
  fi
}

for alias in "${required_aliases[@]}"; do
  if ! has_alias "${alias}"; then
    echo "[smoke] Missing required alias in deps.edn: ${alias}" >&2
    exit 1
  fi
  echo "[smoke] OK alias ${alias}"
done

echo "[smoke] Checking migrate CLI entrypoint"
clojure -M:migrate --help >/dev/null

echo "[smoke] Checking test runner entrypoint"
clojure -M:test --help >/dev/null

echo "[smoke] Running docs lint"
clojure -M:docs-lint >/dev/null

echo "[smoke] Running AGENTS link check"
python3 scripts/check-agents-links.py >/dev/null

echo "[smoke] Command smoke checks passed"
