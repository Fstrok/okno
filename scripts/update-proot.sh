#!/usr/bin/env bash
# Fetches proot and its libraries from the Termux package repository and
# repackages them as jniLibs, so Android extracts them into nativeLibraryDir
# (the only app location from which binaries may be executed on Android 10+).
#
# Termux only keeps the current package version in its pool, so the patched
# binaries are committed to git and this script is run only to update them.
set -euo pipefail

REPO=https://packages-cf.termux.dev/apt/termux-main
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
SOURCES="$(cd "$(dirname "$0")" && pwd)/proot-sources.txt"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

command -v patchelf >/dev/null || { echo "patchelf is required (pip install patchelf)" >&2; exit 1; }

curl -fsSL "$REPO/dists/stable/main/binary-aarch64/Packages" -o "$WORK/Packages"

fetch() { # package name -> extracted data dir
  local pkg=$1 file sha
  file=$(awk -v p="$pkg" '$0=="Package: "p{f=1} f&&/^Filename:/{print $2;exit}' "$WORK/Packages")
  sha=$(awk -v p="$pkg" '$0=="Package: "p{f=1} f&&/^SHA256:/{print $2;exit}' "$WORK/Packages")
  curl -fsSL "$REPO/$file" -o "$WORK/$pkg.deb"
  echo "$sha  $WORK/$pkg.deb" | sha256sum -c - >/dev/null
  mkdir -p "$WORK/$pkg"
  (cd "$WORK/$pkg" && ar x "../$pkg.deb" && tar xf data.tar.*)
  echo "$pkg $(basename "$file") $sha" >> "$WORK/versions"
}

fetch proot
fetch libtalloc
fetch libandroid-shmem

P=data/data/com.termux/files/usr
mkdir -p "$OUT"
cp "$WORK/proot/$P/bin/proot"                  "$OUT/libproot.so"
cp "$WORK/proot/$P/libexec/proot/loader"       "$OUT/libproot-loader.so"
cp "$WORK/libtalloc/$P"/lib/libtalloc.so.2.*   "$OUT/libtalloc.so"
cp "$WORK/libandroid-shmem/$P/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"

# jniLibs must be named lib*.so, so drop talloc's ".2" soname suffix and point
# the libraries at their own directory instead of the Termux prefix.
patchelf --set-soname libtalloc.so "$OUT/libtalloc.so"
patchelf --replace-needed libtalloc.so.2 libtalloc.so "$OUT/libproot.so"
for f in libproot.so libtalloc.so libandroid-shmem.so; do
  patchelf --set-rpath '$ORIGIN' "$OUT/$f"
done

{ echo "# Source packages (https://github.com/termux/termux-packages)"; cat "$WORK/versions"; } > "$SOURCES"
cat "$SOURCES"
