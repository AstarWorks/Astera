# Local chat translation (ADR-0308, ADR-0309)

Inference runs in `llama-server`, outside Minecraft. No paid API or SDK. CPU is
the default. The image is pinned by OCI digest in Compose; model URLs use
immutable Hugging Face revision SHAs in `start.sh`. The first start downloads
one Q4_K_M GGUF to `translator-models`; interrupted downloads are discarded.
Review/accept the model's license before use (Gemma terms for TranslateGemma;
Tencent terms for HY-MT). Set `HF_TOKEN` if access requires it.

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

This overlay selects the pinned CUDA image, TranslateGemma, reserves one GPU and
offloads all layers. Set `translation.model_profile: translategemma` in the backend
chat YAML too. For a separate machine, run the translator there and set the backends'
URL to its private address. Set `TRANSLATOR_BIND` to the private interface address
on that host; do not publish an unauthenticated inference endpoint publicly.
No k8s manifest is added: the existing cluster deploys no similar inference sidecar.

## Model selection and benchmark

| `translation.model_profile` / `TRANSLATOR_MODEL_PROFILE` | Model | Directions |
| --- | --- | --- |
| `hy-mt` (CPU default) | HY-MT1.5-1.8B Q4_K_M | en/ja/ko/es |
| `translategemma` (GPU overlay) | TranslateGemma 4B Q4_K_M | en/ja/ko/es |

Both the plugin YAML and container environment must select the same profile.
LFM2 is removed because its short chat translations did not preserve intent.
Restart the translator after changing its environment. Each profile has a
separate cached model file. TranslateGemma uses `--no-jinja --chat-template gemma`:
without `--no-jinja`, the pinned server treats `gemma` as literal Jinja text.
`POST /apply-template` with a user message must return `<start_of_turn>user` and
`<start_of_turn>model`, never a literal `gemma`. HY-MT uses its embedded template.

`content/chat/chat.yaml` is the shared source for context, profile prompt templates,
direction-specific few-shot examples and slang glossary. Both profiles receive a
user turn describing casual Minecraft PvP chat. Glossary matching is longest-first,
case-insensitive and bounded by Unicode letters/numbers/underscore; it replaces
whole messages/tokens, never arbitrary substrings. Whole glossary messages (including
punctuation) bypass inference. Tokens in longer messages are substituted before
inference. Names and URLs are protected first. Fullwidth `ｗ` is detected as Japanese.
A glossary supplies unambiguous short meanings; it is not a general classifier.

Run the 60-line corpus (Python 3 and PyYAML required):

```sh
python3 -m pip install PyYAML
docker/translator/bench.sh hy-mt=http://localhost:18080
# Change TRANSLATOR_MODEL_PROFILE=translategemma, recreate only translator, then:
docker/translator/bench.sh translategemma=http://localhost:18080
```

With no arguments, ports 18080/18081 represent HY-MT/TranslateGemma. Override
`ASTERA_CHAT_CONFIG` to use another chat YAML. Output includes translations,
`finish`, `check` (PASS/FAIL for 20 keyword-scored cases, `-` otherwise), expected
keywords and latency. A keyword miss, request failure or unfinished scored response
sets a nonzero exit code. Summaries separate uncached model latency from pipeline
latency including dictionary bypasses. The corpus includes en/ja, ko/es directions,
all requested slang, emoji and romaji **already converted** by Japanize.
Keyword matches are a regression signal; read the actual translations to judge intent.
The bench permits 120 seconds; the plugin retains its total 1500 ms budget.

For a sandbox with unreachable host ports, run a Python helper on
`--network container:<isolated-translator>`, install PyYAML, copy `bench.sh` and
`chat.yaml`, and set `ASTERA_CHAT_CONFIG=/chat.yaml`. Use an isolated model container
and the cached model volume; never attach to or mutate `astera-dev-*`.

## Remote dev autodeploy

`docker/docker-compose.remote.yml` enables translator by clearing its optional
profile (`!reset []`), pins HY-MT to four threads/four CPUs and 3 GiB, and sets
`ASTERA_TRANSLATE_URL=http://translator:8080` for lobby and game. The base stack
keeps the optional profile and an empty backend URL. Makefile `dev-*` targets and
`dev-deploy.sh` already select the remote overlay for `ASTERA_DEV_HOST`; no new
Make target or host-specific secret is needed. Requires Docker Compose supporting
`!reset` (the existing deployment Compose supports it).

The watcher defaults to `astera@192.168.14.3`. Its stack step now builds translator
on the development machine, streams its image to the VM with the other images,
and starts it using `up --no-build --no-deps translator` before recreating backends.
It never builds on the VM and never waits for model health to boot Minecraft.
Model download/loading may take several minutes on the first start. No new volumes
or postgres/redis changes are introduced, so this patch does not require manual deploy.

**Manager rollout:** the active watcher runs a copied script outside Git. From a
checkout containing this change, run `make dev-autodeploy-up` before pushing the
integration commit so the supervisor uses the updated watcher; then push normally.
If already pushed, refresh the watcher and inspect `last-sha`/`attempted-sha` first:
a SHA already marked deployed needs the normal locked manual stack step (local
build + image save/load + remote up without build), `FORCE=1 make dev-deploy`, then
`mark-deployed <sha>`. Do not relabel an undeployed SHA.

After push, verify `deploy.log` reports the integration SHA, translator `/health`
is ready, its model profile/threads/memory cap are correct, both backend environments
contain the URL, and `ChatLiveTest` passes in the manager's integration window.
Then check two players with personal locales en/ja and Japanize off for English
slang. A successful CPU bench alone does not establish live Paper behavior or
RTX 3060 throughput. This worker performs no deployment.

[2026-10-29 CPU results and before/after](benchmark-2026-10-29.md) record both
profiles, unscored mistranslations and the remaining live/GPU checks.

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
and a local HTTP server through the real adapter (both profiles and LRU hits).
It also proves dictionary bypass, Unicode/token boundaries, ko/es output and
protection of names/URLs before token replacement.
`live.ChatLiveTest` adds an English-viewer/Japanese-sender probe; it skips with a
reason when inference `/health` is unreachable. The live runner defaults to
`http://translator:8080`; for a remote URL, its harness must pass
`ASTERA_TRANSLATE_URL` into the runner too. A reachable translator with an unset
URL on the backends fails the probe (it is not silently treated as success).
The manager runs live tests; this track does not touch running dev backends.

Prompt references: [llama.cpp Gemma template](https://github.com/ggml-org/llama.cpp/blob/master/models/templates/google-translategemma-4b-it.jinja),
[HY-MT prompts](https://huggingface.co/tencent/HY-MT1.5-1.8B#prompts).
