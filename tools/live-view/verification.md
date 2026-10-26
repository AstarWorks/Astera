# Real-client verification — 2026-10-27 JST

Worktree: `.claude/worktrees/live-view-poc`, branch `wp/live-view-poc`. Skill/case: `bastion-greatsword-s1`. Chromium headless on the capture host; real portablemc Java clients under Xvfb/llvmpipe, private Paper Compose project `astera-shot-18ca3c0a`. No unit tests, Gradle test/check tasks or full e2e stack were run. No plugin code changed.

## Functional evidence

- Started with `make live-view SKILL=bastion-greatsword-s1` and with `VIEW=compare CASE=bastion-greatsword-s1`.
- `tools/live-view/verify.mjs` drove actual browser playback, Unmute, Cast/Recast, HUD show/hide, reload, reconnect, PNG and MP4 writes. It switched side → compare and verified resumed playback. Browser JavaScript errors: **0**.
- Compare selected FOV **45°** from `intent-redo.json` through `case.json`'s `intent.file`. `/tp` camera keys were sent by the existing director; a saved snapshot shows the real caster's sword/pose/VFX beside the target.
- MP4: **H.264, 1280×720, 20 fps + AAC, 48 kHz, stereo**. ffmpeg `volumedetect` on the compare clip measured **−23 dB mean**, confirming actual game sound, not just a silent audio track.
- Audio device selection is explicit in both layers: unique PulseAudio sink descriptions plus Minecraft `soundDevice`. Logs show `OpenAL initialized on device live_18ca3c0a_Watcher` / `_Caster`, and PulseAudio confirms their streams are attached to separate sinks (1 / 0).
- `VERIFY_RELOAD_EDIT=1` temporarily added `content/skills/live-view-reload-probe.yaml`, ran reload, and successfully cast the new ID. Paper's `StartedAt` stayed `2026-10-26T16:40:35.066449104Z`. The fixture was deleted and content reloaded again. Existing content files remain unchanged.

## Measured stream performance

Actual X-display epoch-ms barcode → displayed browser video; 10-second samples, up to 50 ms barcode sampling uncertainty. Same-host browser, so this does not include an external LAN or physical monitor refresh. CPU percentages use one core = 100%, include the session's Java clients, Xvfb, ffmpeg, Bun and private PulseAudio, and exclude Paper/Docker and Chromium.

| 1280×720 stream | Caster | Compare (Watcher + small Caster) |
|---|---:|---:|
| Displayed fps | 20.00 | 19.99 |
| Dropped browser frames | 0 | 0 |
| Latency min / median / p95 | 603 / **628** / 652 ms | 604 / **632** / 657 ms |
| Decoded timestamp samples | 200 | 201 |
| Session CPU | **263.6%** (2.64 cores) | **403.1%** (4.03 cores) |
| ffmpeg CPU, included above | 17.6% | 18.7% |

Raw reports: `build/live-view/verification-caster-clean.json`, `verification-compare.json`. Current/latest run writes `verification.json`; browser screenshot: `build/live-view/browser.png`.

## Reviewable artifacts

Relative to the worktree root:

- Visible compare cast PNG: `build/live-view/bastion-greatsword-s1/snapshot-1793033107352.png`
- Compare video + game-audio MP4: `build/live-view/bastion-greatsword-s1/clip-1793033107520.mp4`
- Mean-audio verification clip: `build/live-view/bastion-greatsword-s1/clip-1793032955717.mp4`
- Caster PNG / MP4: `build/live-view/bastion-greatsword-s1/snapshot-1793033280558.png`, `clip-1793033280773.mp4` in the same directory.

Artifacts and runtime logs stay in gitignored `build/`; this document records their paths rather than committing binary captures.

## Regression and teardown

- `CLAPPER=0 make vfx-shot SKILL=bastion-greatsword-s1 VIEW=caster TICKS=6` succeeded: seven 640×360 frames, sheet, GIF, MP4 and metadata. The original batch workflow retains its default sound-off behavior. Log: `build/live-view/vfx-shot-regression.log`.
- `make live-view-down` exited successfully after both live sessions. After the final regression teardown, `build/live-view/cleanup.json` confirmed zero session-marked processes, watchdogs, containers, volumes and networks; port 8098 closed; host-wide capture slot free.
- Bash/JavaScript syntax checks, ShellCheck for the two new shell scripts, and `git diff --check` passed.

## Limits and existing documentation discrepancies

- View/FOV changes can restart clients and interrupt playback for about 30–90 seconds. Ordinary casts, content reloads, snapshots and clips do not restart Paper.
- Resource-pack assets/plugin-code changes require restarting the live session; Reload content only synchronizes YAML/content.
- Chromium was tested. Other browsers and playback over a separate LAN machine remain unverified.
- `tools/vfx-shot/README.md` describes arena y = −60; `vfx-shot.sh` actually sets `Y=100` to avoid void rescue. The implementation uses 100.
- `tools/skill-compare/README.md` says old intent files are ignored by its comparison UI; capture still uses the active `case.intent.file`. Live view follows the capture code, including `intent-redo.json`.
- Older comments in `CoreWiringContent.kt` mention `/astera reload`; the declared command in `AdminOpsCommands.kt` is `/astera admin reload <category|all>`. Live view invokes `astera admin reload all`.
