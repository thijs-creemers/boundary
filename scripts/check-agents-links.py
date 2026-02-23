#!/usr/bin/env python3
"""Validate local markdown links in AGENTS documentation only.

Checks:
- root AGENTS.md
- libs/*/AGENTS.md

Exits non-zero when broken local links are found.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LINK_RE = re.compile(r"\[[^\]]+\]\(([^)]+)\)")


def iter_agents_files(root: Path) -> list[Path]:
    files = [root / "AGENTS.md"]
    files.extend(sorted((root / "libs").glob("*/AGENTS.md")))
    return [f for f in files if f.exists()]


def is_skippable(link: str) -> bool:
    return link.startswith(("http://", "https://", "mailto:", "#"))


def resolve_target(base_file: Path, link: str) -> Path:
    target = link.split("#", 1)[0]
    if target.startswith("/"):
        return Path(target)
    return (base_file.parent / target).resolve()


def main() -> int:
    files = iter_agents_files(ROOT)
    broken: list[tuple[Path, str, Path]] = []
    checked_links = 0

    for file in files:
        content = file.read_text(encoding="utf-8")
        for match in LINK_RE.finditer(content):
            link = match.group(1).strip()
            if is_skippable(link):
                continue
            checked_links += 1
            target = resolve_target(file, link)
            if not target.exists():
                broken.append((file, link, target))

    print(f"AGENTS files checked: {len(files)}")
    print(f"Local links checked: {checked_links}")
    print(f"Broken links: {len(broken)}")

    if broken:
        for file, link, target in broken:
            rel = file.relative_to(ROOT)
            print(f"\n{rel}\n  -> {link}\n  => {target}")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
