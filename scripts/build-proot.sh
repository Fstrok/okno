#!/usr/bin/env bash
# Builds a host proot for scripts/build-rootfs.sh. Distribution packages ship
# proot 5.1.0, which does not translate statx(): Debian 13's glibc uses it for
# every stat(), so file checks during the image build would see the host.
#
#   scripts/build-proot.sh DIR   →  DIR/proot
# Needs: git, make, gcc, libtalloc-dev, uthash-dev.
set -euo pipefail

COMMIT=25dc6a3134891f98a79f57ce1c2c1b23ff15cad1 # proot-me/proot, v5.5.0 prep
OUT=${1:?usage: build-proot.sh DIR}
SRC=$(mktemp -d)
trap 'rm -rf "$SRC"' EXIT

git -C "$SRC" init -q
git -C "$SRC" fetch -q --depth 1 https://github.com/proot-me/proot "$COMMIT"
git -C "$SRC" checkout -q FETCH_HEAD
make -s -C "$SRC/src" proot
mkdir -p "$OUT"
cp "$SRC/src/proot" "$OUT/proot"
"$OUT/proot" --version | tail -n +6 | head -n 2
