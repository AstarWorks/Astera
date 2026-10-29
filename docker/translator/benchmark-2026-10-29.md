# CPU translation benchmark — 2026-10-29

24 logical CPUs, eight inference threads per model, same pinned CPU image/model
revisions as Compose. Servers/helpers were isolated `chat-translate-2-*` containers;
the helper shared the inference network namespace. No dev stack was touched.
Baseline is the manager-supplied `*-t8.{bench,extra}.txt` corpus, not a claim that
the broken literal `gemma` template produced useful translations. Baseline and
new runs have different host load/corpora; latency comparisons are indicative.
All twelve short slang lines plus iron/lag sentence lines were also re-run with
the original prompts and no glossary (14 requests/profile). The before columns
prefer this fresh run. Gemma uses corrected turn framing in this comparison so
it measures semantic improvements independently of the literal-template bug.
No GPU hardware run was performed.

## Latency and checks

| Model | Baseline 40-line p50 / p95 | New 60-line pipeline p50 / p95 | New 44 model calls p50 / p95 | Keyword checks | Exit |
| --- | --- | --- | --- | --- | --- |
| hy-mt | 540.6 / 915.0 ms | 385.2 / 766.0 ms | 482.9 / 813.5 ms | 20/20 | 0 |
| translategemma | 1353.6 / 2071.4 ms | 1465.2 / 2012.4 ms | 1626.5 / 2103.6 ms | 20/20 | 0 |

Sixteen messages bypassed inference (including duplicates in the original corpus).
The other 44 requests were uncached. Keyword checks cover the twelve requested
short slang terms, iron/lag in sentences and ko/es directions. They do not score
every line or prove complete semantic correctness. Docker working-set memory after
runs was HY-MT 1.018 GiB and Gemma 2.168 GiB (not process RSS).

The dev-shaped HY-MT run (four threads, `--cpus=4 --memory=3g`) also exited 0:
60/60 requests, 20/20 scored cases, pipeline p50/p95 450.4/759.5 ms; 44 model calls
p50/p95 524.5/847.9 ms; max 920.6 ms. Docker working set was 1.062 GiB / 3 GiB.
This ran on the 24-core machine under caps, not on the live eight-core VM.

## Before / after

After columns use the shared glossary and configured prompt. Short exact slang
is deterministic in both profiles; sentence examples use real model inference.

| Input → target | HY-MT before | HY-MT after | Gemma before | Gemma after |
| --- | --- | --- | --- | --- |
| おつ → en | Thank you. | gg | Thank you. | gg |
| 草 → en | Grass | lol | Grass | lol |
| それな → en | That’s it. | same | That's right. | same |
| 鉄 → en | Iron | iron | Steel | iron |
| ラグい → en | Laggy | laggy | Smooth; effortless | laggy |
| ｗｗ → en | www | lol | (No text provided) | lol |
| gg → ja | gg | おつ | gg | おつ |
| gg wp → ja | gg wp | おつ、ナイスゲーム | 素晴らしい！ | おつ、ナイスゲーム |
| brb → ja | すぐに戻ってくるね。 | すぐ戻る | 少しで戻ります | すぐ戻る |
| ty → ja | テュイ | ありがとう | タイ | ありがとう |
| cya → ja | シア | またね | また後で | またね |
| nice clutch → ja | 素敵なクラッチバッグです。 | ナイス逆転 | 素敵なバッグ | ナイス逆転 |
| nice clutch! → ja | 素敵なクラッチバッグですね！ | ナイス逆転! | 素晴らしい一着のバッグ！ | ナイス逆転! |
| 誰か鉄持ってる？ → en | Does anyone have a iron with them? | Does anyone have an iron? | Does anyone have a wrench? | Does anyone have iron? |
| 今のラグい？ → en | Is it still laggy right now? | What’s the lag right now? | Is the current situation problematic? | Is the lag bad right now? |
| ラグい？自分だけ？ → en | Lag? Just for myself? | Laggy? Just me? | Is this just me, or is something feeling off? | Is my connection laggy? Is it just me? |
| 一旦下がろう → en | Once it drops down… | Let’s retreat for now. | Let's go down. | Let's fall back for now. |
| 誰か鉄持ってる？ → ko | 혹시 누군가는 철을 가지고 있나요? | 혹시 철을 가진 사람 있나요? | 혹시 철봉 가지고 있는 사람 있나요? | 혹시 철 모으는 사람 있어? |
| 誰か鉄持ってる？ → es | ¿Hay alguien que tenga hierro? | ¿Alguien tiene hierro? | ¿Alguien tiene una llave inglesa? | ¿Alguien tiene hierro? |

## Limits observed

`りょうかい！` remains wrong (HY-MT: “Thank you!”, Gemma: “Let's go!”).
Gemma translates `ty for the carry` as “運命をありがとう”; HY-MT preserves the
thanks/carry intent. Mixed sentences can still change subject/tense, e.g. HY-MT
renders `out of ammo brb` as a request to come back when ammo runs out.
This is why CPU/dev uses HY-MT and keyword PASS is not called full quality approval.
Gemma CPU exceeded 1500 ms in 28/60 lines; its GPU profile is configured, not
performance-verified. Live Paper rendering and real dev-host contention are pending.

## Template and integration evidence

`/apply-template` returned:

- HY-MT: `<｜hy_begin▁of▁sentence｜><｜hy_User｜>Translate hello<｜hy_Assistant｜>`
- Gemma: `<start_of_turn>user\nTranslate hello<end_of_turn>\n<start_of_turn>model\n`

Gradle exited 0 for the requested chat/game-sim compilation, architecture tests,
and only `ChatTranslationScenarioTest` (8 cases). Added scenario names:

- `shipped slang crosses backends without inference while whole tokens preserve names and URLs`
- `shipped glossary covers ty and configurable Korean and Spanish without a model request`

The existing HTTP scenario now validates both supported profiles, configured PvP
context/few-shot and cache behavior. Compose config validation confirmed remote
translator has no disabled profile, HY-MT/four threads/four CPUs/3 GiB and both
backend URLs. An extracted-function dry run confirmed local build/image shipping
and translator startup before lobby/game, using `--no-build --no-deps` remotely.

Raw local evidence: `build/translation-bench/`; logs in `/tmp/chat-translate-gradle.log`
and `/tmp/chat-translate-stack-dry.txt`. Manager rollout is in README; no push/deploy.
