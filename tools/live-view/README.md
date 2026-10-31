# Live view PoC

Watch and hear a **real Java Minecraft client** while editing skill YAML. Capture only: no bot gameplay, custom Minecraft implementation, paid service, or cloud. Runs entirely on the capture host.

```sh
make live-view SKILL=bastion-greatsword-s1
make live-view SKILL=bastion-greatsword-s1 VIEW=side PORT=8098
make live-view SKILL=bastion-greatsword-s1 VIEW=compare CASE=bastion-greatsword-s1
make live-view-down
```

Open the 「ライブ」 tab at **http://192.168.11.5:8097/live** after `make skill-compare-up`. The hub embeds this backend (default port 8098); set `SKILL_COMPARE_LIVE_URL` on the hub for a different capture host/port. Direct backend access remains available for capture diagnostics. The page binds **0.0.0.0**. It is an unauthenticated authoring tool for a trusted LAN; do not forward its port to the Internet. Control requests and WebSockets require the page's own Origin. Keep the terminal running; Ctrl-C, `make live-view-down`, the caller disappearing, or the capture watchdog stops the session and removes its stack. Startup builds compile/package tasks only, then boots Paper and joins the real client (typically a few minutes cold).

## Prerequisites

The [vfx-shot prerequisites](../vfx-shot/README.md) apply: Docker Compose, JDK/Gradle, Python, portablemc (installed into the existing capture cache), Xvfb, Mesa/llvmpipe, xdotool, jq and ffmpeg with x11grab, libx264, AAC, PulseAudio and drawtext support. Additionally install **Bun**, **pulseaudio**, and **pulseaudio-utils**. Debian/Ubuntu:

```sh
sudo apt-get install pulseaudio pulseaudio-utils
```

No npm dependencies are needed to serve the page. Use a browser with H.264/AAC MediaSource support (verified in Chromium); audio starts muted to satisfy autoplay rules. Click **Unmute** to hear it.

## Controls

- **Skill / Cast:** IDs are discovered from this checkout's `content/skills/*.yaml`; startup verifies its selected ID against `/astera skill list`. Sends `/astera skill cast <id> Caster --force` through the existing RCON client. Each cast resets the arena, returns the caster, replaces the dummy and equips the inferred item (`ITEM=` overrides it). **Recast** repeats the last successfully cast skill.
- **Reload content:** stages and hash-verifies the current worktree's content using vfx-shot's existing synchronization, copies it into the private capture server and invokes **`/astera admin reload all`**, then `/astera items refresh`. The actual command is declared by `core/feature-admin/.../AdminOpsCommands.kt`. YAML changes apply to the next cast without restarting Paper. Resource-pack assets and plugin jars require restarting the live session to rebuild; this button updates content only.
- **View / Apply view:** caster first person, side spectator, or compare spectator. Compare reads `case.json`'s `intent.file` (defaults to `intent.json`), including named camera presets. `compare_plan.py` supplies enemy placement, initial FOV and camera-key `/tp` commands; `director.py` schedules the same cuts/dollies during the cast. Later key FOV changes are emulated by dollying, as in vfx-shot. A change in viewer size/FOV may restart a client (roughly 30–90 seconds); playback reconnects automatically afterwards. Actions are serialized; other tabs receive HTTP 409 while an action runs.
- **HUD:** the existing per-client F1 helper. Hiding HUD also hides the first-person hand, matching Minecraft F1 behavior. Default hidden (`HIDE_HUD=0` starts visible).
- **Snapshot PNG:** saves a full-resolution image directly from the viewing client's X display.
- **Record clip MP4:** saves the last 1–120 seconds already captured, with H.264 video and AAC audio, without a second live encoder. Duration rounds to fragment boundaries and is limited by available history (also capped at 96 MiB). Recording history resets on view changes/reconnect. Artifacts are under `build/live-view/<selected-skill>/snapshot-<epoch-ms>.png` and `clip-<epoch-ms>.mp4`, linked on the page. A clip can contain earlier skill casts if the requested time range spans them.
- **Reconnect:** restarts the encoder and the player's MediaSource. Socket loss reconnects automatically, joining at the next keyframe instead of replaying stale history. State, decoded/dropped frames, capture fps and buffered seconds appear above the video.

## Architecture and transport choice

`live-view.sh` **sources vfx-shot's existing implementation**: portablemc, server artifacts/content deployment, isolated `astera-shot-<checkout-hash>` Compose project, scene placement, clients, per-stack lock, host-wide slot, memory guard, watchdog and reaper. One live session owns **one slot for its full lifetime**. It shares this checkout's vfx-shot stack/lock, so do not run vfx-shot concurrently in the same checkout. Other checkouts queue normally. `SHOT_KEEP` is deliberately disabled for live sessions.

A private PulseAudio daemon exposes a Unix socket in a mode-0700 session directory `/tmp/claude-1000/astera-live-<id>/`. Separate null sinks exist for `Caster` and `Watcher`; `PULSE_SERVER` and `PULSE_SINK` explicitly select the private server/sink for each Java client; each sink also has a unique device description selected through Minecraft’s `soundDevice` option (OpenAL otherwise confused identically named null outputs). No host/default audio sink is changed. Minecraft master sound is enabled; ffmpeg captures **only the selected client's sink monitor**. This works without a sound card or host audio server. The PulseAudio daemon, Bun, ffmpeg and control processes inherit `VFX_SHOT_STATE`, so the existing reaper can remove them after a killed owner as well as on normal shutdown.

One ffmpeg captures Xvfb at 1280×720/20 fps and the private monitor at 48 kHz stereo. It encodes H.264 Baseline (`ultrafast`, `zerolatency`, no B frames, keyframe every ~250 ms) and AAC into **fragmented MP4 over WebSocket**. Bun forwards complete MP4 fragments to native browser MediaSource and retains a bounded clip ring. The browser skips toward the newest frame if its buffer exceeds 650 ms, retains a small playback window, and reconnects slow receivers instead of building an unbounded queue.

This uses stock ffmpeg + Bun + browser APIs, without another gateway, ICE, certificate setup or an additional codec/player library. Both audio and video use the same muxer clock. WebRTC would better handle congested networks; for a local trusted LAN, ~250 ms fragments meet the PoC latency target with much less infrastructure. TCP loss/retransmission can increase latency, so the measured local result is not a WAN guarantee. See ffmpeg's [MP4 muxer options](https://ffmpeg.org/ffmpeg-formats.html#mov_002c-mp4_002c-ismv) for `empty_moov`, `default_base_moof` and `frag_keyframe`.

## Knobs and logs

| Knob | Default | Meaning |
|---|---|---|
| `SKILL` | required | Initial registered skill ID |
| `VIEW` | `caster` | `caster`, `side`, `compare` |
| `CASE` | empty | Required for compare; dropdown can choose one later |
| `CAMERA` | `case` | Compare intent keys, `side`, or an intent preset |
| `PORT` | `8098` | LAN HTTP/WebSocket port |
| `RES` | `1280x720` | Capture size; keep even dimensions for H.264 |
| `LIVE_FPS` | `20` | Client fps cap and capture fps, 5–60 |
| `HIDE_HUD` | `1` | Initial F1 state |
| `ITEM` | inferred from skill | Item held by Caster |
| `SHOT_TIMEOUT` | `2700` | Maximum session duration in seconds; raise for longer authoring |
| `SHOT_SLOTS` | `1` | Shared host-wide capture slot limit |
| `SHOT_MIN_FREE_GB` | `6` | Existing capture memory guard |
| `LP_NUM_THREADS` | `4` | llvmpipe worker threads |

Other vfx-shot boot/client knobs apply; sound is always enabled and keeping the stack is disabled. Compare view controls use the case FOV; selecting a different case/preset can restart the viewer to apply it.

State and process logs: `build/vfx-shot/.state/<checkout-hash>/` (`pulse.log`, `ffmpeg.log`, `control.log`, `casts.log`, `watchdog.log`, `gradle.log`). Client logs: `build/vfx-shot/.clients/<hash>/<name>/client.log`. Outputs and verification reports: `build/live-view/`.

- **Queued:** another capture owns the slot; live sessions hold it until stopped. Stop your session when done.
- **No audio:** check `pulse.log`, the client's `OpenAL initialized` line, and `PULSE_SERVER=unix:/tmp/claude-1000/astera-live-<id>/pulse.sock pactl list sink-inputs short`. Click Unmute. Lack of a system D-Bus can generate PulseAudio log warnings but does not prevent these null sinks from working.
- **Black/stale player:** inspect `ffmpeg.log`, click Reconnect; verify H.264/AAC MediaSource support and firewall access to the chosen port. FOV/view switching is visibly disconnected while clients rejoin.
- **Reload errors:** inspect `control.log` and the capture server logs; fix the YAML and reload again. A resource-pack change requires a session restart, not just content reload.
- **No hand:** enable HUD. This is the real F1 behavior, not missing stream pixels.
- **High CPU:** llvmpipe renders the real game on CPU. Lower `RES`, `LIVE_FPS`, render distance or llvmpipe threads, and avoid heavy builds while viewing.

## Real-stack verification

With a live session running, the integration script drives Chromium against the actual server/client, exercises Cast/Recast, HUD, reload, snapshot, clip and reconnect, checks MP4 streams with ffprobe, and measures fps/CPU and capture-to-browser delay. It reuses skill-compare's existing Playwright dependency; the optional measurement overlay needs `python3-tk`.

```sh
(cd tools/skill-compare && bun install)
sudo apt-get install python3-tk  # measurement only
VERIFY_VIEWS=1 VERIFY_RELOAD_EDIT=1 node tools/live-view/verify.mjs
make live-view-down
```

`VERIFY_RELOAD_EDIT=1` temporarily adds a uniquely named copy of the selected skill YAML, reloads it, casts it successfully, and confirms the Paper container start time is unchanged; it removes the fixture and reloads again in a `finally` block. Do not edit that temporary `live-view-reload-probe.yaml` file concurrently.

`VERIFY_VIEWS=1` additionally boots side and compare views (`CASE=` defaults to `bastion-greatsword-s1`). `LIVE_URL=` and `CHROMIUM=` override the test URL/browser. No unit tests or Gradle test/check tasks run. The overlay writes an epoch-ms barcode onto the **actual viewing X display**; Chromium decodes it from displayed video frames and subtracts it from browser wall time. That measures X presentation → capture → encode → WebSocket → MediaSource → displayed video, with up to 50 ms overlay sampling uncertainty. It does not measure a physical monitor's refresh or a remote LAN link. The regular stream also carries a readable host-clock overlay.

Results are saved as `build/live-view/verification.json` and `build/live-view/browser.png`. The verification report for this implementation is in `verification.md`.
