# The exact upstream CPU/CUDA image is supplied (and pinned) by Compose.
ARG LLAMA_IMAGE
FROM ${LLAMA_IMAGE}
COPY docker/translator/start.sh /opt/translator/start.sh
ENTRYPOINT ["/bin/sh", "/opt/translator/start.sh"]
