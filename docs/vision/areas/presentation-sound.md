# Area: presentation and sound

Owner track `wp/presentation` (skill-level VFX/sound belongs to `skills-vfx`). Items: Board prefix `FX-`.

## 構想 (source)
- Only stars-and-norma.md specifies presentation: star pickup sound and "+N", Norma level-up title + sound + particles, choice chime, 10 s default notice.
- ADR-0206: kill feed, hit-marker sounds (normal / crit / block / finisher), damage numbers, nameplates.
- ADR-0109/0110: effect orchestration and BGM moods (lobby / match / boss / victory) as foundations.
- ADR-0026 planned 20-40 generated SFX via a paid service. **Superseded in intent by the founder decision 2026-10-26: sound is sourced free only (ffmpeg synthesis or CC0).** A supersede ADR is still owed.
- `content/sounds/README.md`: weapons / skills / status / match / ui OGG, mono 44.1 kHz.

## 現状
- Cross-server moments (ADR-0306): Core `IAnnouncer` relays the same presentation table over Redis ANNOUNCE to all or one backend, playing per online player with localized legacy-colour chat. `/astera announce` (operator default) replaces `/astera chat title`; `rare-drop` and `server-boost` reuse existing cues/presets. Successful LEGENDARY loot pickup (the existing PvE roll/item-entity path) calls `rare-drop`; no gacha/crate or booster feature exists.
- `content/presentation/events.yaml` is the one moment table (ADR-0267 cue path): 34 moments (countdown, kill/death/respawn, hit/crit/blocked, star pop/pickup/burst, Norma level/choice, queue/found, sudden death, win/lose/draw, reward, purchase, quest, player level-up, UI click/confirm/deny) with cue, audience cue, title, action bar and particle presets. All cues are own synthesized OGGs (`tools/sound-gen`, ffmpeg only) under `content/sounds/`, 6 BGM tracks play through the Paper music gateway.
- Death, respawn and player level-up are now visible as well as audible (soul / glint / spark presets; respawn action-bar line); `PresentationCueCoverageScenarioTest` covers them.
- UI: click is central in the GUI intent router; confirm/deny are played only by the queue, shop and cosmetic handlers.
- Status HUD: HP is the red bar, the energy (hunger) row is drawn as blue mana orbs (own 9x9 sprites in `content/pack-assets/.../hud/food_*.png`), and the ammo readout sits on the HP row to the right of the orb row, clear of both it and the cooldown row (`AmmoReadoutLayout`). HP regens 1/s after 5 s without a hit (`content/balance/health-regen.yaml`, `HealthRegenScenarioTest`).
- Gaps: the party / friends / vault / travel intent handlers refuse silently (no deny cue); 25 of 84 skills have no `play_sound` (skills-vfx area); star pop/burst strength is still a founder taste call.

## 理想形
One data table `content/presentation/events.yaml` maps each moment (queue, countdown, kill, death, respawn, star pop/pickup/burst, Norma level, win/lose, reward, purchase, UI) to title / sound / particle / music cues, all played through `IPlayerSoundGateway`. Own OGGs under `content/sounds/` plus a generated `sounds.json` in the resource pack. A Paper `IMusicGateway` switches BGM by mood. Norma level-up is the reference "feel".

## 未決
- Music style and the first 3 tracks (needs a founder taste check).
- Stars: how strong should pop/burst effects be?
- Do the unused sound/music sample YAMLs become real or get deleted?

## Governing ADRs
0026 (to be superseded), 0109, 0110, 0177, 0206, 0271 (stars-are-match-scoped), 0274/0275 (particle reskin and sword shading: founder confirmation pending).
