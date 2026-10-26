# Sound knowledge base

Optional knowledge helper: `.claude/agents/limbus-sound.md` (the skill author may consult it; it does not own the skill's sound). Team overview: [README.md](README.md).

## Pass rule (founder 2026-10-27, read first)

- **Sound passes only by a listener.** A human (the founder) or a reviewer that can actually listen hears the reference section and ours alternately (A/B) and says what is similar and what differs: attack, body, low end, metal, tail, sync with the blade. If nobody can listen, the status is `not listened`, never PASS.
- **Feature numbers are never a pass.** Onset, centroid, level, band shares and the timbre correlation in the Learnings log matched on S1 while the founder heard "cheap, weak impact, nothing like Limbus". Numbers are for showing the difference (sync, clipping, level against the reference), not for approving.
- **Record in the real client.** Every sound round includes an in-client recording (the real player path, `SHOT_SOUND_VOLUME` above 0 in `tools/vfx-shot`, default is 0 = muted) so the A/B compares what the game plays, plus an offline mix if useful; say which one it is. A/B files are given as audio on the skill-compare page (two buttons: reference section, ours), kept out of the repo.
- Sound events are columns of the beat sheet from step 1; the same author puts the sound on dev in the same round as the sword (sound is not a later, separate pass).
- Gate 2 (in game, by ear) is the only sound approval. If pure synthesis has not passed in 2 rounds, switch to layered CC0 recorded sources.

## Allowed and forbidden sources

- **Allowed:** ffmpeg synthesis, and **CC0 recorded sources** (metal hits, low impacts, wind) layered with synthesis. Record URL, author and licence (CC0) in the registry yaml comment or a provenance file next to the sound.
- **Forbidden:** paid services (fal.ai and the `generate-sounds` skill included), and any use of game audio: never ship, cut, process or derive from Limbus Company (or any game) audio. The reference audio is for listening and for showing differences only; fetch it to scratch, never into the repo or the pack.

## What the sound route produces

Per-beat SFX as OGG files under `content/sounds/<category>/` with a registry yaml, plus a table (beat tick -> sound id -> pitch/volume). The author places `play_sound`.

## The paid-service finding (read first)

The founder forbids paid services. **The existing route is paid.**

- `.claude/skills/generate-sounds/SKILL.md` calls `tools/sound-gen/scripts/fal_generate.sh` = fal.ai Stable Audio 2.5 and requires `FAL_KEY`.
- `tools/sound-gen/README.md` "Cost reference": flat **$0.20 per generation** (checked there as 2026-05-19). 100 SFX is about $20. ADR-0026 accepted this; it is superseded in practice by the founder's no-paid-services rule (manager memory "no paid services"). Do not run `/generate-sounds` without `--dry-run`, do not set `FAL_KEY`.
- The skill's `--local` flag (Stable Audio Open Small) is **reserved, not implemented**: it falls back to dry-run with a warning. Stable Audio Open weights are free (Stability Community License; ADR-0026 table says free if org revenue < $1M, attribution required, 11s/47s caps) but there is no wired local path.
- ElevenLabs Sound Effects was declined in ADR-0026 (redistribution prohibited in a resource pack).
- OGG files are committed (`content/sounds/weapons/bastion-s1-*.ogg` and others); their existence is not evidence of quality, only an in-client A/B listening is.

## Free route (documented; verify tools exist on the host)

1. **ffmpeg synthesis** (lavfi sources `sine`, `anoisesrc`, `aevalsrc`; filters `highpass`, `lowpass`, `bandpass`, `afade`, `aecho`, `volume`, `atempo`/`asetrate` for pitch, `amix` for layers). Write a repo script under `tools/sound-gen/scripts/` (for example `synth_*.sh`) so the result is reproducible, then `bash tools/sound-gen/scripts/to_ogg.sh in.wav content/sounds/<cat>/<name>.ogg`. `to_ogg.sh` (verified) does mono, 44.1 kHz, EBU R128 loudnorm (I=-16, TP=-1.5, LRA=11), libvorbis `-q:a 5`.
   Recipes: swing = band-passed noise with a rising then falling envelope (200-2500 Hz sweep) 0.2-0.5 s; impact = noise burst + sine thump 60-120 Hz with fast decay (transient first); metallic ring = sum of inharmonic sines with long decay; stinger = layered pitch-shifted copies. Layer rules in [vfx-art-direction section 0.6](../vfx-art-direction.md): two pitches of the same sound together; low base + high top; charge pitch up, swing/decay pitch down, a moment of silence at the apex, then the hit.
2. **CC0 sources** (freesound.org CC0 filter; ADR-0026 lists the CC0 mapping as complementary). Record URL, author and licence in the registry yaml comment. Never rip game audio (Limbus Company or others).
3. **Layering and pitch in-game**: `play_sound` pitch is clamped (`SoundSpec.MIN_PITCH..MAX_PITCH`); order several sounds with `delay_ticks` (no arc syntax, no `stopsound`). Different `pitch` on the same file gives variants cheaply.
4. If a beat cannot be made acceptable for free, report what is missing and ask the founder; do not buy and do not use game audio.

Tools needed: `ffmpeg` >= 4.2 with `loudnorm`, `ffprobe`; `sox` optional. `tools/sound-gen/scripts/dry_run.sh` makes a sine test WAV (offline smoke only).

## Format and registry (verified)

- Mono, 44.1 kHz, OGG Vorbis, ~96 kbps; file name kebab-case ASCII; categories `weapons skills status match ui`; variants `-01 -02` ([content/sounds/README.md](../../../content/sounds/README.md)).
- Check: `ffprobe -v error -show_streams <file> | grep -E 'codec_name|channels|sample_rate'` -> vorbis / 1 / 44100.
- Registry yaml (example `content/sounds/skills/fireball-cast.yaml`): `id: "astera:skill/fireball/cast"`, `resource_key`, `default_volume`, `default_pitch`, `category`, `display_name_key` (i18n key; en/ja parity applies).
- In a skill: `- type: play_sound`, `targets: { kind: caster }`, `sound_id: skills/fire-cast` ([add-a-skill-yaml.md](../add-a-skill-yaml.md)).
- Agents cannot hear. `ffprobe`, waveform PNG (`ffmpeg -i f.ogg -filter_complex showwavespic=s=1200x300 out.png`), spectrogram (`showspectrumpic`) and tick timing only check format, clipping and sync; they never decide quality. The vfx-shot client is muted by default (`SHOT_SOUND_VOLUME=0`); a sound round sets it above 0 and records in-client audio for the A/B. Being able to set the volume is not evidence that anyone listened.

## Beat guidance

- Telegraph: low rising sound 1-2 ticks before the hit. Hit: strong transient exactly at the damage tick; reverb restrained. Miss and block sounds differ from hit. Do not reuse one explosion for everything (sound inflation).
- Volume, pitch, number of layers proportional to skill importance (1-10).
- Multi-hit skills: hit sounds rise in pitch, the finisher is larger.

## Learnings log

(Append dated bullets after each skill. Format: `- YYYY-MM-DD <case>: <fact / pitfall / number>`.)

- 2026-10-23 seed: generate-sounds is paid (fal.ai, $0.20/clip) and `--local` is not implemented; free ffmpeg route documented here.
- 2026-10-24 bastion-greatsword-s1: free route exercised end to end. `tools/sound-gen/scripts/bastion_s1_synth.py` (numpy + scipy + ffmpeg libvorbis, seeds fixed) makes 10 cues in about 3 s; `bastion_s1_timeline.py` places cues by tick/pitch/volume and `bastion_s1_evidence.py` makes ffprobe/levels/wave+spectro. Reuse the helpers (swept SVF band-pass, modal partials, all-pass, comb).
- 2026-10-24 bastion-greatsword-s1: do not use `to_ogg.sh` loudnorm for layered transients; peak-normalise, encode, decode again and trim once (Vorbis -q:a 5 shifts the peak by up to 0.1 dB; stays under true peak -1 dBFS for a -1.5 target).
- 2026-10-24 bastion-greatsword-s1: summing the per-file peaks at 1.0 volume on one contact tick (impact + thud + ring + slam + whirl) reached +5.6 dBFS. Layered cues need `play_sound` volume < 1 (0.46 on hit 2, 0.56 on hit 1 gave -1.1 / -3.4 dBFS mixed peak, hit2-hit1 = +2.3 dB). Check with the timeline script before wiring.
- 2026-10-24 bastion-greatsword-s1: `play_sound` pitch is a playback-rate multiplier, so peak offset and length scale by 1/pitch. A whoosh with peak at 120 ms at pitch 0.8 peaks at 150 ms: play it at tick 27 (peak = tick 30.0) rather than 28 (tick 31.0 = contact).
- 2026-10-24 bastion-greatsword-s1: modal sums with random phases peak 50-80 ms late (beating); to put the peak at the strike add a short HP-noise tick and scale the partial sum down (armor-ring peak went 60 ms -> 4 ms). Dense modal decays still need a >20 ms trailing fade check.
- 2026-10-24 bastion-greatsword-s1: worktree was branched from main, not from `wp/pilot-bastion-s1`; the briefs were absent from it (267 commits diverged); merging `wp/pilot-bastion-s1` conflicts in `SpriteSources.kt`, so the briefs were read from the branch during the merge attempt and the merge was aborted. Director should base specialist worktrees on the pilot branch.
- 2026-10-24 bastion-greatsword-s1: pack builder (gradle) not run; agents cannot listen, so audibility is unchecked.
- 2026-10-24 bastion-greatsword-s1 r10: when the animator moves a contact tick, the cue sheet (`deliver/sound-cues.yaml`) and the checker's `CONTACT` in `tools/sound-gen/scripts/bastion_s1_timeline.py` go stale silently while the director shifts the skill YAML; resync both from the shipped `play_sound delay_ticks` and re-run the checker. Hit-1 r9 (contact 8): whoosh-heavy at tick 5 peaks at tick 7.43 (file peak 121 ms = 2.4 ticks), 0.57 tick before contact; tick 4 would peak 1.57 before. Keep 5: sound is not delayed in the client while displays show ~1-2 ticks late, so an earlier whoosh would lead the visible chop even more. Mixed peaks: hit 1 -3.5 dBFS at 8.03, hit 2 -1.1 dBFS at 31.05 (hit 2 +2.4 dB louder, as intended).
- 2026-10-26 bastion-greatsword-s1 r2: re-synthesised against the reference with `bastion_s1_features.py` (per-event onset/peak/decay/centroid/band shares/level), `bastion_s1_mix.py` (renders the sheet, `calibrate --write` solves volumes relative to the hit-1 body) and `bastion_s1_sound_evidence.py` (spectrogram/waveform pairs + table). Result: all onsets within 1 tick, centroids within 20% except swing-2 whoosh (+23%) and settle 2, levels within 0.2 dB. Never put the reference audio in the repo; fetch to scratch, keep numbers only.
- 2026-10-26 bastion-greatsword-s1 r2: reference clips carry music and voice. Judge events by 4.5-16 kHz band envelopes and the excess spectrum (window power minus median of the 5 ticks before onset); low-band peaks and decays (body thuds) are contaminated.
- 2026-10-26 bastion-greatsword-s1 r2: Chamberlin SVF band-pass clips near SR/6 (7.3 kHz at 44.1 kHz); bright 8-12 kHz noise needs STFT-mask shaped noise (`spec_noise`). Fractional-tick onsets go in as internal lead silence in the file because delays are integer ticks.
- 2026-10-26 bastion-greatsword-s1 r2b (manager review of spec-full.png: numbers matched, picture did not): matching onset/centroid/level is not enough. Brick-wall `lo`/`hi` masks in STFT noise read as static slabs: use soft roll-offs plus a smooth random time-frequency texture. Impact bodies are short thumps (<80 ms) with a pitch drop and slow wander, never a held tone. Every file needs a long cosine tail fade (here at least 40% of the file, max 120 ms) or the spectrogram shows vertical walls. Steel = inharmonic partials with own decays, wander and beating, not 2-3 pure sines. The evidence table now has a timbre metric (log band-energy correlation ref vs ours, 4.5-16 kHz and own band); low-band values are music-contaminated.

## Reference profile: bastion-greatsword-s1 (Limbus Ishmael, tick 0 = video t 11.633 s; contacts tick 7 / 31)

Numbers only; low band is music-contaminated.

| event | onset tick | character | centroid | level vs hit-1 body |
|---|---|---|---|---|
| E1 lift flick A | 0.1 | bright noise, 40 ms | 9.3 kHz | -6.0 dB |
| E2 lift flick B | 3.0 | bright noise, 55 ms | 9.1 kHz | -7.8 dB |
| E3 swing-1 whoosh | 5.0 | mid rising noise, peak t5.9 | 2.6 kHz | -12.1 dB |
| E4 impact-1 body | 7.0 | dull body, 76% in 150-500 Hz | 510 Hz | 0 |
| E5 shimmer 1 | 8.7 | noisy 2-12 kHz, about 1.7 ticks after contact | 6.5 kHz | -14.4 dB |
| E6 flick before swing 2 | 23.3 | bright noise | 7.7 kHz | -5.7 dB |
| E7 swing-2 whoosh | 26.5 | falling sweep ~12 kHz to 3.3 kHz, peak ~4 ticks before contact | 7.3 kHz | -3.3 dB |
| E8 impact-2 body | 30.3 | dull body | 390 Hz | -4.6 dB |
| E9 shimmer 2 | 32.6 | noisy 2-12 kHz | 6.9 kHz | -9.1 dB |
| E10 / E11 settle | 19.2 / 41.2 | quiet 500 Hz-1 kHz rattle | 560-760 Hz | -9.2 / -12.7 dB |
- 2026-10-27 bastion-greatsword-s1 impact weight pass: founder heard the impact as light. The pure-synth thump was too short and clean; impact-steel/cut-thud/ground-slam are now sub thump + CC0 plate/metal body (Kenney Impact Sounds 1.0, CC0, pitched down, low-passed) + transient crack + inharmonic ring, tanh-saturated with a short synthetic room tail. Hit-1 impact volume 0.59 -> 0.78, hit 2 0.31 -> 0.51 (mix peak -1.2 dBFS). Wiring audited statically: ids match and nothing else in the skill plays sound; vanilla GOLDEN_SWORD attack/damage sounds are not muted by the pack.
- 2026-10-27 S1 retrospectives: feature matching (onset/centroid/level/timbre) is not a pass; the founder heard the matched sound as cheap. Pass = in-client recording + A/B listening by a listener; CC0 layers with provenance are the default over pure synthesis; no game audio, no paid services. The numeric metrics above are historical and must not be used as an acceptance criterion.
