#!/usr/bin/env bash
# rstudio-conda-r-versions: expose conda R environments to RStudio Server.
#
# Scans public conda roots (/opt/miniforge3/envs) and every user's personal
# env roots (~/.conda/envs, ~/{miniconda3,anaconda3,miniforge3}/envs) for
# environments containing bin/R, then writes them as DCF entries to
# /etc/rstudio/r-versions where rserver's RVersionsScanner picks them up.
#
# Labels carry ownership so users can tell shared envs from personal ones:
#   conda: sc-r-base (public)
#   conda: hym/my-env (personal)
#
# When the generated file differs from the previous one, rserver is restarted
# so the new list reaches sessions (rsession processes survive the restart).
# This file is fully managed - manual edits are overwritten.
set -euo pipefail

OUT=${AIR_STUDIO_ETC:-/etc/air-studio}/r-versions
PUBLIC_ROOTS=(/opt/miniforge3/envs /opt/miniconda3/envs /opt/anaconda3/envs)
HOME_ENV_RELS=(.conda/envs miniconda3/envs anaconda3/envs miniforge3/envs)
LOG_TAG=air-studio-r-versions

log() { logger -t "$LOG_TAG" "$*"; echo "$*"; }
# Collect unique R-bearing envs: path<TAB>label
declare -A seen=()
entries=()
add_env() { # $1=env dir (with trailing /), $2=label; records path<TAB>label<TAB>r_version
   local env=$1 label=$2 path=${1%/}
   [ -x "$env/bin/R" ] || return 0
   local real
   real=$(readlink -f "$path") || return 0
   [ -n "${seen[$real]:-}" ] && return 0
   seen[$real]=1
   # r-base version from conda-meta (no process spawn; empty when absent)
   local rver
   rver=$(ls "$env/conda-meta/r-base-"*.json 2>/dev/null | head -1 | sed 's/.*r-base-\([0-9][^-]*\)-.*/\1/')
   entries+=("${real}"$'\t'"$2"$'\t'"${rver:-}")
}

for root in "${PUBLIC_ROOTS[@]}"; do
   [ -d "$root" ] || continue
   for env in "$root"/*/; do
      [ -d "$env" ] || continue
      add_env "$env" "conda: $(basename "${env%/}") (public)"
   done
done

for homedir in /home/*/; do
   user=$(basename "$homedir")
   for rel in "${HOME_ENV_RELS[@]}"; do
      [ -d "$homedir$rel" ] || continue
      for env in "$homedir$rel"/*/; do
         [ -d "$env" ] || continue
         add_env "$env" "conda: $user/$(basename "${env%/}") (personal)"
      done
   done
done

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
{
   echo "# Managed by $LOG_TAG - DO NOT EDIT (regenerated automatically)."
   echo "# Conda environments containing R, refreshed by systemd timer."
   first=1
   for entry in "${entries[@]}"; do
      path=${entry%%$'\t'*}; rest=${entry#*$'\t'}
      label=${rest%%$'\t'*}; rver=${rest#*$'\t'}
      if [ $first -eq 0 ]; then echo ""; fi
      echo "Label: $label"
      echo "Path: $path"
      [ -n "$rver" ] && echo "Version: $rver"
      first=0
   done
} > "$tmp"

if [ -f "$OUT" ] && cmp -s "$tmp" "$OUT"; then
   exit 0
fi

install -m 644 "$tmp" "$OUT"
log "updated $OUT (${#entries[@]} conda R env(s))"

# rserver caches the version list at startup; restart to expose changes.
# rsession processes are independent and keep running.
if systemctl is-active --quiet air-studio-server; then
   systemctl restart air-studio-server
   log "restarted air-studio-server to reload R versions"
fi
