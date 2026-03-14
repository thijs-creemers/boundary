#!/usr/bin/env bash
set -euo pipefail

REPO_OWNER="thijs-creemers"
REPO_NAME="boundary"
REF="${BOUNDARY_STARTER_REF:-main}"
TARGET_DIR="${1:-boundary-starter}"

if ! command -v curl >/dev/null 2>&1; then
  echo "Error: curl is required but not installed." >&2
  exit 1
fi

if ! command -v tar >/dev/null 2>&1; then
  echo "Error: tar is required but not installed." >&2
  exit 1
fi

if [ -e "${TARGET_DIR}" ]; then
  echo "Error: target '${TARGET_DIR}' already exists." >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

archive_url="https://codeload.github.com/${REPO_OWNER}/${REPO_NAME}/tar.gz/${REF}"
archive_path="${tmp_dir}/boundary.tar.gz"

echo "Downloading starter from ${REPO_OWNER}/${REPO_NAME}@${REF}..."
curl -fsSL "${archive_url}" -o "${archive_path}"

echo "Extracting starter files..."
tar -xzf "${archive_path}" -C "${tmp_dir}"

src_dir="${tmp_dir}/${REPO_NAME}-${REF}/starter"
if [ ! -d "${src_dir}" ]; then
  echo "Error: could not find starter directory in downloaded archive." >&2
  exit 1
fi

mkdir -p "${TARGET_DIR}"
cp -R "${src_dir}/." "${TARGET_DIR}/"

echo
echo "Starter ready in '${TARGET_DIR}'."
echo "Next steps:"
echo "  cd ${TARGET_DIR}"
echo "  bb setup"
