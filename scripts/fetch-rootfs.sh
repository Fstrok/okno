#!/usr/bin/env bash
# Downloads the official debian:trixie-slim arm64 image layer, the base that
# scripts/build-rootfs.sh turns into Okno's system. Pinned by layer digest,
# so the download is verified byte-for-byte. Bump DIGEST to update Debian.
#
#   scripts/fetch-rootfs.sh OUT.tgz   (also writes OUT.tgz.version)
set -euo pipefail

IMAGE=library/debian
TAG=trixie-slim # informational: the digest below is what actually gets fetched
DIGEST=sha256:bbeda6b4abb749f743f4547ef5a28070a14de8caef211daa5afb78d5b5fa21ba

OUT=${1:?usage: fetch-rootfs.sh OUT.tgz}
mkdir -p "$(dirname "$OUT")"

if ! { [ -f "$OUT" ] && echo "${DIGEST#sha256:}  $OUT" | sha256sum -c - >/dev/null 2>&1; }; then
  TOKEN=$(curl -fsSL "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$IMAGE:pull" |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
  curl -fsSL -H "Authorization: Bearer $TOKEN" \
    "https://registry-1.docker.io/v2/$IMAGE/blobs/$DIGEST" -o "$OUT.part"
  echo "${DIGEST#sha256:}  $OUT.part" | sha256sum -c -
  mv "$OUT.part" "$OUT"
fi
echo "debian:$TAG@$DIGEST" > "$OUT.version"
