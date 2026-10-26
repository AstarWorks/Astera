# Brief: animation (limbus-animator) — bastion-greatsword-s3 r1

Goal: body + wrist rig for S3 in the S2 manner. Write `tools/pose-solve/author_bastion_s3.py` (import `author_bastion_s1 as A` like `author_bastion_s2.py`; read that file first) that writes
`content/animations/bastion-s3-body.yaml` and `content/vfx/bastion-s3-rig.yaml` (id `bastion-s3-rig`, body `bastion-s3-body`, `follow: true`, `follow_pitch: true`, copy the header of bastion-s2-rig.yaml). Run it with `python3 tools/pose-solve/author_bastion_s3.py`. Do not touch other files, do not commit, never run gradle.

Reference of truth: `tools/skill-compare/data/bastion-greatsword-s3/beat-sheet/beat-sheet.md` (gate 1 passed; founder: slam goes OVERHEAD then forward-down = screen CW; slam damage t63; dash ~5.4 blocks hard stop). Frames: `tools/skill-compare/cache/bastion-greatsword-s3/frames/frame_NNNNN.jpg` (n = tick*1.5). Look at them for the poses; do not guess.

Beats (skill ticks; hit-stop freezes at contacts 10 (2), 40 (2), 63 (3); DURATION 96):
- t0-9 coil: crouched, sword lying low behind, tip near floor behind-left of her (blade flat), slight yaw turn away (+40) by t9.
- t9-10 ring 1: blade swings low behind -> front -> right -> back-up over the shoulder, TOP VIEW CW, contact t10 (fast, 1-2 ticks of travel; pose at t9 = back-low, at t10 = already up-back, tip ~(-1.2, 0.3, 2.8)), body turns from back-view to facing the compare camera (yaw +40 -> -70, CW).
- t11-22 hold: blade up over the shoulder (45-60 deg), body yaw -70 -> -30 slowly back toward the enemy.
- t22-24 lower: blade down to back-low (floor), yaw -> 0, deep crouch.
- t24-37 low hold: deep crouch facing the enemy, blade tip on the floor behind (scrape), almost no motion (a few ticks of subtle drag/lean ok).
- t37-39 dash: blade comes up back-low -> forward (screen CCW) while she lunges; body upright profile; contact t40.
- t40-50 thrust hold: deep lunge, blade horizontal pointing at the enemy at ~1.4 up, tip (2.1, 0, 1.4), held.
- t50-51 raise: ONE-tick raise to overhead: fist overhead (z~2.2), blade back-down tip (-1.5, 0, 1.0), body turns to the compare camera (yaw -90). Same overhead pose as bastion-s2 `raise-hold` (reuse that beat and the S2 `chop-*` beats: slam = S2 chop).
- t51-62 overhead hold facing the camera (anticipation; keep it alive: tiny breathing/lean).
- t62-63 slam: overhead top (tip up ~3.2) -> contact t63 blade forward-down into the floor tip (1.4, 0, 0), yaw back to 0 (CCW body turn). Screen CW blade path.
- t63-75 plant, then kneel beside the upright planted sword with a hand on the hilt to t95 (hand-back step at the end like S2).

Quality bar: strong anticipation, 1-2 tick main motions, apex holds, edge leads on cuts (see S2 `spec_of`), head follows body (head bone yaw/pitch looks where she looks: enemy, or compare camera on the raise/hold). Reuse S1/S2 `cut(...)` beats where the blade path matches (ring path, chop). Rejected looks: blocky single-colour props, wrong grip, weak swings. Report: table of beats with tick -> rig tick, the th (tip azimuth) CW/CCW printout for ring 1 / dash / slam, and any beat you could not match. The final judge is the real client / preview.
