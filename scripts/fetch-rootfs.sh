#!/usr/bin/env bash
# Downloads the Debian arm64 root filesystem that the APK bundles as an asset.
# The image is pinned by layer digest (official "debian" image on Docker Hub),
# so the download is verified byte-for-byte. Bump DIGEST to update Debian.
set -euo pipefail

IMAGE=library/debian
TAG=trixie-slim # informational: the digest below is what actually gets fetched
DIGEST=sha256:bbeda6b4abb749f743f4547ef5a28070a14de8caef211daa5afb78d5b5fa21ba

OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/rootfs.tgz"
mkdir -p "$(dirname "$OUT")"

if [ -f "$OUT" ] && echo "${DIGEST#sha256:}  $OUT" | sha256sum -c - >/dev/null 2>&1; then
  echo "$DIGEST" > "$(dirname "$OUT")/rootfs.version"
  echo "rootfs already up to date"
  exit 0
fi

TOKEN=$(curl -fsSL "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$IMAGE:pull" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
curl -fsSL -H "Authorization: Bearer $TOKEN" \
  "https://registry-1.docker.io/v2/$IMAGE/blobs/$DIGEST" -o "$OUT.part"
echo "${DIGEST#sha256:}  $OUT.part" | sha256sum -c -
mv "$OUT.part" "$OUT"
echo "$DIGEST" > "$(dirname "$OUT")/rootfs.version"
echo "fetched Debian $TAG ($DIGEST)"
