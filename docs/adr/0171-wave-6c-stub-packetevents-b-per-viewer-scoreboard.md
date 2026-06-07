---
status: Accepted
date: 2026-06-07
deciders: Astera core
supersedes: null
superseded_by: null
---

# ADR-0171: Wave 6c-stub-packetevents (B) — per-viewer scoreboard locale via team-packet fan-out

## Status

Accepted. Second-stage PacketEvents consumer on the ADR-0170 foundation:
both Paper scoreboard adapters ([`PaperScoreboard`][line],
[`PaperTeamScoreboard`][team]) move to the opaque-entry/team-prefix sidebar
model and, when PacketEvents is installed, fan a per-viewer
`ClientboundSetPlayerTeamPacket` UPDATE out to each viewer so every player
reads the sidebar in **their own locale**.

## Context

ADR-0170 §Context named per-viewer scoreboard team-name rendering as one of
the surfaces Paper-native APIs cannot cover: vanilla Scoreboard packets are
server-wide per scoreboard, so two viewers attached to the same board always
see identical bytes. Pre-wave, both Paper adapters rendered all text in the
*first* viewer's locale (`viewerForRendering() = viewers.firstOrNull()`), and
the production wiring didn't even pass an `IMessageRenderer` into
`PaperScoreboardManager` — boards fell back to raw `MessageKey.value`.

Two further latent defects surfaced during design:

- **Team-name 16-char wire limit.** `PaperTeamScoreboard.addTeam` called
  `registerNewTeam(teamId)` with arbitrary caller text; CraftBukkit throws
  past 16 chars, and the `ClientboundSetPlayerTeamPacket` name field carries
  the same bound.
- **Text-as-entry-name modeling.** `PaperScoreboard` stored each line's
  rendered text *as* the vanilla entry name (40-char truncation, ghost-row
  reset dance on every text change, impossible to override per viewer —
  entry names are global).

This wave fixes all three with one model change. The scope decision (user
ratified): **both** boards in one wave, full handoff scope, rather than
splitting the line-board rewrite into a follow-up.

## Decision

### §A — Opaque entry + team-prefix sidebar model (both boards)

Every visible sidebar row becomes:

- an **opaque entry token** (legacy `§`-code sequence, renders as zero
  visible characters) holding the objective score that drives vanilla's
  vertical sort — stable per row; text changes never touch it. Line board:
  one code per slot 0..14 (`"§<code>§r"`). Team board: two-code permutation
  from a monotonic ordinal (256 per board lifetime, never reused after
  `removeTeam`).
- a **per-row Bukkit `Team`** whose **prefix carries the visible text** as
  an Adventure `Component`. The shared Bukkit scoreboard broadcasts the
  **default-locale baseline** (`render(null, …)`) — this is also the
  complete vanilla fallback when PacketEvents is absent.

Side effects: the 40-char line truncation disappears (component prefixes are
unbounded; the title keeps `TITLE_LIMIT`), and the first-viewer-locale hack
is deleted in favor of the deterministic default locale.

### §B — `ScoreboardTeamNames`: derived ≤16-char wire names

Bukkit team names are never raw caller text. `ScoreboardTeamNames`
(`adapter/paper/hud/`) derives FNV-1a-32 → base62 names ≤10 chars
(`"at" + hash(boardId + teamId)` / `"al" + hash(boardId) + slot`). The
registration path and the per-viewer UPDATE path must reuse the stored
name **byte-identically** — a mismatched name targets a team the client
never created and the packet is silently dropped. This also fixes the
latent >16-char `registerNewTeam` crash.

### §C — `ITeamPacketSender` isolation seam + PacketEvents impl

`ITeamPacketSender` (`adapter/paper/packetevents/`) is the per-viewer
override hook: `sendTeamUpdate(player: Player, teamName: String, text:
Component)`. Its signature uses only Bukkit/Adventure types, so the
scoreboard classes stay loadable on vanilla Paper.

`PacketEventsTeamPacketSender` implements it and is — alongside
`PacketEventsBootstrap` / `PaperPacketInterceptor` — the only code allowed
to import `com.github.retrooper.packetevents.*`. It builds
`WrapperPlayServerTeams(teamName, TeamMode.UPDATE, ScoreBoardTeamInfo(text,
text, Component.empty(), ALWAYS, ALWAYS, NamedTextColor.WHITE,
OptionData.NONE))` via the non-deprecated nullable-info constructor (the
`Optional` overloads are `@Deprecated` in PE 2.12.2) and sends through
`PacketEvents.getAPI().playerManager.getUser(player).sendPacket(packet)` —
thread-safe (Netty `writeAndFlush`), no main-thread marshalling. UPDATE
mode serializes no entity list (only CREATE / ADD_ENTITIES /
REMOVE_ENTITIES do), so membership is untouched.

`AsteraCorePlugin` constructs the impl **strictly inside the
`if (packetEventsInstalled)` gate** (same JLS §5.3.4 lazy-resolution pattern
as `PaperPacketInterceptor`); on vanilla the boards receive `null` and skip
fan-out. Construction is side-effect-free — `PacketEvents.getAPI()` is only
touched at send time, after the `init()` phase.

### §D — Fan-out triggers (and non-triggers)

Per-viewer UPDATEs are sent exactly on:

- **`addTeam` / `addLine`** — that row, to all current online viewers;
- **`addViewer`** — every registered row, to the new viewer only (after the
  shared-board attach pushes the baseline).

Explicitly **no** fan-out on: `setScore` (objective packet, not team),
`removeTeam` / `removeLine` (Bukkit `unregister()` broadcasts REMOVE),
`setTitle` (objective display name — different packet, out of scope per
ADR-0170's "team-name rendering" wording), `removeViewer` (reassignment to
the main scoreboard wipes the board's teams client-side). Fan-out is also
skipped when no `IMessageRenderer` is wired (every viewer's text would
equal the baseline).

### §E — Production wiring

`PaperScoreboardManager` gains `(server, messageRenderer, teamPacketSender)`
and threads both into every created board. `AsteraCorePlugin` now passes the
already-constructed `messages` renderer (previously omitted — boards showed
raw key strings) plus the gated sender.

## Consequences

### Positive

- **Per-viewer locale end-to-end.** With PacketEvents installed, viewers
  with different client languages read the same board in their own locale —
  the first user-facing per-viewer rendering consumer on the ADR-0170
  foundation.
- **Latent >16-char team-name crash fixed**, independent of PE presence.
- **Line text no longer truncated at 40 chars**; text updates are a single
  team-prefix mutation (no entry reset / ghost-row dance).
- **Vanilla fallback intact** (CLAUDE.md §4): no PE → default-locale
  baseline via the shared scoreboard; `null` sender; zero PE symbols
  resolved.
- **i18n actually wired**: `PaperScoreboardManager` finally receives
  `IMessageRenderer`.

### Neutral / costs

- **~430 LOC** across 2 new files (`ITeamPacketSender`,
  `PacketEventsTeamPacketSender`), 1 new util (`ScoreboardTeamNames`),
  2 rewrites (`PaperScoreboard`, `PaperTeamScoreboard`), manager + platform
  wiring.
- **No new tests** (same posture as ADR-0170: PE paths need a real Paper +
  PE runtime; the in-memory fakes keep covering the `IScoreboard` /
  `ITeamScoreboard` contracts). Manual smoke in §Verification.
- **Konsist no rule change** — everything lives in `adapter.paper.*`.

### Negative / trade-offs

- **Baseline locale changed first-viewer → default locale.** Pre-wave,
  PE-less multi-viewer boards rendered in viewer #1's locale; now they
  render in the server default. Deterministic and arguably more correct,
  but a visible behavior change for non-PE servers.
- **Locale change requires relog** (re-`show` → `addViewer` re-fans). A
  `PlayerLocaleChangeEvent` listener is a candidate follow-up.
- **Per-viewer title not included** — the objective display name travels in
  a different packet (`ClientboundSetObjectivePacket`); deferred.
- **256-ordinal ceiling** on team-board entry tokens per board lifetime
  (ordinals never reused, by design); exceeding it throws with a clear
  message. Phase 1 PoC boards hold ≤15 visible rows, so headroom is ample.

## Verification

- `./gradlew :plugin:core:compileKotlin` BUILD SUCCESSFUL — PE 2.12.2
  wrapper constructors resolve as specified (nullable-info overload,
  Adventure `Component`, `NamedTextColor`).
- `./gradlew check` BUILD SUCCESSFUL — full suite + 58 Konsist rules green.
- Manual smoke (post-merge):
  1. Vanilla Paper, no PE → boards render default locale; no
     `NoClassDefFoundError`; sender is null.
  2. + PacketEvents 2.12.2 → two players, `en` and `ja` clients, same board
     (one line-style, one team-style): each reads their own language.
  3. Player B `show`-n after the board is populated → B immediately sees
     their locale (addViewer fan-out), not A's.
  4. `teamId` / board id longer than 16 chars no longer throws.

## Alternatives Considered

- **Per-line `ClientboundSetScorePacket` display-name component (1.20.3+)**
  instead of team prefixes. Viable on modern protocol, but diverges from
  the packet ADR-0170 §Context committed to, splits the mechanism across
  two packet types (team board still needs team packets), and has no
  Bukkit-API baseline equivalent for the vanilla fallback. Rejected.
- **Team-style board only in this wave** (split the line-board rewrite to a
  follow-up). Lower risk per commit; user chose full handoff scope so both
  boards land on one model in one review.
- **Per-viewer title in the same wave.** Different packet, marginal value
  (titles are often locale-neutral brand strings). Deferred.
- **`PlayerLocaleChangeEvent` listener for live locale switches.** Needs a
  plugin reference in the manager + listener lifecycle; relog covers the
  Phase 1 PoC. Deferred.
- **Raw `teamId` as Bukkit team name with caller-side length contract.**
  Pushes a wire-protocol invariant onto every caller; a derived hash is
  strictly safer. Rejected.

## References

- [[adr/0170-wave-6c-stub-packetevents-foundation]] — the PacketEvents
  adoption + isolation pattern this wave consumes; its §Context names this
  scoreboard surface as deferred work.
- [[adr/0095-packet-system-and-per-viewer-visibility]] — the original
  per-viewer rendering design intent.
- [[adr/0031]] — Wave 5b HUD ports (`IScoreboard` / `ITeamScoreboard` /
  `IScoreboardManager` contracts, unchanged by this wave).
- [[adr/0168-wave-6g-visibility-paper-native-no-packetevents]] — the
  carve-out that left scoreboard locale as a PacketEvents-only surface.
- [PacketEvents 2.12.2][packetevents] — `WrapperPlayServerTeams` /
  `ScoreBoardTeamInfo` / `User.sendPacket` (signatures verified against the
  published 2.12.2 artifacts).

[line]: ../../plugin/core/src/main/kotlin/com/astarworks/astera/core/adapter/paper/hud/PaperScoreboard.kt
[team]: ../../plugin/core/src/main/kotlin/com/astarworks/astera/core/adapter/paper/hud/PaperTeamScoreboard.kt
[packetevents]: https://github.com/retrooper/packetevents/releases
