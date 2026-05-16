# RTM Divergence Map

How Astera relates to [`Y-RyuZU/RyuZUTechnicalMagic`](https://github.com/Y-RyuZU/RyuZUTechnicalMagic) (RTM),
its predecessor. Policy: **reference, don't follow upstream** ([[adr/0013-rtm-divergence-policy]]).

Three rules of thumb:

- **Keep**: mechanics, math, intent — carry over verbatim (renamed if needed)
- **Redesign**: architecture, error handling, DI, dispatch — rebuild on Astera's principles
- **Add**: anything RTM lacked that modern multi-tenant / OSS / GitOps needs

## Keep — copy from RTM

Mechanics and shapes that RTM got right.

| Concept | RTM file (path inside RTM repo) | Astera location |
|---|---|---|
| 31 easing functions | `modules/api/core/src/.../skill/effect/display/EasingFunction.kt` (enum only) | `plugin/domain/src/.../domain/animation/Easing.kt` (enum + math) |
| Loadout slot taxonomy (MAIN / SUB / MOVE / SPECIAL / ARMOR / ULTIMATE) | `modules/api/core/src/.../game/team/ConfiguredTeam.kt` and related | `plugin/domain/src/.../model/player/LoadoutSlot.kt` |
| Damage attribute / element list | `modules/api/core/src/.../skill/performance/Element.kt`, `Attribute.kt` | `plugin/domain/src/.../model/weapon/DamageProfile.kt` (`DamageAttribute` enum) |
| Weapon archetype split (gun / sword / wand) | gameplay design doc (`docs/Writerside/topics/`) + scattered impl | `plugin/domain/src/.../model/weapon/WeaponSpec.kt` (`WeaponArchetype` enum) |
| Rarity tiers | implicit in RTM YAML | `plugin/domain/src/.../model/weapon/WeaponSpec.kt` (`Rarity` enum) |
| Game phase lifecycle (WAITING / COUNTDOWN / ACTIVE / SUDDEN_DEATH / ENDED) | `modules/api/core/src/.../game/mode/...` scattered states | `plugin/domain/src/.../model/match/MatchPhase.kt` |
| Star generator concept (BedWars-style spawners) | `modules/api/core/src/.../game/generator/*Generator.kt` | *Phase 3* — `plugin/domain/src/.../model/generator/` (TBD) |
| Anomaly (random in-match events) | `modules/api/core/src/.../game/anomaly/*` | *Phase 3* — `plugin/domain/src/.../model/anomaly/` (TBD) |
| Provider pattern (vanilla fallback + Oraxen / MythicMobs branches) | `modules/minecraft/paper/src/.../adapter/*/Bukkit*Provider.kt` + `Oraxen*Provider.kt` | `plugin/adapter/providers/{oraxen,mythicmobs,weaponmechanics}/` (modules + ADR-0009 soft-dep policy) |
| Status effect kinds (STAR_GAIN / HEAL_AMP / GRAVITY / CT_RATE / SPEED / PARALYSIS / BURN) | scattered in RTM skill impl | *Phase 2 mid* — `plugin/domain/src/.../model/status/StatusKind.kt` (TBD) |
| YAML-driven content (config-over-code) | `modules/core/impl/src/.../configuration/module/*` | `plugin/application/src/.../config/WeaponYamlConfig.kt`, generalised in Phase 2 mid |
| Docker download-papermc pattern | `docker/download-papermc.sh` | `docker/Dockerfile` (multi-stage, simplified via `itzg/minecraft-server`) |
| Writerside docs scaffold | `docs/Writerside/` | `docs/*.md` (Markdown-first, Writerside-compatible) |

## Redesign — RTM's choices we replace

Where RTM made a choice that doesn't meet Astera's cleanliness / decoupling / testability bar.

| RTM choice | Why it's a problem | Astera replacement | Recorded in |
|---|---|---|---|
| Triple-stack DI (Dagger 2 + Koin + Spring Boot) | Slow startup, tangled init order, no single source of truth for bindings | **Koin single** ([[adr/0004-koin-single-di-not-dagger-spring]]) | ADR-0004 |
| MongoDB persistence (per-concept collections) | Schema sprawl; fragile to schema drift; AstarManagement uses Postgres | **PostgreSQL + Exposed + JSONB** | ADR-0005 |
| `paper-plugin.yml` declares every external plugin as required | Any single dep going stale stops the server from booting | **All external plugins soft-dep**, provider pattern with vanilla fallback | ADR-0009 |
| Bukkit imports inside `modules/core/impl/*` | Core layer is no longer Minecraft-free; testing requires Paper | **Strict 4-layer Hexagonal**; Konsist test fails the build on layer violation | ADR-0001, ADR-0002, [[dependency-rules]] |
| `org.reflections` scanned at runtime for event listeners | Reflection is opaque to readers and slow to start; no compile-time check | **Explicit listener registration + sealed event dispatcher** (Phase 2 mid) | (planned in §13.5) |
| 8 damage event classes (`EntityDamageEvent` / `EntitySkillDamageEvent` / `EntityDeathEvent` / `EntitySkillDeathEvent` × by-entity variants) | Same idea, 8 places to update; pattern-matching is verbose | **One `DamageEvent` data class + sealed `DamageSource` (ENVIRONMENT / WEAPON / SKILL / FALL / ...)**; attacker is an optional field | (planned in §13.5) |
| `ISkillParams` with reflection-resolved parameter types | No compile-time guarantee that a skill's params match its impl | **Typed generics: `interface Skill<P : SkillParams>`** | (planned in §13.5) |
| Element matrix hardcoded in Kotlin (`Element.kt` + switch) | Balance changes require code edit + recompile + deploy | **`content/balance/element-matrix.yaml`** + domain pure-function loader | (planned in §13.5) |
| `ConfigurationModule` polymorphism (one Module per concept) | Verbose; each loader re-implements the same dir-walk + parse flow | **Generic `ContentLoader<T : Loadable>` port** + sealed YAML DTO per concept | (planned in §13.5) |
| Null returns + thrown exceptions for expected outcomes | Caller can't tell "expected failure" from "bug"; no exhaustive `when` | **`Result<T, E>` with sealed `E`**; `kotlin.Result` is avoided because it forces `Throwable` | ADR-0011 + [[error-handling]] |
| Direct `CommandAPI` calls scattered in `core/impl` | Application logic knows about CommandAPI; can't swap to Brigadier-only or web | **inbound port `ICommandHandler` + sealed `CommandSpec`** (Phase 2 mid) | (planned in §13.5) |
| Boss bar / scoreboard accessed directly via Bukkit | Domain leaks; UI work can't be tested without Paper | **`IMcBossBar` / `IMcScoreboard` ports** in `adapter-minecraft-api` | (planned in §13.5) |
| GUI on top of InventoryFramework directly | Same UI cannot serve a web admin frontend in Phase 4 | **`IGuiAdapter` port + `GuiSpec` data**; IF impl in adapter, web impl when needed | (planned in §13.5) |
| Persistent player data fragmented (Vault / Level / Settings / Skin / Donate as separate entities) | Joins require N reads; cross-cutting updates fragile | **One `PlayerProfile` row + JSONB fields** (Postgres) | ADR-0005 |
| i18n: per-file YAML + raw `{key}` substitution | No plural / gender / number formatting; no compile-time key check | **`MessageKey` value class** + ICU MessageFormat (Phase 2 mid extension) | §11 (Result + MessageKey) |
| ~0 test coverage | Refactoring is terrifying | **`domain` ≥ 90% line cov, `application` ≥ 80%**; test-fixtures module provides reusable fakes | [[testing-strategy]] |
| JVM 17 + Paper 1.20.4 baseline | Already a year behind Minecraft 1.21+ | **JDK 25 + Paper 26.1.x** + monthly dep-bump discipline | ADR-0003, ADR-0012 |
| No ADRs / no incident archive | Every design choice has to be re-discovered from `git log` | **16 ADRs** + [[../incidents]] template | [[../README]] |
| Scheduler: tick-only callback API | Tick-rate assumptions baked into call sites; coroutines unavailable | **Coroutine-first `IScheduler`** (Duration param + `suspend awaitTicks` + `mainDispatcher`/`asyncDispatcher`) | [[adr/0011-phase-1-implementation-deviations]] + 計画書 §14.1 |
| External plugin dependencies (11 required in RTM) | Each upstream stagnation blocks Astera releases | **Vanilla + self-build first** (0 plugins required) — `IItemRegistry` / `IProjectileService` / `ICommandHandler` Astera-owned | [[adr/0015-external-plugin-interrogation]] |
| Hand-written JSON Schemas / VSCode hints / AI prompts | Three places drift, no SSoT | **Kotlin `@Serializable` DTO = SSoT**; schemas / hints / prompts are generated artifacts; YAML / TOML / JSON wire formats interchangeable | [[adr/0016-content-schema-ssot]] |
| Mongo-style "everything is a document" or pure-column SQL extremes | Either no schema or migration-heavy | **Postgres + JSONB hybrid**: real columns for queryability, JSONB for flexibility | [[adr/0014-jsonb-usage-policy]] |

## Add — modern needs RTM never addressed

Things RTM didn't have that Astera needs because of its OSS + GitOps + AI-delegation posture.

| Element | Why now | Phase | Status |
|---|---|---|---|
| `Result<T, E>` discipline across all use cases | Compile-time exhaustiveness on expected failures | Phase 1 polish | ✅ §11 done |
| `MessageKey` value class with format validation | Surface i18n typos at compile time, not at runtime | Phase 1 polish | ✅ §11 done |
| Konsist architecture tests in CI | Reflection-free, fails the build on layer violation | Phase 1 | ✅ 9 rules green |
| ADR archive (immutable decision history) | Astera will be touched by many humans + AIs across years | Phase 1 | ✅ 13 ADRs |
| `docs/incidents/` (Re-DIVERSE-style 5-Whys archive) | Real ops; learning persists across team turnover | Phase 1 | ✅ template; entries arrive with first incident |
| ArgoCD + Kustomize App-of-Apps GitOps | `git push` is the deploy | Phase 1 | ✅ manifests written; cluster sync awaiting user setup |
| Sealed-Secrets or SOPS for `private/secrets/` | Phase 4 production secrets without leaking plaintext | Phase 1.5+ | ❌ tooling choice deferred |
| KEDA-driven dynamic Game Pod | Match queue → spawn → idle → scale to 0 (Hypixel-style) | Phase 1.5 | ❌ static 1 Pod for now |
| Web admin UI (Nuxt 4, AstarSite/Fax pattern) | Phase 4 UGC marketplace + ops dashboard for non-Minecraft access | Phase 4 | ❌ |
| Discord bot intake for content (non-IT contributors) | Lower friction than git PRs for community weapon submission | Phase 4 | ❌ |
| Generative AI for textures / sounds / 3D / stages | "100% AI-developed" mission, ADR-0010 defers to Phase 6 | Phase 6 | ❌ deliberately deferred |
| AI PR review bot | Catch architectural drift before merge | Phase 4 | ❌ |
| `release-noter` agent (commits → release notes) | Faster cadence than manual notes | Phase 7 | ❌ |
| `incident-responder` agent (Grafana alert → hypothesis + runbook) | Reduce on-call cognitive load | Phase 7 | ❌ |
| Real-time LLM chat translation | RTM mentioned `GPT4o-mini` translation; Astera realizes it | Phase 6 | ❌ |
| Vector-search subscription product (RTM concept) | Phase 4+ monetisation | Phase 4+ | ❌ |
| Battle Pass + seasonal event automation | RTM listed; Astera automates rather than hand-runs | Phase 7 | ❌ |

## What this means for a contributor (human or AI)

1. **Reading an RTM file**: ask "is this mechanic, shape, or architecture?"
   - Mechanic / math / data shape → safe to mirror in Astera
   - Architecture / DI / persistence / error-handling → check the "Redesign" table above first

2. **Writing new Astera code**: never recreate one of the redesigned RTM patterns by accident. If a familiar RTM pattern doesn't appear here under "Keep", it probably falls under "Redesign" — open this doc or [[adr/0013]] to confirm.

3. **Encountering a gap** ("RTM had this; we don't"): consult §13 of the plan,
   then decide which Phase carries it (most things land Phase 2 mid or later). If
   it's not in §13 or the roadmap, add a row to one of the tables here in the
   same PR.
