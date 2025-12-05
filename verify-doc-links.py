#!/usr/bin/env python3
"""
Verify internal documentation links are valid.
Checks link: references in .md and .adoc files.
"""

import os
import re
from pathlib import Path


def extract_links(content, file_type):
    """Extract link references from content."""
    if file_type == "md":
        # Markdown: [text](path)
        pattern = r"\[([^\]]+)\]\(([^)]+)\)"
    else:
        # AsciiDoc: link:path[text]
        pattern = r"link:([^\[]+)\[([^\]]+)\]"

    matches = re.findall(pattern, content)
    if file_type == "md":
        return [
            (url, text)
            for text, url in matches
            if not url.startswith(("http://", "https://", "#", "mailto:"))
        ]
    else:
        return [
            (url, text)
            for url, text in matches
            if not url.startswith(("http://", "https://", "#", "mailto:"))
        ]


def check_link_exists(source_file, link_path):
    """Check if a linked file exists."""
    source_dir = source_file.parent

    # Handle relative paths
    if link_path.startswith("../"):
        target = (source_dir / link_path).resolve()
    else:
        target = (source_dir / link_path).resolve()

    return target.exists(), target


def main():
    docs_dir = Path("docs")

    if not docs_dir.exists():
        print("❌ docs/ directory not found")
        return 1

    all_files = list(docs_dir.rglob("*.md")) + list(docs_dir.rglob("*.adoc"))
    broken_links = []
    total_links = 0

    print(f"🔍 Checking {len(all_files)} documentation files...")

    for file_path in all_files:
        with open(file_path, "r", encoding="utf-8") as f:
            content = f.read()

        file_type = "md" if file_path.suffix == ".md" else "adoc"
        links = extract_links(content, file_type)
        total_links += len(links)

        for link, text in links:
            exists, target = check_link_exists(file_path, link)
            if not exists:
                broken_links.append((file_path, link, text, target))

    print(f"\n📊 Results:")
    print(f"   Files checked: {len(all_files)}")
    print(f"   Links checked: {total_links}")
    print(f"   Broken links: {len(broken_links)}")

    if broken_links:
        print(f"\n⚠️  Broken Links Found:\n")
        for source, link, text, target in broken_links:
            print(f"  {source}")
            print(f"    → {link} ('{text}')")
            print(f"    Expected: {target}")
            print()
        return 1
    else:
        print("\n✅ All internal links are valid!")
        return 0


if __name__ == "__main__":
    exit(main())
