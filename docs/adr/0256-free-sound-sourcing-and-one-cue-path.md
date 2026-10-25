---
status: Accepted
date: 2026-10-26
deciders: founder (no paid services, memory `feedback_manage_agents_cloud`) + Claude
supersedes: 0026
superseded_by: null
---

# ADR-0256: Free-only sound sourcing, and one cue path for every game sound

## Status

Accepted. Supersedes [[adr/0026-sound-generation-policy]] (Stable Audio 2.5 on fal.ai). Builds on
[[adr/0109-effect-orchestration-foundation]] (`IPlayerSoundGateway`, `content/sounds/`) and
[[adr/0110-camera-music-gateway-foundation]] (`IMusicGateway`).

## Context

1. ADR-0026 chose a paid generator (fal.ai, $0.20 per clip, `FAL_KEY`). The standing rule since is no paid
   services for assets (a founder decision recorded in memory; `limbus-sound` already works free-only). No file
   produced by the ADR-0026 route exists: `content/sounds/` held two YAML samples and no OGG.
2. The audit of 2026-10-26 found the game's milestones silent (countdown, death, respawn, win / lose, login bonus,
   quest, purchase, player level) or sounding through three unrelated paths: raw `org.bukkit.Sound` calls
   (`PaperNormaPresentation`, `PaperStarDropWorld`), hard-coded `SoundSpec` constants in listeners
   (`HitFeedbackListener`, `SkillReadyCueListener`, `EgoReadyCueListener`, `HudUpdateOnLeveledUpListener`), and YAML
   `play_sound` skill mechanics. Nothing honoured the `/astera volume` settings, which existed but mixed nothing.
3. `IMusicGateway` had only an in-memory implementation: no BGM was ever audible.

## Decision

### A. Sourcing: ffmpeg synthesis and CC0 only

Astera sounds and music come from exactly two sources:

- **Synthesis** with ffmpeg (`lavfi` generators, filters, mixing) or sox, written as a script under `tools/sound-gen/`
  so the file is reproducible from the repo.
- **CC0** recordings (freesound.org CC0 filter, other public-domain sources), with the source URL and licence kept in
  the same commit.

Not allowed: fal.ai / Stable Audio, ElevenLabs or any other paid or credit-metered generator; any model whose output
licence restricts redistribution in a resource pack; CC-BY / NC material. Vanilla Minecraft sound events remain a valid
placeholder (they are the client's own and are not redistributed). The ADR-0026 tool-selection research (licence table)
stays valid as the reason the open models are excluded. `content/sounds/README.md` format rules (OGG Vorbis, mono,
44.1 kHz for SFX) are unchanged. `.claude/skills/generate-sounds` still describes the fal.ai route and must be
rewritten to the two sources above (harness change, tracked separately).

### B. One path: cues

Game code never names a raw sound. It names a **cue id** (`SoundCues`, e.g. `astera:match/death`) and calls
`ISoundCuePlayer.play(player, cue)`:

```
listener  -> ISoundCuePlayer -> SoundDefinition (content/sounds/**/*.yaml: resource_key + layers)
          -> scaled by the listener's `soundVolume` setting (0 = mute)
          -> IPlayerSoundGateway (Paper: Player.playSound, per viewer)
```

- A cue is a YAML `SoundDefinition` with a primary sound and optional `layers` (extra sound key + volume + pitch that
  start together), which gives character from vanilla sounds by stacking and detuning them.
- Swapping vanilla for an Astera OGG later is a YAML edit (`resource_key: astera:...` plus a `sounds.json` event); no
  listener changes.
- Positional world sounds that everyone nearby must hear (skill `play_sound`, annihilation nexus) keep
  `IWorldGateway.playSound`; they already resolve ids through the same `ISoundRegistry`.

### C. Cues shipped

Countdown tick and go, death, respawn, kill, hit / crit / blocked, skill ready, E.G.O ready, star pickup and burst,
Norma level up / max / choice open / picked / defaulted and their bursts, match win / lose / draw, player level up,
login bonus, quest chain complete, shop purchase (26 files under `content/sounds/{match,combat,star,norma,ui}/`).

### D. BGM

`IPlayerBgm` (mood -> track -> `IMusicGateway`) is driven by events: lobby on server join and return to lobby, match on
`PlayerEnteredMatch`, high intensity when the match enters `SUDDEN_DEATH` (the last stretch), victory / defeat sting on
`MatchSummaryReady`. The Paper `PaperMusicGateway` plays a track's new `MusicSource.Vanilla(soundKey)` (a vanilla music
disc event, `source.kind: vanilla` in `content/music/*.yaml`) on the client's MUSIC bus following the player, and
restarts a looping track every `duration_ticks`. The player's `musicVolume` setting at 0 plays no BGM at all.

Client-protocol limits are stated, not faked: a playing sound cannot be faded, so crossfade / stop cut immediately;
duck / unduck take effect on the next loop restart.

## Consequences

- Positive: every milestone is audible and testable (`MilestoneSoundScenarioTest` asserts each cue and the volume
  opt-out through `FakePlayerSoundGateway`); sounds are swappable per cue; the volume setting finally works; BGM exists
  without shipping audio.
- The vanilla music discs are stand-ins (distinctly recognisable). Real BGM is a later content task under rule A.
- `IStarDropWorld.playPickup` is removed (the cue player owns the sound); `HitFeedbackListener` and the other listeners
  lose their `SoundSpec` constants.
- Two music tracks shared `mood: high_intensity`; `boss-theme` moved to `boss_reveal` so `high-intensity-drums` is the
  last-stretch track (the registry returns the first track of a mood by id).
- ADR number note: 0256 is the next free number in this branch; concurrent tracks may have reserved the same number.
