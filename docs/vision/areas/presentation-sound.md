# Area: presentation and sound

Owner track `wp/presentation` (skill-level VFX/sound belongs to `skills-vfx`). Items: Board prefix `FX-`.

## 構想 (source)
- Only stars-and-norma.md specifies presentation: star pickup sound and "+N", Norma level-up title + sound + particles, choice chime, 10 s default notice.
- ADR-0206: kill feed, hit-marker sounds (normal / crit / block / finisher), damage numbers, nameplates.
- ADR-0109/0110: effect orchestration and BGM moods (lobby / match / boss / victory) as foundations.
- ADR-0026 planned 20-40 generated SFX via a paid service. **Superseded in intent by the founder decision 2026-10-26: sound is sourced free only (ffmpeg synthesis or CC0).** A supersede ADR is still owed.
- `content/sounds/README.md`: weapons / skills / status / match / ui OGG, mono 44.1 kHz.

## 現状
- Presentation table exists: `content/presentation/events.yaml` maps 31 moments (countdown, kill/death/respawn, hit/crit/blocked, skill/ego ready, star pop/pickup/burst, Norma level/choice, sudden death, win/lose/draw, login/purchase/quest/level, UI) to cue / title / action bar / particle; all played through `IPresentation` (`PresentationMoments.ALL` has a coverage scenario).
- Sound: every cue under `content/sounds/` is now an own ffmpeg-synthesized OGG (`tools/sound-gen/scripts/synth.sh`, 2026-10-27 added hit/crit/blocked, skill/ego-ready, respawn, draw, star pop/gain, Norma burst/choice, level-up/login/quest, sudden-death). No vanilla stand-ins left in cue yamls except the skill YAML `play_sound` routes.
- Sudden death (final stretch): warning sting + "Final stretch!" title for every member, then high-intensity BGM.
- BGM: all 6 `content/music/` tracks are own procedural OGGs (`tools/sound-gen/scripts/music_synth.py`: lobby calm, match tense, high-intensity drums, boss, reveal, victory), shipped in the pack as streamed events `astera:music/<id>` and played by `PaperMusicGateway` via mood switching. Tone is unjudged: founder taste check pending.
- Hit marker: the central `astera:combat/hit|crit|blocked` is the only one; the vanilla `hit_player` play_sound was removed from the 7 gun/magic skills. Other skill YAML `play_sound`s (59 of 84 skills, vanilla ids; 25 silent) are owned by skills-vfx.
- Queue: `queue-join` cue when queued, `match-found` sting + title for the formed roster. Lobby join has no extra cue (the lobby BGM starting is its sound).
- VFX quality of the 211 vfx YAMLs is judged in skills-vfx.

## 理想形
One data table `content/presentation/events.yaml` maps each moment (queue, countdown, kill, death, respawn, star pop/pickup/burst, Norma level, win/lose, reward, purchase, UI) to title / sound / particle / music cues, all played through `IPlayerSoundGateway`. Own OGGs under `content/sounds/` plus a generated `sounds.json` in the resource pack. A Paper `IMusicGateway` switches BGM by mood. Norma level-up is the reference "feel".

## 未決
- Music style and the first 3 tracks (needs a founder taste check).
- Stars: how strong should pop/burst effects be?
- Do the unused sound/music sample YAMLs become real or get deleted?

## Governing ADRs
0026 (to be superseded), 0109, 0110, 0177, 0206, 0271 (stars-are-match-scoped), 0274/0275 (particle reskin and sword shading: founder confirmation pending).
