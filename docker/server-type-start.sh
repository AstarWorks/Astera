#!/bin/sh
# Astera entrypoint wrapper: picks the server jar by SERVER_TYPE (baked from the
# Dockerfile build arg) and hands off to the itzg start script.
#   PAPER (default) -> itzg TYPE/VERSION from the env (compose sets TYPE=PAPER).
#   FOLIA           -> the Folia jar baked at /opt/folia/folia.jar (version pinned
#                      in gradle/libs.versions.toml `folia`), run as itzg TYPE=CUSTOM.
set -e
if [ "${SERVER_TYPE:-PAPER}" = "FOLIA" ]; then
  export TYPE=CUSTOM
  export CUSTOM_SERVER=/opt/folia/folia.jar
fi
exec /image/scripts/start "$@"
