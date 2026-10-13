#!/usr/bin/env bash
# Builds the Debian system that ships inside the APK: the official
# debian:trixie-slim arm64 image (scripts/fetch-rootfs.sh) plus the XFCE
# desktop, TigerVNC and Okno's configuration. Runs on any Linux host:
# arm64 natively, x86_64 through proot + qemu-user.
# Host packages: gtk-update-icon-cache, an aarch64 C compiler (CC_AARCH64,
# default aarch64-linux-gnu-gcc), qemu-user-static on x86_64, and a
# proot that understands statx (PROOT=..., see scripts/build-proot.sh): the
# 5.1.0 shipped by Debian/Ubuntu does not, so file checks inside the image
# would silently look at the host.
#
#   scripts/build-rootfs.sh   →  app/src/main/assets/rootfs.tgz
set -euo pipefail

# Everything lives in main(): bash parses it fully before running, so editing
# this file during a (long) build cannot derail the build in progress.
main() {
  ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
  ASSETS="$ROOT_DIR/app/src/main/assets"
  WORK="${WORK:-$(mktemp -d)}"
  FS="$WORK/rootfs"

  # Packages of the desktop system. --no-install-recommends keeps it lean, so
  # everything the desktop needs is listed explicitly.
  PACKAGES=(
    # desktop
    xfce4-session xfwm4 xfce4-panel xfdesktop4 xfce4-settings xfconf
    xfce4-whiskermenu-plugin xfce4-terminal thunar mousepad
    # display server reached by the app
    tigervnc-standalone-server dbus-x11
    # look and feel
    greybird-gtk-theme elementary-xfce-icon-theme librsvg2-common
    fonts-dejavu-core fonts-noto-color-emoji
    # remote access (started from the app, see rootfs-overlay/usr/local/bin/okno-ssh)
    openssh-server
    # base tools
    locales ca-certificates curl nano less procps psmisc xdg-utils bash-completion
  )

  base_tar="$WORK/base.tgz"
  "$ROOT_DIR/scripts/fetch-rootfs.sh" "$base_tar"

  rm -rf "$FS"
  mkdir -p "$FS"
  tar -xzf "$base_tar" -C "$FS"

  case "$(uname -m)" in
    aarch64) QEMU=() ;;
    *) QEMU=(-q "$(command -v qemu-aarch64-static || command -v qemu-aarch64)") ;;
  esac

  guest() {
    "${PROOT:-proot}" "${QEMU[@]}" -0 -r "$FS" -b /dev -b /proc -b /sys -w /root \
      /usr/bin/env -i HOME=/root LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "$@"
  }

  echo "nameserver 1.1.1.1" > "$FS/etc/resolv.conf"
  # The slim image drops all translations; keep Russian for the desktop.
  echo "path-include /usr/share/locale/ru/*" > "$FS/etc/dpkg/dpkg.cfg.d/okno-locale"
  echo 'APT::Sandbox::User "root";' > "$FS/etc/apt/apt.conf.d/01okno"
  # No GPU behind Xvnc: leave out Mesa's software 3D stack (~180 MB; Xvnc only
  # dlopens it for OpenGL clients) and the HiDPI icon set the desktop never uses.
  cat > "$FS/etc/dpkg/dpkg.cfg.d/okno-slim" <<'EOF2'
# Okno leaves out Mesa's software 3D (no GPU behind the desktop) and HiDPI icons.
# For OpenGL programs: delete this file, then
#   apt install --reinstall libllvm19 mesa-libgallium libgl1-mesa-dri libz3-4
path-exclude /usr/lib/*/libLLVM*
path-exclude /usr/lib/*/libgallium*
path-exclude /usr/lib/*/libz3.so*
path-exclude /usr/lib/*/dri/*
path-exclude /usr/share/icons/elementary-xfce-hidpi/*
EOF2

  # Skipping debconf's preconfigure pass changes nothing in noninteractive mode
  # but saves a lot of time under qemu (build-time only, removed below).
  echo '#clear DPkg::Pre-Install-Pkgs;' > "$FS/etc/apt/apt.conf.d/99okno-build"
  # Icon caches over thousands of files take an hour under proot + qemu;
  # stub the tool during install and build the caches natively below
  # (the cache format does not depend on the CPU).
  guest dpkg-divert --local --rename --add /usr/bin/gtk-update-icon-cache
  printf '#!/bin/sh\nexit 0\n' > "$FS/usr/bin/gtk-update-icon-cache"
  chmod 755 "$FS/usr/bin/gtk-update-icon-cache"

  guest apt-get update
  guest apt-get install -y --no-install-recommends "${PACKAGES[@]}"

  rm "$FS/etc/apt/apt.conf.d/99okno-build" "$FS/usr/bin/gtk-update-icon-cache"
  guest dpkg-divert --local --rename --remove /usr/bin/gtk-update-icon-cache
  for theme in "$FS"/usr/share/icons/*/; do
    if [ -f "$theme/index.theme" ]; then gtk-update-icon-cache --force --quiet "$theme"; fi
  done

  echo "ru_RU.UTF-8 UTF-8" > "$FS/etc/locale.gen"
  echo "en_US.UTF-8 UTF-8" >> "$FS/etc/locale.gen"
  guest locale-gen
  printf 'LANG=ru_RU.UTF-8\n' > "$FS/etc/default/locale"

  # Okno's own files: desktop launcher and XFCE defaults.
  cp -r "$ROOT_DIR/rootfs-overlay/." "$FS/"
  chmod 755 "$FS/usr/local/bin/"*

  # LD_PRELOAD shim that lets sftp-server run under proot (native/nodumpable.c).
  "${CC_AARCH64:-aarch64-linux-gnu-gcc}" -shared -fPIC -O2 -Wall \
    -o "$FS/usr/local/lib/libokno-nodumpable.so" "$ROOT_DIR/native/nodumpable.c"

  # Every phone must get its own SSH host keys (okno-ssh creates them).
  rm -f "$FS"/etc/ssh/ssh_host_*

  # Single properties on top of Debian's packaged defaults (those files also
  # define the session itself, so they are edited, not replaced).
  XF="$FS/etc/xdg/xfce4/xfconf/xfce-perchannel-xml"
  "$ROOT_DIR/scripts/xfconf-set.py" "$XF/xfce4-session.xml" \
    /general/SaveOnExit bool false /general/AutoSave bool false \
    /shutdown/ShowSuspend bool false /shutdown/ShowHibernate bool false \
    /shutdown/ShowHybridSleep bool false /shutdown/ShowSwitchUser bool false
  "$ROOT_DIR/scripts/xfconf-set.py" "$XF/xsettings.xml" \
    /Net/ThemeName string Greybird /Net/IconThemeName string elementary-xfce \
    /Gtk/FontName string "DejaVu Sans 10" /Gtk/MonospaceFontName string "DejaVu Sans Mono 10"

  # The WIN key opens the Start menu, as on Windows (added to XFCE's own defaults).
  KS="$FS/etc/xdg/xfce4/xfconf/xfce-perchannel-xml/xfce4-keyboard-shortcuts.xml"
  grep -q 'name="Super_L"' "$KS" ||
    sed -i '0,/<property name="default" type="empty">/s||&\n      <property name="Super_L" type="string" value="xfce4-popup-whiskermenu"/>|' "$KS"

  guest apt-get clean
  # Mount points proot -q leaves behind (host rootfs for qemu, /dev/null bind).
  rmdir "$FS/host-rootfs" 2>/dev/null || true
  if [ -f "$FS/etc/ld.so.preload" ] && [ ! -s "$FS/etc/ld.so.preload" ]; then rm -f "$FS/etc/ld.so.preload"; fi
  rm -rf "$FS"/var/lib/apt/lists/* "$FS"/var/cache/debconf/*-old "$FS"/var/log/*.log \
    "$FS"/var/log/apt/* "$FS/etc/resolv.conf" "$FS"/tmp/* "$FS"/root/.cache

  mkdir -p "$ASSETS"
  tar --numeric-owner -C "$FS" -czf "$ASSETS/rootfs.tgz.part" .
  mv "$ASSETS/rootfs.tgz.part" "$ASSETS/rootfs.tgz"
  {
    echo "base=$(cat "$base_tar.version")"
    echo "overlay=$(cd "$ROOT_DIR" && cat scripts/build-rootfs.sh native/* $(find rootfs-overlay -type f | sort) | sha256sum | cut -c1-16)"
  } > "$ASSETS/rootfs.version"
  du -sh "$FS" "$ASSETS/rootfs.tgz"
}

main "$@"; exit
