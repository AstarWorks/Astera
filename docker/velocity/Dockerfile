# Velocity proxy for the local stack: itzg/mc-proxy with Astera's velocity.toml
# baked in (the Docker daemon may be remote, so no bind mounts for config).
# The forwarding secret is NOT in the image: it comes from the
# VELOCITY_FORWARDING_SECRET environment variable at run time.
FROM itzg/mc-proxy:latest
COPY velocity.toml /config/velocity.toml
