# Brief: limbus-sound (SFX) — S1 月輪の薙ぎ

Read `common.md` first. Role: the sound of the two sweeps: swing whoosh, impact, armour/metal, ground hit, plus small foley. You deliver OGG files and a cue table; the director wires `play_sound` steps in the skill YAML (you do not edit it).

## What exists (checked, do not rediscover)
- `.claude/skills/generate-sounds` + `tools/sound-gen/` (ADR-0018, ADR-0026): the only generation route is **fal.ai Stable Audio 2.5, a PAID API** ($0.20/clip, needs `FAL_KEY`). Its "local" mode (Stable Audio Open Small) is "reserved, not implemented". **Founder policy forbids paid services, so do not use `fal_generate.sh` or the skill's default path.** Reusable free parts: `tools/sound-gen/scripts/to_ogg.sh` (mono / 44.1 kHz / OGG Vorbis `-q:a 5`), the format rules in `content/sounds/README.md`, `ffmpeg`/`ffprobe` (installed). Note: `to_ogg.sh` applies `loudnorm I=-16` **per file**, which flattens the relative levels of a layered mix and is meaningless on 0.1-0.5 s transients: do NOT use its loudnorm; encode with peak normalisation instead (see Loudness).
- `content/sounds/` has no OGG yet (only two sample registry yamls, `match/phase-transition.yaml`, `skills/fireball-cast.yaml`). The current S1 uses vanilla sound events (`entity.player.attack.sweep` at tick 5 pitch 0.8 and tick 28 pitch 0.7, `...attack.strong` at 7, `...attack.crit` at 31) — thin and generic.
- Pack building: `tools/resource-pack/.../ResourcePackBuilder.kt` copies `content/sounds/**.ogg` to `assets/astera/sounds/**` and writes `sounds.json`: **a bare OGG is its own event `astera:<path-without-.ogg>`** (e.g. `content/sounds/weapons/bastion-s1-impact-steel.ogg` -> event `astera:weapons/bastion-s1-impact-steel`); a sidecar `.yaml` with `id:` renames the event. One sound per event, no pitch/volume variants in `sounds.json`. `SoundId` accepts `astera:weapons/...`; `play_sound` takes `sound_id`, `volume`, `pitch` (literals), `delay_ticks`. Prefer bare OGGs (no sidecar) unless `content-lint` requires one; check `tools/content-lint` and the language-key parity rule for `display_name_key` before adding any sidecar.

## Free pipeline to use (propose-and-build, no paid API)
1. **Procedural synthesis in Python** (`numpy` + `scipy` are installed; no `sox`): noise bursts through shaped band-pass/low-pass filters with envelopes, pitch-dropping sine sub, **modal synthesis for metal** (decaying inharmonic partials), simple comb/all-pass reverb tails, soft clipping for crunch, 4 random seeds per cue and pick the best. Script: `tools/sound-gen/scripts/bastion_s1_synth.py` (deterministic seeds, writes WAV to the scratchpad, then encodes).
2. **Layering vanilla events** (free, already in the pack): `entity.player.attack.strong/crit/knockback`, `item.armor.equip_iron`, `item.shield.block`, `block.chain.*`, `block.anvil.*`, `block.gravel.break` can be named in the cue table as optional layers at low volume; list them with reasons, the director decides.
3. **CC0 samples** only if a human-verifiable licence exists (e.g. freesound.org CC0, Sonniss GDC bundles): download into a fresh directory, record URL + licence + author in the cue table's `source:` field and in `tools/sound-gen/specs/bastion-s1.yaml`; no CC-BY/NC. Synthesis is the default; samples only to raise realism of the ground crunch or the cloth/armour rattle.
4. **Encode**: `ffmpeg -y -i in.wav -ac 1 -ar 44100 -af "alimiter=limit=<peak>" -c:a libvorbis -q:a 5 out.ogg` after peak-normalising in Python to the per-cue peak below. Mono is mandatory (3D attenuation).

## Cue list (skill ticks; 1 tick = 50 ms; contacts at tick 7 and 31)
All files in `content/sounds/weapons/`, mono 44.1 kHz OGG Vorbis. "Peak offset" = where the loudest moment is inside the file, so the director can place the file start so that the peak lands on the intended tick (`play tick = peak tick - peak_ms/50`, rounded).
| file | length | peak offset | play at skill tick (hit 1 / hit 2) | pitch (1 / 2) | peak dBFS | content |
|---|---|---|---|---|---|---|
| `bastion-s1-step-scuff` | 0.35 s | 40 ms | 0 / 24 | 1.0 / 0.85 | -14 | boot scrape + chest-plate/mail rattle during the dash step-in (cloth, 0.5-3 kHz, short metal ticks) |
| `bastion-s1-blade-lift` | 0.30 s | 60 ms | 3 / 27 | 1.0 / 0.9 | -16 | the heavy blade leaving the floor: low scrape 150-800 Hz + one faint steel "tink" 3-5 kHz |
| `bastion-s1-flash-sting` | 0.12 s | 10 ms | - / 30 | - / 1.0 | -8 | thin air-tearing "shk" (noise 3-9 kHz, fast decay), the ink-flash frame; followed by a 40 ms near-silence |
| `bastion-s1-whoosh-heavy` | 0.60 s | 120 ms | 4 / 28 (peak lands 1 tick before contact: tick 6.4 / 30.4) | 1.0 / 0.8 | -9 | heavy greatsword air displacement: band-passed noise sweeping 400 -> 2500 -> 700 Hz (rise 120 ms, fall 400 ms) over a 90-200 Hz body; Doppler-like falling pitch of the body |
| `bastion-s1-impact-steel` | 0.45 s | 0 ms (attack < 3 ms) | 7 / 31 | 1.0 / 0.85 | -1.5 | the contact: 60-120 Hz thump (25 ms) + steel clang (inharmonic partials ratio 1 : 2.76 : 5.40 : 8.93 on a 520 Hz base, decay 250 ms) + 5 ms noise click |
| `bastion-s1-cut-thud` | 0.25 s | 5 ms | 7 / 31 | 1.0 / 0.9 | -6 | the body/cloth layer under the clang: low-passed noise burst + 150 Hz thud, 80 ms |
| `bastion-s1-armor-ring` | 0.90 s | 15 ms | 7 / 31 (1 tick late is fine) | 1.1 / 0.8 | -12 | steel ring-out of the struck armour and the sword: modal partials 1.1 kHz base, 4 partials, decay 0.8 s, light all-pass reverb |
| `bastion-s1-ground-slam` | 0.60 s | 10 ms | - / 31 | - / 1.0 | -2 | the hit-2 ground shock (low gold ring + dust): sub sine 70 -> 38 Hz over 250 ms + gravel crunch (noise 200-1200 Hz low-pass, 0.4 s) |
| `bastion-s1-ring-whirl` | 0.90 s | 250 ms | 8 / 32 | 1.2 / 1.0 | -12 | the gold ring in the air: metallic shimmer (comb-filtered noise, rising partials 2-6 kHz) swelling then fading; hit-1 version plays at lower volume and pitch 1.2 |
| `bastion-s1-sword-settle` | 0.25 s | 30 ms | 19 / 40 | 1.0 / 0.9 | -16 | the blade returning to rest: soft steel tink + leather/cloth rattle |
Vanilla optional layers to list in the cue table: `item.armor.equip_iron` volume 0.4 at 0 / 24; `entity.player.attack.crit` volume 0.5 at 31.

## Layering and loudness (measurable)
- Relative mix at the contact moments (measured by summing the placed cues in a timeline WAV, see Self-check): impact + cut-thud peak is the loudest event; whoosh peak at -6 dB (+-2) below the impact peak; armour-ring at -10 dB (+-2); ring-whirl at -10 dB; step/scuff/settle/blade-lift at least -12 dB under the impact. Hit 2 impact is +2 dB (+-1) louder than hit 1 (cue pitch 0.85 and the extra ground-slam do this; do not exceed the per-cue peaks above).
- **Peak normalise** each file to the "peak dBFS" column (true peak <= -1.0 dBFS; no clipping); report `volumedetect` max/mean volume and `ebur128` integrated/true-peak per file in the handback table. Do not loudnorm.
- DC offset < 0.001, start and end fade 2 ms/10 ms (no clicks), no silence longer than 30 ms at the start, trailing silence <= 20 ms.
- Spectra (check with `ffmpeg showspectrumpic` and open the PNG): whoosh energy centroid moves up then down; impact has energy at 60-120 Hz and 2-6 kHz; ring has discrete partial lines; ground-slam fundamental falls.
- Timing: every cue's placed peak must fall within +-1 tick of the intended tick (whoosh peak at contact-1 tick, impact peak = contact tick, armour ring on contact or +1, ground-slam = contact 2, settle at 19/40).

## Delivery to the director: `deliver/sound-cues.yaml`
One row per file: `file`, `event` (`astera:weapons/<name>`), `hit1: {tick, volume, pitch}`, `hit2: {...}`, `peak_ms`, `layer` (whoosh|impact|armour|ground|foley|sting), `source` (`synth` | CC0 URL + licence), `notes`, plus the vanilla optional layers. The director turns it into `play_sound` steps (delay_ticks), keeping the old vanilla sweep/strong/crit steps out of the way.

## File territory
`content/sounds/weapons/bastion-s1-*.ogg` (+ `.yaml` only if required), `tools/sound-gen/scripts/bastion_s1_*.py` (synth + encode + timeline checker), `tools/sound-gen/specs/bastion-s1.yaml` (recipe/seeds/source list), `tools/skill-compare/data/bastion-greatsword-s1/deliver/sound-cues.yaml`, `tools/skill-compare/data/bastion-greatsword-s1/evidence/sound/**`. Do not edit `content/sounds/README.md`, the generate-sounds skill, ADR-0026, `fal_generate.sh` or the skill YAML. Policy conflict (the skill defaults to the paid route) is a request to the manager: propose amending the skill to make a free local synth route the default.

## Self-check
1. `ffprobe` on every OGG: `codec_name=vorbis`, `channels=1`, `sample_rate=44100`; sizes < 60 kB each.
2. For every file: waveform PNG (`showwavespic`) and spectrogram PNG (`showspectrumpic`), **open them**, state what they show against the table.
3. Build a **timeline WAV** of the whole skill (0-52 ticks) by placing every cue at its tick/pitch/volume from your cue table (script `bastion_s1_timeline.py`), save a labelled waveform PNG with tick gridlines and the contact ticks 7 and 31 marked, and measure the per-tick peak envelope; show that the table's peaks/ticks and the relative levels hold.
4. Pack check: if the resource-pack builder can be run without gradle `check` (see `tools/resource-pack/README.md`), build and confirm `assets/astera/sounds.json` lists the 10 events and the OGGs are in the pack; otherwise say "pack not built". Do not claim audibility: agents cannot listen, the founder/reviewer listen in the client. Say so explicitly and list the first three cues to listen to (whoosh -> impact -> ring on hit 1).

## Done means
- All 10 files exist, valid format, within the loudness/timing/spectral targets or misses listed; free synthesis route documented in the script header (no API key, no network needed); any CC0 source has URL + licence.
- Timeline waveform shows the swing whoosh before each contact and the impact exactly on tick 7 / 31 (+-1), hit 2 louder and heavier.
- `deliver/sound-cues.yaml` complete. Handback lists what is still wrong and what only a human ear can judge. No self-grading.
