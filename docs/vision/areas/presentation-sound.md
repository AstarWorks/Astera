# Area: presentation and sound

Owner track `wp/presentation` (skill-level VFX/sound belongs to `skills-vfx`). Items: Board prefix `FX-`.

## 構想 (source)
- Only stars-and-norma.md specifies presentation: star pickup sound and "+N", Norma level-up title + sound + particles, choice chime, 10 s default notice.
- ADR-0206: kill feed, hit-marker sounds (normal / crit / block / finisher), damage numbers, nameplates.
- ADR-0109/0110: effect orchestration and BGM moods (lobby / match / boss / victory) as foundations.
- ADR-0026 planned 20-40 generated SFX via a paid service. **Superseded in intent by the founder decision 2026-10-26: sound is sourced free only (ffmpeg synthesis or CC0).** A supersede ADR is still owed.
- `content/sounds/README.md`: weapons / skills / status / match / ui OGG, mono 44.1 kHz.

## 現状
- Zero OGG files in the repo. `content/sounds/` has 2 sample YAMLs (unused); `content/music/` has 6 YAMLs pointing at non-existent files. Everything audible is vanilla sound.
- `IMusicGateway` has only `InMemoryMusicGateway`; no Paper implementation, so no BGM and no ambience.
- Silent moments (no sound call in code): countdown, match start/phase change, death, respawn, victory/defeat (titles only), level-up/achievement, quest, login bonus, purchase, UI clicks, lobby join/queue join.
- Best-done moment: Norma level-up (`PaperNormaPresentation`: title, level-up sound, totem + end-rod + firework). Star pop has a name tag only; pickup plays an orb sound; burst is the drop only.
- 59 of 84 skills have `play_sound` (all vanilla); 25 are silent.
- Three sound routes coexist: raw `Sound` enum, `IPlayerSoundGateway`, skill YAML `play_sound`.
- VFX: 211 vfx YAMLs in 10 weapon families; their quality is judged in the skills-vfx area.

## 理想形
One data table `content/presentation/events.yaml` maps each moment (queue, countdown, kill, death, respawn, star pop/pickup/burst, Norma level, win/lose, reward, purchase, UI) to title / sound / particle / music cues, all played through `IPlayerSoundGateway`. Own OGGs under `content/sounds/` plus a generated `sounds.json` in the resource pack. A Paper `IMusicGateway` switches BGM by mood. Norma level-up is the reference "feel".

## 未決
- Music style and the first 3 tracks (needs a founder taste check).
- Stars: how strong should pop/burst effects be?
- Do the unused sound/music sample YAMLs become real or get deleted?

## Governing ADRs
0026 (to be superseded), 0109, 0110, 0177, 0206, 0271 (stars-are-match-scoped), 0252/0253 (particle reskin and sword shading: founder confirmation pending).
