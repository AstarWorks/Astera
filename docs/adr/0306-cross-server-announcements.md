# ADR-0306: Cross-server moment announcements

- Status: Accepted
- Date: 2026-10-29
- Supersedes: ADR-0289's TITLE relay only

## Context

Rare drops and future server boosts should show the same title, subtitle, sound,
chat, action bar and particles to players on lobby and game. FX-05 already owns
these choices in `content/presentation/events.yaml`; a second presentation
protocol would duplicate that table.

## Decision

Core exposes `IAnnouncer.announce(moment, AnnounceTarget, args)` through the
ServiceRegistry, returning `Result<Unit, AnnounceError>`. `All` and `Server(name)`
select backends. The existing RedisProxyMessenger transports `ProxyChannel.ANNOUNCE`:
its ProxyMessage envelope contains `fromServer`, and its JSON payload contains
`moment`, `args`, and nullable `target` (null means all). Each targeted backend
plays the table moment through IPresentation for each online player as subject,
on that player's entity scheduler; particles anchor at each player and retain
visibility settings. Local delivery is scheduled immediately before publishing.
The sender ignores its own Redis echo. A remote-only target does not play locally.

Unknown moments fail before publish with a typed error and ERROR log. Receivers
log unknown moments and malformed frames at ERROR. Transport failure returns a
typed error and one warning; any targeted local delivery is retained.
A standalone host using the existing in-memory messenger logs a startup warning
that announcements are local-only. Local-only
Server(self) needs no transport. Shutdown unsubscribes and prevents queued playback.

Moment rows gain an optional `chat` i18n key, rendered per viewer with args and
legacy ampersand colours only. The seeded announcement title/subtitle keys also
use legacy rendering, so MiniMessage tags in args remain literal. Existing
presentation rows retain their existing renderer. YAML rejects unknown fields,
invalid message keys, and undefined particle references. The schema reflects the
same fields. Seed `rare-drop` and `server-boost` reuse existing sounds and presets.

Remove `/astera chat title`, `TitleFrame`, and `ProxyChannel.TITLE`. The single
admin entry point is `/astera announce <moment> <all|server> [key=value ...]`, with
operator-default permission `astera.announce`. Completion lists table moments and
live peer server names plus the local server. Args are whitespace-delimited,
nonempty key=value tokens with unique keys; values may contain equals signs.
Free-text title frames are unnecessary because content owns presentation.

ADR-0289 also says chat permission snapshots run on entity schedulers. Since
commit `697bb6b15`, those snapshots run asynchronously; entity schedulers are used
for player delivery. This ADR records that correction without editing the Accepted ADR.

## Consequences

Feature plugins depend only on Core to announce existing moments. No booster,
gacha, or crate feature is introduced. The economy plugin has no gacha/crate caller. The existing PvE roll -> item
entity -> successful `LootPickedUp` path announces LEGENDARY acquisitions, using
the stable item id in the args; failed grants do not announce. Redis delivery remains best effort with no persistence or retry.
Two-server game-sim coverage checks local/remote delivery, target filtering, echo
suppression, unknown moments and transport failure. The live chat test checks a
game bot's chat/title after a lobby announcement; the manager runs it in the batched window.
