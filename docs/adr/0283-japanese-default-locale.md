---
status: Accepted
date: 2026-10-27
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0283: Japanese default locale; explicit choice beats server default, client locale ignored

## Status

Accepted. Amends the locale-resolution rule of [ADR-0124](0124-player-prefs-social-functional.md) and the
"default-locale baseline" assumption of [ADR-0171](0171-wave-6c-stub-packetevents-b-per-viewer-scoreboard.md) and
[ADR-0172](0172-wave-6c-stub-packetevents-c-per-viewer-tablist.md) (the baseline locale is now the configured server default, `ja` unless overridden).

## Context

Astera is a Japanese server, but everything a player saw was English unless the client itself ran in Japanese:
`SimpleMessageRenderer` defaulted to `en`, the resolver fell back to the client's Minecraft language, and
`PlayerSettings.locale` defaulted to `EN` so any settings save (volume, toggles) pinned the player to English.
Player-less renders (lobby signs, lane pads, NPC nameplates, the shared scoreboard / tab-list baseline) always used
the default, and mob nameplates hardcoded `LocaleCode.EN`. `ja.yaml` already has every key (1912 keys, en/ja parity).

## Decision

1. The server default locale is `ja`, overridable by the env var `ASTERA_DEFAULT_LOCALE` (`en` | `ja`; anything else
   logs a warning and uses `ja`). It is read once in `ServerEnvironment` (platform layer), carried by
   `CorePlatformPorts.defaultLocale`, and passed explicitly to `SimpleMessageRenderer` (no default parameter),
   `PaperMobBodyGateway` and the Settings locale handler.
2. Resolution order: the player's explicitly chosen locale (`/astera lang`, Settings tile) > the server default.
   The client's Minecraft locale is no longer consulted.
3. `PlayerSettings.locale` is `LocaleCode?`; `null` (the default) follows the server default, so saving other settings
   no longer pins a language. The JSONB field is absent when null; an unsupported stored value decodes as null.
   The Settings tile cycles starting from the effective locale. Migration `V71` drops the stored `locale` key from
   existing rows (pre-release; rows pinned to `en` by unrelated saves would otherwise stay English).
4. Mob nameplates use the server default locale (a vanilla custom name is world-shared, so it has no per-viewer variant).
5. The e2e server sets `ASTERA_DEFAULT_LOCALE=en` (`docker/docker-compose.e2e.yml`) because bots and ~40 tests assert
   English; the headless game-sim pins `en` for the same reason. Dev and prod compose / k8s manifests do not set it.

## Consequences

### Positive
- Every player-visible surface is Japanese by default, including player-less baselines.
- A single switch (`ASTERA_DEFAULT_LOCALE`) flips the whole server; no per-test rewrite.

### Negative / Trade-offs
- English-speaking players must run `/astera lang en`; their client language no longer helps.
- Mob nameplates cannot follow the per-player choice.
- Stored explicit locale choices made before V71 are reset.

## Alternatives Considered

- **Keep honouring the client locale**: Japanese players on en_us clients stay English.
- **Seed the settings row with `ja`**: still pins a language and needs a default constant in three places.

## References

- [[adr/0124-player-prefs-social-functional]]
- [[adr/0171-wave-6c-stub-packetevents-b-per-viewer-scoreboard]]
- [[adr/0172-wave-6c-stub-packetevents-c-per-viewer-tablist]]
