# Local chat translation (ADR-0308)

Inference runs in `llama-server`, outside Minecraft. No paid API or SDK. CPU is
the default. The image is pinned by OCI digest in Compose; model URLs use
immutable Hugging Face revision SHAs in `start.sh`. The first start downloads
one Q4_K_M GGUF to `translator-models`; interrupted downloads are discarded.
Review/accept the model's license before use (Gemma terms for TranslateGemma;
Tencent/Liquid terms for the alternatives). Set `HF_TOKEN` if access requires it.

From the repo root, start **only** the optional translator service:

```sh
docker compose -f docker/docker-compose.yml --profile translation up -d --build translator
```

Enable the plugin by setting `ASTERA_TRANSLATE_URL=http://translator:8080` on the
backends and recreating them through the manager's normal deploy workflow.
An unset/empty URL disables translation even with `translation.enabled: true`.
There is no startup dependency on inference. The default endpoint published to
the host is `127.0.0.1:18080`; `/health` returns 200 after model loading.

NVIDIA host (e.g. RTX 3060), with Docker NVIDIA Container Toolkit installed:

```sh
docker compose -f docker/docker-compose.yml -f docker/translator/compose.gpu.yml \
  --profile translation up -d --build translator
```

This overlay selects the pinned CUDA image, reserves one GPU and offloads all
layers. For a separate machine, run the translator there and set the backends'
URL to its private address. Set `TRANSLATOR_BIND` to the private interface address
on that host; do not publish an unauthenticated inference endpoint publicly.
No k8s manifest is added: the existing cluster deploys no similar inference sidecar.

## Model selection and benchmark

| `translation.model_profile` / `TRANSLATOR_MODEL_PROFILE` | Model | Directions |
| --- | --- | --- |
| `translategemma` (default) | TranslateGemma 4B Q4_K_M | en/ja/ko/es |
| `hy-mt` | HY-MT1.5-1.8B Q4_K_M | en/ja/ko/es |
| `lfm2` | LFM2-350M-ENJP-MT Q4_K_M | en↔ja only |

Both the plugin YAML and the container environment must select the same profile.
Restart the translator after changing its environment. Each profile has a
separate cached model file. TranslateGemma uses plain `--chat-template gemma`
turn framing: the HTTP adapter provides its full official translation prompt,
avoiding version-dependent structured content support. HY-MT uses its non-Chinese
user prompt; LFM2 uses its required English/Japanese system prompt. The adapter
rejects unsupported language directions. No model-specific prompt exists in the relay.

Run the 40-line corpus against one profile at a time after selecting/loading it:

```sh
docker/translator/bench.sh translategemma=http://localhost:18080
# Change TRANSLATOR_MODEL_PROFILE=hy-mt, recreate only translator, then:
docker/translator/bench.sh hy-mt=http://localhost:18080
# Repeat for lfm2. Or provide all three profile=URL arguments for separate servers.
```

With no arguments the script probes ports 18080/18081/18082 for the three
profiles. It prints each translation, finish reason, wall latency, p50/p95/max and
count over the default 1500 ms budget. Corpus includes short/slang/emoji/romaji
**already converted** by Japanize, matching inference input. Benchmark calls are
uncached and allow 120 seconds so slow CPU results remain visible; the plugin
still cuts off after its configured total budget. Judge intent preservation
from this output before picking a model; a model loading successfully does not
establish translation quality or the RTX 3060 latency target.

## Chat behavior and settings

`content/chat/chat.yaml` controls enabled, languages (default `[en, ja]`, optional
`ko`/`es`), timeout_ms, marker and model_profile. After Japanize, a cheap script
heuristic chooses Japanese for kana/kanji, Korean for hangul, Spanish for selected
words/diacritics, otherwise English. Ambiguous Latin defaults to English; mixed
messages get one source language. This is deliberately approximate, especially
for short slang. Japanize can be switched off for players writing Latin languages.
Only the converted text goes to inference; the romaji echo stays in the original
shown on hover. Translate once per configured target other than the source, put
all translations in the CHAT frame, then local/remote recipients select their
existing i18n locale. Missing translation, disabled preference or source-language
viewer means original. Timeout/failure logs one warning and sends original once.
Game→Discord always formats the original; Discord→game uses the same translation
stage, retaining the Discord original without player-specific Japanize.

Japanize bypasses lines containing known player names, just as it already does
for URLs; kanji conversion therefore cannot rename a mentioned player.
URLs (including query ampersands) and names known on the sending backend are
masked and restored verbatim. A response changing/dropping those placeholders is
rejected. Decoration is removed before inference; model-supplied codes are
stripped. Only an allowed sender's leading decoration is applied to the translated
literal text. Rank/prefix/suffix/name color and the chat format remain unchanged.

`/astera chat translate on|off` controls the viewer. Japanize and translate toggles
are saved as atomic per-player/per-preference files in
`ASTERA_CHAT_PREFERENCES_DIR` (default `plugins/Astera-Chat/preferences`). No new
Core fields/migrations are needed. Compose mounts one `chat-preferences` volume
on both backends; they read current files off-thread, so preferences survive
reconnect/restart/server switches. Multiple physical backend hosts must mount
the same shared directory, or settings are host-local. Moving these fields into
Core PlayerSettings/Postgres needs the Core/Persistence owners, not a chat shim.

## Verification

`ChatTranslationScenarioTest` exercises two backend relays, shipped config, viewer
selection/marker/hover, timeout/late reply, protection, Discord, durable toggles
and a local HTTP server through the real adapter (three profiles and LRU hits).
`live.ChatLiveTest` adds an English-viewer/Japanese-sender probe; it skips with a
reason when inference `/health` is unreachable. The live runner defaults to
`http://translator:8080`; for a remote URL, its harness must pass
`ASTERA_TRANSLATE_URL` into the runner too. A reachable translator with an unset
URL on the backends fails the probe (it is not silently treated as success).
The manager runs live tests; this track does not touch running dev backends.

Prompt references: [llama.cpp Gemma template](https://github.com/ggml-org/llama.cpp/blob/master/models/templates/google-translategemma-4b-it.jinja),
[HY-MT prompts](https://huggingface.co/tencent/HY-MT1.5-1.8B#prompts),
[LFM2 required system prompts](https://huggingface.co/LiquidAI/LFM2-350M-ENJP-MT#-model-details).
