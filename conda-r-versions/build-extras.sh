#!/usr/bin/env bash
# Builds air-studio-extras_<ver>_amd64.deb: bioagent backend bundle,
# conda R-environment machinery (generator + systemd timer), rsession
# wrapper, and the rserver.conf hookup. Depends: air-studio-server (= VER)
# so the protocol-matched pair installs together.
set -euo pipefail

VER="${1:?usage: build-extras.sh <version> [arch]}"
ARCH="${2:-amd64}"
SRC="${AIRSTUDIO_SRC:-$HOME/rs_omp}"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

PKG="$STAGE/air-studio-extras"
mkdir -p "$PKG/opt/air-studio/bioagent" \
         "$PKG/usr/local/sbin" \
         "$PKG/etc/systemd/system" \
         "$PKG/etc/air-studio" \
         "$PKG/DEBIAN"

# 1. bioagent: single-file bundle + static client + protocol pin
#    layout matches ChatConstants: dist/server/main.js + dist/client/index.html
mkdir -p "$PKG/opt/air-studio/bioagent/dist/server" "$PKG/opt/air-studio/bioagent/dist/client"
cp "$SRC/bioagent/dist/server/main.js" "$PKG/opt/air-studio/bioagent/dist/server/"
cp "$SRC/bioagent/package.json" "$SRC/bioagent/protocol.json" "$PKG/opt/air-studio/bioagent/"
cp -r "$SRC/bioagent/dist/client/." "$PKG/opt/air-studio/bioagent/dist/client/"
cp "$SRC/bioagent/dist/csp.json" "$PKG/opt/air-studio/bioagent/dist/" 2>/dev/null || true

# 2. conda machinery
cp "$SRC/conda-r-versions/generate-r-versions.sh" "$PKG/usr/local/sbin/air-studio-conda-r-versions"
chmod 755 "$PKG/usr/local/sbin/air-studio-conda-r-versions"

cat > "$PKG/etc/systemd/system/air-studio-conda-r-versions.service" <<'UNIT'
[Unit]
Description=AIR Studio conda R-environment discovery

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/air-studio-conda-r-versions
UNIT

cat > "$PKG/etc/systemd/system/air-studio-conda-r-versions.timer" <<'UNIT'
[Unit]
Description=Refresh AIR Studio conda R-environment list every 5 minutes

[Timer]
OnBootSec=1min
OnUnitActiveSec=5min
Unit=air-studio-conda-r-versions.service

[Install]
WantedBy=timers.target
UNIT

# 3. rsession wrappers
#    /usr/lib/air-studio/bin/... : AIR Studio's own launcher (air rserver.conf)
#    /usr/local/bin/rsession-conda-wrapper : stock RStudio Server launcher.
#      extras owns this shared path and keeps it targeting the STOCK rsession
#      so installing AIR Studio alongside RStudio cannot break the stock
#      deployment (regression: air wrapper here launched air rsession under
#      the stock rserver -> auth cookie mismatch -> agent WS "connect timeout").
mkdir -p "$PKG/usr/lib/air-studio/bin" "$PKG/usr/local/bin"
cp "$SRC/conda-r-versions/rsession-conda-wrapper" "$PKG/usr/lib/air-studio/bin/"
chmod 755 "$PKG/usr/lib/air-studio/bin/rsession-conda-wrapper"
cp "$SRC/conda-r-versions/rsession-conda-wrapper-rstudio" "$PKG/usr/local/bin/rsession-conda-wrapper"
chmod 755 "$PKG/usr/local/bin/rsession-conda-wrapper"

# 4. control files
cat > "$PKG/DEBIAN/control" <<CTRL
Package: air-studio-extras
Version: $VER
Architecture: $ARCH
Depends: air-studio-server (= $VER)
Section: science
Priority: optional
Maintainer: AIR Studio <yuumeHuang@users.noreply.github.com>
Description: AIR Studio AI-agent backend and conda R-environment machinery
 Bundles the bioagent single-file Node backend (OpenAI-compatible LLM
 gateway configured via BIOAGENT_* environment variables), the conda
 R-environment scanner + systemd timer feeding /etc/air-studio/r-versions,
 and the rsession launcher wrapper that starts sessions in the selected
 environment.
CTRL

cat > "$PKG/DEBIAN/postinst" <<'POST'
#!/bin/sh
set -e
# expose the agent backend to rsession via the system pai install path
mkdir -p /etc/air-studio/pai
ln -sfn /opt/air-studio/bioagent /etc/air-studio/pai/bin
# start the conda env discovery timer
systemctl daemon-reload
systemctl enable --now air-studio-conda-r-versions.timer 2>/dev/null || true
# wire the AIR rsession wrapper; migrate installs that previously pointed
# at the shared /usr/local/bin path (which now targets the stock rsession)
if grep -q '^rsession-path=/usr/local/bin/rsession-conda-wrapper$' /etc/air-studio/rserver.conf 2>/dev/null; then
  sed -i 's|^rsession-path=/usr/local/bin/rsession-conda-wrapper$|rsession-path=/usr/lib/air-studio/bin/rsession-conda-wrapper|' /etc/air-studio/rserver.conf
elif ! grep -q '^rsession-path' /etc/air-studio/rserver.conf 2>/dev/null; then
  printf '\nrsession-path=/usr/lib/air-studio/bin/rsession-conda-wrapper\n' >> /etc/air-studio/rserver.conf
fi
echo 'AIR Studio extras installed.'
echo 'Configure the LLM gateway (OpenAI-compatible):'
echo '  systemctl edit air-studio-server   # add Environment=BIOAGENT_BASE_URL/BIOAGENT_API_KEY/BIOAGENT_MODEL'
POST
chmod 755 "$PKG/DEBIAN/postinst"

cat > "$PKG/DEBIAN/prerm" <<'PRE'
#!/bin/sh
set -e
systemctl disable --now air-studio-conda-r-versions.timer 2>/dev/null || true
PRE
chmod 755 "$PKG/DEBIAN/prerm"

# 5. pack
dpkg-deb --build --root-owner-group "$PKG" "air-studio-extras_${VER}_${ARCH}.deb"
echo "built air-studio-extras_${VER}_${ARCH}.deb"
