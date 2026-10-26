#!/bin/sh
# Astera entrypoint wrapper: picks the server jar by SERVER_TYPE (baked from the
# Dockerfile build arg) and hands off to the itzg start script.
#   PAPER (default) -> itzg TYPE/VERSION from the env (compose sets TYPE=PAPER).
#   FOLIA           -> the Folia jar baked at /opt/folia/folia.jar (version pinned
#                      in gradle/libs.versions.toml `folia`), run as itzg TYPE=CUSTOM.
set -e
# Install before the server discovers datapacks and loads its fixed levels.
mkdir -p "/data/${LEVEL:-world}/datapacks"
rm -rf "/data/${LEVEL:-world}/datapacks/astera-void"
cp -R /opt/astera/void-world "/data/${LEVEL:-world}/datapacks/astera-void"
# This wrapper runs as root; the server (uid 1000) must own the world dir it is about to create.
chown -R "${UID:-1000}:${GID:-1000}" "/data/${LEVEL:-world}"
export LEVEL_TYPE="${LEVEL_TYPE:-astera:void}"
if [ -z "${GENERATOR_SETTINGS:-}" ]; then
  export GENERATOR_SETTINGS='{"biome":"minecraft:plains","features":false,"lakes":false,"layers":[{"block":"minecraft:air","height":1}],"structure_overrides":[]}'
fi
if [ "${SERVER_TYPE:-PAPER}" = "FOLIA" ]; then
  export TYPE=CUSTOM
  export CUSTOM_SERVER=/opt/folia/folia.jar
fi
exec /image/scripts/start "$@"
