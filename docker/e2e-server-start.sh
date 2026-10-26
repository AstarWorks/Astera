#!/bin/sh
# E2E-only setup; the ordinary runtime delegates without changing its configuration.
set -eu
if [ "${ASTERA_E2E_FAST:-0}" = 1 ] && [ "${SERVER_TYPE:-PAPER}" = FOLIA ]; then
  case "${E2E_FOLIA_THREADS:-4}" in ''|*[!0-9]*|0) echo "E2E_FOLIA_THREADS must be positive" >&2; exit 1;; esac
  # itzg patches only existing files. Seed before the first fresh-volume boot.
  mkdir -p /data/config /plugins
  if [ ! -f /data/config/paper-global.yml ]; then
    printf 'threaded-regions:\n  threads: %s\n  grid-exponent: 4\n' "${E2E_FOLIA_THREADS:-4}" > /data/config/paper-global.yml
  fi
  # The fixed four-thread image patch would overwrite the explicit lane value.
  rm -f /patches/threaded-regions.json
  cp /opt/astera/folia-e2e-probe.jar /plugins/
  chown -R "${UID:-1000}:${GID:-1000}" /data/config
fi
exec /usr/local/bin/astera-start "$@"
