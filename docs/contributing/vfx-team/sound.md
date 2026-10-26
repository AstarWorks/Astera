# Sound knowledge base

Agent: `.claude/agents/sound-designer.md`. Team overview: [README.md](README.md).

## What the sound designer produces

Per-beat SFX as OGG files under `content/sounds/<category>/` with a registry yaml, plus a table (beat tick -> sound id -> pitch/volume). The director places `play_sound`.

## The paid-service finding (read first)

The founder forbids paid services. **The existing route is paid.**

- `.claude/skills/generate-sounds/SKILL.md` calls `tools/sound-gen/scripts/fal_generate.sh` = fal.ai Stable Audio 2.5 and requires `FAL_KEY`.
- `tools/sound-gen/README.md` "Cost reference": flat **$0.20 per generation** (checked there as 2026-05-19). 100 SFX is about $20. ADR-0026 accepted this; it is superseded in practice by the founder's no-paid-services rule (manager memory "no paid services"). Do not run `/generate-sounds` without `--dry-run`, do not set `FAL_KEY`.
- The skill's `--local` flag (Stable Audio Open Small) is **reserved, not implemented**: it falls back to dry-run with a warning. Stable Audio Open weights are free (Stability Community License; ADR-0026 table says free if org revenue < $1M, attribution required, 11s/47s caps) but there is no wired local path.
- ElevenLabs Sound Effects was declined in ADR-0026 (redistribution prohibited in a resource pack).
- No OGG files are committed yet: `content/sounds/` contains only registry yaml (`match/phase-transition.yaml`, `skills/fireball-cast.yaml`) and README. So there is no precedent for audio quality in-repo.

## Free route (documented; verify tools exist on the host)

1. **ffmpeg synthesis** (lavfi sources `sine`, `anoisesrc`, `aevalsrc`; filters `highpass`, `lowpass`, `bandpass`, `afade`, `aecho`, `volume`, `atempo`/`asetrate` for pitch, `amix` for layers). Write a repo script under `tools/sound-gen/scripts/` (for example `synth_*.sh`) so the result is reproducible, then `bash tools/sound-gen/scripts/to_ogg.sh in.wav content/sounds/<cat>/<name>.ogg`. `to_ogg.sh` (verified) does mono, 44.1 kHz, EBU R128 loudnorm (I=-16, TP=-1.5, LRA=11), libvorbis `-q:a 5`.
   Recipes: swing = band-passed noise with a rising then falling envelope (200-2500 Hz sweep) 0.2-0.5 s; impact = noise burst + sine thump 60-120 Hz with fast decay (transient first); metallic ring = sum of inharmonic sines with long decay; stinger = layered pitch-shifted copies. Layer rules in [vfx-art-direction section 0.6](../vfx-art-direction.md): two pitches of the same sound together; low base + high top; charge pitch up, swing/decay pitch down, a moment of silence at the apex, then the hit.
2. **CC0 sources** (freesound.org CC0 filter; ADR-0026 lists the CC0 mapping as complementary). Record URL, author and licence in the registry yaml comment. Never rip game audio (Limbus Company or others).
3. **Layering and pitch in-game**: `play_sound` pitch is clamped (`SoundSpec.MIN_PITCH..MAX_PITCH`); order several sounds with `delay_ticks` (no arc syntax, no `stopsound`). Different `pitch` on the same file gives variants cheaply.
4. If a beat cannot be made acceptable for free, report it; do not buy.

Tools needed: `ffmpeg` >= 4.2 with `loudnorm`, `ffprobe`; `sox` optional. `tools/sound-gen/scripts/dry_run.sh` makes a sine test WAV (offline smoke only).

## Format and registry (verified)

- Mono, 44.1 kHz, OGG Vorbis, ~96 kbps; file name kebab-case ASCII; categories `weapons skills status match ui`; variants `-01 -02` ([content/sounds/README.md](../../../content/sounds/README.md)).
- Check: `ffprobe -v error -show_streams <file> | grep -E 'codec_name|channels|sample_rate'` -> vorbis / 1 / 44100.
- Registry yaml (example `content/sounds/skills/fireball-cast.yaml`): `id: "astera:skill/fireball/cast"`, `resource_key`, `default_volume`, `default_pitch`, `category`, `display_name_key` (i18n key; en/ja parity applies).
- In a skill: `- type: play_sound`, `targets: { kind: caster }`, `sound_id: skills/fire-cast` ([add-a-skill-yaml.md](../add-a-skill-yaml.md)).
- Audio cannot be heard by agents. Verify by `ffprobe`, waveform PNG (`ffmpeg -i f.ogg -filter_complex showwavespic=s=1200x300 out.png`), spectrogram (`showspectrumpic`), and timing relative to the beat tick. The vfx-shot client records with sound off, so in-game audio is not checked by captures.

## Beat guidance

- Telegraph: low rising sound 1-2 ticks before the hit. Hit: strong transient exactly at the damage tick; reverb restrained. Miss and block sounds differ from hit. Do not reuse one explosion for everything (sound inflation).
- Volume, pitch, number of layers proportional to skill importance (1-10).
- Multi-hit skills: hit sounds rise in pitch, the finisher is larger.

## Learnings log

(Append dated bullets after each skill. Format: `- YYYY-MM-DD <case>: <fact / pitfall / number>`.)

- 2026-10-23 seed: generate-sounds is paid (fal.ai, $0.20/clip) and `--local` is not implemented; free ffmpeg route documented here but not yet exercised. No OGG exists in `content/sounds/` yet.
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
