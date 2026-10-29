---
status: Accepted
date: 2026-10-29
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0308: Chat translation through a separate local server

## Status

Accepted (founder direction, 2026-10-29; number reserved by manager).

## Context

Community chat needs to convey player intent across Japanese and English, with
Korean and Spanish configurable. Founder: 「マジで意図が伝わるくらいの翻訳精度でいい」
and 「翻訳サーバー別プロセスでのほうが美しいならそうするべき」.
Inference belongs outside the Minecraft JVM, on a separate process/container or
an NVIDIA host (RTX 3060 intended). ADR-0307 changes the old plugin AI prohibition;
this decision introduces no AI SDK or in-process model dependency.

The existing relay converts romaji on the sender, transports one CHAT frame, and
suppresses its own Redis echo. The existing Japanize toggle is a session map
cleared on quit, despite the task's description of it as persisted. Core's typed
PlayerSettings has no chat fields; this track cannot edit Core or Persistence.

## Decision

Use a separate `llama-server` with OpenAI-compatible `/v1/chat/completions`.
Default to TranslateGemma 4B Q4_K_M, with HY-MT1.5-1.8B and
LFM2-350M-ENJP-MT selectable by profile. Pin CPU/CUDA images by digest in Compose
and GGUF downloads by immutable repository revision in the startup script.
Download to a model volume on first start, atomically promote a successful
transfer, and reuse it thereafter. The optional translation Compose profile
never blocks backend boot. No equivalent inference sidecar exists in deploy/,
so this change adds no k8s manifest. Document the CUDA overlay for a separate GPU
host; no claim of measured 3060 throughput is made.

Chat owns an asynchronous `ITranslator` port returning
`CompletionStage<Result<String, TranslateError>>`, and a JDK HTTP adapter with
request/total timeouts, bounded response bodies and a 512-entry LRU. Model prompt
formats live only in the adapter: TranslateGemma's full translation prompt with
plain Gemma turn framing, HY-MT's non-Chinese user prompt, and LFM2's mandatory
English/Japanese system prompt. LFM2 rejects directions outside en↔ja.
`ASTERA_TRANSLATE_URL` unset/empty cleanly disables requests.

The sender runs Japanize first; inference sees the converted text without its
romaji echo. Detect kana/kanji as ja, hangul as ko, and a small Spanish
word/diacritic list as es; other Latin defaults to en. This is a cheap heuristic,
not a language classifier. Translate once per distinct configured target other
than source, in parallel, within one `translation.timeout_ms` budget (1500 by
default). Put `sourceLanguage` and `translations` in the frame. Receiving backends
never invoke translation. Failure/timeout logs a warning and delivers original
once; late responses cannot republish. Successful directions can remain present
when another direction returns a typed failure. A total deadline discards the
unfinished batch.

Viewer choice uses Core's persisted i18n locale, falling back to the same server
default. A matching translation and enabled viewer preference displays the
translation plus a small configured marker; hover is original plus source code.
Otherwise show original. Preserve rank, name cosmetics and format. Mask URLs
and names known on the sending backend; reject broken placeholder responses.
Strip decoration before inference (after masking URLs so query ampersands survive)
and strip model-generated decoration. Only an allowed sender's leading style
may decorate the literal translated output. Discord→game uses the same translation stage without player-specific Japanize;
game→Discord always formats the original frame message.

`/astera chat translate on|off` changes the viewer preference. Persist this and
the existing Japanize setting with atomic files per player/preference, off the
region thread, in `ASTERA_CHAT_PREFERENCES_DIR`. Compose shares one volume between
lobby/game, allowing restart/reconnect/switch persistence without writing another
track's Core settings/JSONB codec. Physical hosts need a shared directory for the
same behavior. A failed setting write returns an i18n error rather than success.
Moving chat preferences into Core's database-backed settings is owner work,
not a second decoder or migration shim in chat.

## Consequences

### Positive

- Inference cannot stall a region thread or prevent backend startup.
- Cross-server recipients consume the same translated frame; cost scales with
  configured target languages rather than viewers or backends.
- Language/model configuration and a 40-line benchmark permit measuring intent
  preservation and latency before committing to a GPU/model choice.

### Negative / Trade-offs

- Cold loading/CPU inference may miss 1500 ms; chat then remains untranslated.
- Script/word heuristics misclassify ambiguous slang and mixed languages.
  Japanize remains independently switchable for Latin-language senders.
- Only sender-known names are protected; unknown names need a future network
  directory or terminology service. Mask-damaged responses fall back safely.
- Shared-file preferences require shared storage for physical backend hosts.

### Mitigations

- Keep the original available with hover and the per-viewer off switch.
- Bound HTTP time/body/cache and skip absent endpoints without warnings per line.
- Add a two-backend integration scenario with fake translation, actual HTTP
  adapter/local server, timeout, late reply, durable toggles and Discord paths.
- Add an opt-in live English-viewer probe which explicitly skips when inference
  is unavailable. The manager runs real-server validation in its integration window.

## Alternatives Considered

- **In-process inference / SDK:** GPU/runtime dependencies and memory contend
  with Minecraft; contrary to the founder's separate-process direction.
- **Paid remote API:** unnecessary for the stated local/free requirement.
- **Per-recipient inference:** repeats requests and makes cross-backend behavior
  inconsistent; sender fan-out is bounded by at most four configured languages.
- **Add Core settings fields here:** violates file ownership. A shared chat-owned
  directory provides durable settings within this task's paths.

## References

- [[adr/0289]] cross-server chat; [[adr/0299]] decoration/Japanize.
- [[adr/0307]] parallel AI-policy change; [[adr/0283]] locale resolution.
- [Operator instructions and benchmark](../../docker/translator/README.md).
- [llama.cpp Docker images](https://github.com/ggml-org/llama.cpp/blob/master/docs/docker.md).
- [TranslateGemma template](https://github.com/ggml-org/llama.cpp/blob/master/models/templates/google-translategemma-4b-it.jinja).
- [HY-MT prompt definitions](https://huggingface.co/tencent/HY-MT1.5-1.8B#prompts).
- [LFM2 system prompts](https://huggingface.co/LiquidAI/LFM2-350M-ENJP-MT#-model-details).
