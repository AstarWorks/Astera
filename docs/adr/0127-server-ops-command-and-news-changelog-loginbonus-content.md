---
status: Accepted
date: 2026-05-30
deciders: orchestrator (Wave J 5server-content-ops)
supersedes: null
superseded_by: null
---

# ADR-0127: Server ops restart command + News/Changelog/LoginBonus content scaffolding

## Status

Accepted

## Context

Round 12 closed Wave H (content baseline expansion + listener wire-up). A
follow-up server-polish audit surfaced five private-beta gaps:

1. **No graceful restart command.** Admins could `stop` the server but had
   no in-process way to *schedule* a restart with countdown notifications.
   Wave 5t (ADR-0086) gave us `IScheduledAnnouncementSystem` and Wave 5w
   (ADR-0089) gave us `IServerBootstrapper`, but neither was wired into an
   operator-facing command — the substrate was complete, the verb was not.
2. **No `content/news/` directory.** Every other category had a documented
   per-file YAML pattern (recipes, quests, advancements, mobs, …); there
   was no equivalent for editorial / announcement posts that an admin can
   schedule out-of-band and a player can read at leisure.
3. **No `content/changelog/` directory.** PoC release notes lived only in
   markdown under `docs/`; in-game players had no way to find out "what
   changed" between two sessions without leaving the server.
4. **No `content/login-bonus/` directory.** Daily-login rotations are a
   Phase 2 deliverable, but the *table* shape (day → reward) could ship
   now so authors can edit it from day one — the claim flow is the only
   piece that genuinely needs the Phase 2 Postgres migration.
5. **No `/astera-news` or `/astera-patch` command.** Even with content in
   place, players had no surface to read it from.

CLAUDE.md §Pre-release posture frames this exactly as the right kind of
wave to land now: pure additions on top of existing ports, no schema
break, no Konsist rule change, no DB migration. The fix is cheap *and*
the boundary stays clean enough to unblock Wave 6's Paper sweep.

## Decision

Land all five gaps in one wave, all in `:plugin:core`:

- **`/astera-restart <minutes> [reason]`** — `application/command/admin/`
  AsteraRestartCommandSpec. Orchestrates Wave 5t IScheduledAnnouncementSystem
  (1 chat at `now` + 4 Title waypoints at -30s / -10s / -3s / -1s before
  the restart instant) and Wave 5w IServerBootstrapper.shutdown(Restart)
  scheduled at N-1s via IScheduler.runLater. Bound by `astera.admin.restart`.
- **News domain + port + content** —
  `core.domain.news.{NewsEntry, NewsId, NewsSeverity}` +
  `core.application.port.outbound.news.INewsRegistry` +
  `core.application.contents.NewsContents` (extends `YamlDynamicContents`)
  + `core.application.config.news.NewsYamlConfig`. Two sample YAMLs:
  `welcome-to-phase-1.yaml` (Info), `wave-6-incoming.yaml` (Important).
- **`/astera-news [limit]`** — `AsteraNewsCommandSpec` in
  `application/command/`. Renders top-N non-expired entries (newest first)
  to chat via `IPlayerGateway.sendMessage` + `IMessageRenderer.render`.
  Permission-free; defaults limit=5, max 20. Read-tracking is intentionally
  deferred to Phase 2 (re-reads are cheap, persistence cost is not).
- **Changelog domain + port + content** —
  `core.domain.changelog.{ChangelogEntry, ChangelogVersion}` +
  `IChangelogRegistry` + `ChangelogContents` + `ChangelogYamlConfig`.
  `ChangelogVersion` is a value class with strict `MAJOR.MINOR.PATCH`
  digit-only validation + a `Comparable` impl so future "show all newer
  than X" queries land trivially. Sample YAML: `0.1.0.yaml`.
- **`/astera-patch [version]`** — `AsteraPatchCommandSpec`. No argument →
  latest entry + "use /astera-patch <version> for details" hint; with
  argument → that entry + its full body. Permission-free.
- **Login bonus skeleton** —
  `core.domain.loginbonus.{LoginStreakRecord, LoginBonusReward,
  LoginBonusSpec, LoginBonusError}` + 3 ports
  (`ILoginBonusRegistry`, `ILoginStreakRepository`, `ILoginBonusService`) +
  3 services (`LoginBonusContents`, `InMemoryLoginStreakRepository`,
  `DefaultLoginBonusService`). `claimDaily` returns
  `Result.Failure(LoginBonusError.NotImplemented)` in Phase 1; Phase 2
  fills the body. `LoginBonusContents` is custom (does NOT extend
  `YamlDynamicContents`) because each file describes a *list* of day
  entries; the substrate's `toEntry: C → Pair<K, V>?` can only return one
  pair per file. The Contents class reuses `ContentLoader` directly and
  expands each file's `days:` list into N store entries. Sample:
  `default.yaml` — 7-day STAR-currency rotation with a welcome-pack item
  on day 7.
- **i18n** — new keys under `astera.{restart,news,patch,changelog,
  loginbonus}.*` in both `content/languages/en.yaml` and `ja.yaml`. Parity
  test maintained.
- **No new Konsist rule** — every constraint we want is already covered
  by existing rules (domain stays pure, application returns
  `Result<T, E>`, en↔ja parity).

Plugin wiring (`AsteraCorePlugin.onEnable`) registers 5 new services
(INewsRegistry, IChangelogRegistry, ILoginBonusRegistry, ILoginStreakRepository,
ILoginBonusService), binds the 3 new YAML directories into the cross-plugin
`IReloadableContentsRegistry`, and registers 3 new commands. The Wave J
block sits *after* Wave G so it can see every Wave 5–G local val
(announcementSystem, bootstrapper, messages, playerGateway, scheduler,
permissionService) without forward references.

## Consequences

### Positive

- Operators get a real restart workflow without leaving the game ("1-hour
  warning" with auto-cued countdown). The 4 Title waypoints + chat preface
  match the established pattern from major MMOs / matchmaking lobbies.
- Authors can ship news + changelog + login-bonus *content* on day one
  without waiting for Phase 2 claim logic — the same loop used for
  recipes / quests / advancements applies uniformly.
- Players have an in-game surface to discover what changed (`/astera-patch`)
  and what's announced (`/astera-news`) — the two most common "why am I
  surprised?" Discord questions become self-service.
- LoginStreakRepository ships as a real port now, so any Phase 1 surface
  that wants to *show* a streak (lobby GUI, profile screen) can read it
  immediately; Phase 2 just adds the *grant* side without touching call
  sites.
- Pure additive: no field rename, no schema rewrite, no `@Deprecated` shim,
  no DB migration, no Konsist rule change. Konsist count stays at 53.

### Negative / Trade-offs

- `LoginBonusContents` doesn't share `YamlDynamicContents`'s base class,
  so any future refactor of the substrate has to remember to update both.
  Mitigated by living right next to it in the same `application/contents/`
  package — visible at a glance.
- `/astera-restart`'s reason argument is currently informational only (logged
  as a `MessageKey` reference for future GUI surfaces). The four Title
  waypoints don't render the reason. Acceptable for Phase 1 — the chat
  preface gives the operator's intent, and the Titles are last-second
  urgency cues where extra text would hurt legibility.
- `ChangelogVersion` rejects pre-release / build-metadata suffixes (`-rc1`,
  `+build42`). Phase 1 PoC cuts are stable-only, so the constraint is
  cheap; if we later need pre-release tags a follow-up ADR can broaden
  the grammar.
- `INewsRegistry.latest()` re-sorts the full collection on every call.
  Acceptable at Phase 1 cardinality (~10 entries); a future wave can
  pre-sort at register time if profiling shows it matters.

### Mitigations

- Each new content file carries a `# Wave J 5server-content-ops (ADR-0127)`
  header so the lineage is greppable.
- The 3-test `AsteraRestartCommandSpecTest` covers the happy path (5-min
  countdown schedules 5 announcements), the shutdown trigger (advances
  FakeScheduler to N-1s and asserts every `ShutdownPhase` fired), and
  the rejection path (out-of-range minutes).
- Phase 2's planned migration for `LoginStreakRecord` will be `V44+`
  (Flyway up-only per CLAUDE.md). Phase 1's `InMemoryLoginStreakRepository`
  is data-loss-on-restart by design — `DefaultLoginBonusService.claimDaily`
  doesn't touch it yet so no surprises.

## Alternatives Considered

- **Make `LoginBonusContents` extend `YamlDynamicContents` with one file
  per day** (e.g. `1.yaml`, `2.yaml`, …). Rejected: authors editing a
  7-day rotation should see all rewards in one view; spreading the table
  across 7 files turns "rebalance days 3-5" into a multi-file rename
  dance. Custom Contents is ~30 LoC; the readability win is worth it.
- **Pre-allocate `ChangelogVersion` as `String` (no value class).**
  Rejected: the value class catches typos at parse time and gives
  `Comparable<ChangelogVersion>` for free, which Phase 2 will use to
  filter "newer than my last login". The cost (one file) is dwarfed by
  the type safety.
- **Have `/astera-restart` write its scheduling state to disk so a crash
  preserves the restart timer.** Rejected: a crash *is* a shutdown; the
  operator can re-issue the command on the next boot. Persisting an
  in-progress restart timer is operational complexity without a clear
  user story.
- **Move News + Changelog into a separate `:plugin:announce` module.**
  Rejected: ADR-0022 sibling-isolation deliberately keeps Core lean; a
  new plugin per content category would multiply CommandSpec / Contents
  bootstrap boilerplate. Per the Items / Tutorials / Advancements
  precedent, content registries that don't need feature-plugin
  dependencies live in Core.
- **Use `IBroadcaster.publish` to render news as `BroadcastIssued` events
  instead of direct `IPlayerGateway.sendMessage`.** Rejected: the existing
  `IScheduledAnnouncementSystem` already targets the *broadcast* surface
  (server-wide); `/astera-news` is *per-player on-demand*, semantically
  the opposite. Reusing the gateway pattern from Wave 5lobby-content
  intent handlers keeps the boundary clean.

## References

- 関連 ADR:
  [[adr/0086-scheduled-announcement-system]] (IScheduledAnnouncementSystem
  reuse),
  [[adr/0089-server-lifecycle-bootstrapper]] (IServerBootstrapper /
  ShutdownReason reuse),
  [[adr/0060-yaml-dynamic-contents-substrate]] (YamlDynamicContents +
  ReloadableContents reuse),
  [[adr/0065-cross-plugin-reload-dispatcher]] (IReloadableContentsRegistry),
  [[adr/0022-multi-plugin-paper-layout]] (Core-only land per
  sibling-isolation),
  [[adr/0117-pvp-server-completeness-orthogonal-axes]] (flat
  `/astera-<verb>` admin command pattern),
  [[adr/0122-admin-operations-completeness]] (companion admin command
  surface).
- 関連 docs: `docs/IMPLEMENTATION-ROADMAP.md` §Round 13,
  `docs/RTM-PARITY-TRACKER.md` (private-beta readiness rows).
- 関連 content: `content/news/*.yaml`, `content/changelog/*.yaml`,
  `content/login-bonus/*.yaml`.
