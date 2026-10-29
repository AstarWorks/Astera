#!/bin/sh
set -eu
profile=${TRANSLATOR_MODEL_PROFILE:-translategemma}
case "$profile" in
  translategemma)
    repo=mradermacher/translategemma-4b-it-GGUF
    revision=d37868f759d2f817a43d28dd80cf9f982ffa62fd
    file=translategemma-4b-it.Q4_K_M.gguf
    # The adapter supplies the translation prompt, so use plain Gemma turn framing.
    set -- --chat-template gemma
    ;;
  hy-mt)
    repo=tencent/HY-MT1.5-1.8B-GGUF
    revision=265b2e615a7dc9b06c435dc878829ad99a512ba2
    file=HY-MT1.5-1.8B-Q4_K_M.gguf
    set --
    ;;
  lfm2)
    repo=LiquidAI/LFM2-350M-ENJP-MT-GGUF
    revision=889c88c3d3681b4f342ae210a1d2a1369aba0e10
    file=LFM2-350M-ENJP-MT-Q4_K_M.gguf
    set --
    ;;
  *) echo "Unknown TRANSLATOR_MODEL_PROFILE: $profile" >&2; exit 2 ;;
esac
model="/models/$profile-$revision.gguf"
if [ ! -s "$model" ]; then
  echo "Downloading $repo at $revision ($file)" >&2
  # A failed/interrupted transfer never becomes the cached model.
  trap 'rm -f "$model.part"' EXIT HUP INT TERM
  if [ -n "${HF_TOKEN:-}" ]; then
    curl --fail --location --retry 3 --header "Authorization: Bearer $HF_TOKEN" \
      "https://huggingface.co/$repo/resolve/$revision/$file" --output "$model.part"
  else
    curl --fail --location --retry 3 \
      "https://huggingface.co/$repo/resolve/$revision/$file" --output "$model.part"
  fi
  mv "$model.part" "$model"
  trap - EXIT HUP INT TERM
fi
exec /app/llama-server --model "$model" --alias "$profile" --host 0.0.0.0 --port 8080 \
  --ctx-size 4096 --parallel 2 --n-predict 512 --threads "${TRANSLATOR_THREADS:-4}" \
  --n-gpu-layers "${TRANSLATOR_GPU_LAYERS:-0}" "$@"
