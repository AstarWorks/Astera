# tools/remote - heavy jobs on the Astera VM (astera-mc)

Claude sessions share one dev host (kaiha2). `make vfx-shot` (Paper server + real Minecraft clients under Xvfb) and heavy
Gradle (`build`, `check`, game-sim, shadowJar) eat ~15 cores there and slow other products' CI 4-10x. So they run on the
Astera VM `astera-mc` (`astera@192.168.14.3`, 8 cores, 15 GB RAM of which ~9 GB belong to the `astera-dev-*` containers).
Sessions, git and editing stay where they are; only the heavy command moves.

```
make vfx-shot SKILL=... | anim-shot | vfx-shot-status|down|reap | build | check | skill-trace | remote-gradle ARGS="..."
   -> tools/remote/run.sh   sync -> run -> pull
```

## How it works

1. **Sync** (local -> VM). Per local checkout the VM keeps `~/astera-heavy/w/<basename>-<sha1(path)[:8]>/repo` (a plain git
   repo). `git push --force` sends HEAD to `refs/heads/heavy-sync`; `rsync --files-from` (list = `git ls-files -c -o
   --exclude-standard`, submodule dirs skipped, `--delete-missing-args`) copies the working tree, so uncommitted edits count;
   files deleted locally disappear remotely; `remote-exec.sh prepare` resets the index to the pushed commit. Ignored files
   (`build/`, `.gradle/`) stay on the VM between runs = warm incremental builds.
2. **Run.** `remote-exec.sh run` takes a **slot** (flock, `HEAVY_SLOTS`, default 1; others queue and print who holds it),
   waits for free memory, then starts `astera/heavy-runner:local` (`docker run --rm`, repo mounted at the same absolute
   path, docker socket mounted so vfx-shot's compose stack runs on the VM's daemon). Caps: `--memory` 4g (vfx and gradle),
   `--cpus 6`, `--oom-score-adj 500`; the shot's server container gets `mem_limit: 3g` + `oom_score_adj: 500`
   (`compose.heavy.yml` via `SHOT_COMPOSE_EXTRA`). Under memory pressure the kernel therefore kills the runner or the shot
   containers, never `astera-dev-*`. Ctrl-C / ssh death stops the runner (pty HUP, or stdin EOF watcher) and sweeps this
   checkout's `astera-shot-*` stacks.
3. **Pull** (VM -> local, also after a failed run). `--pull <path-or-glob>` is rsynced back to the same relative path
   (never `--delete`; `.state/`, `.clients/`, `*.lock` skipped; missing paths are fine).

Env vars named by `--env NAME|PREFIX*` are forwarded (vfx knobs `VIEW CASE TICKS ... SHOT_*`; `SHOT_CACHE/LOCK/REGISTRY/SLOT_DIR`
are host paths and dropped). The remote vfx defaults live in one block in `remote-exec.sh`: `SHOT_KEEP=0`, `SHOT_MIN_FREE_GB=3`,
`SHOT_CLIENT_XMX=1024m` (the caster is a protocol bot, see tools/vfx-shot/README.md), `LP_NUM_THREADS=3`, `SHOT_REAP_SCOPE=host`, `SHOT_COMPOSE_EXTRA` (caller values win except SHOT_KEEP).

`tools/remote/run.sh --dry-run ...` prints the planned git/rsync/ssh commands and the env file without connecting.

## First-time setup

```
make heavy-setup            # == tools/remote/setup.sh astera@192.168.14.3 ; idempotent
make heavy-status           # slots, holders, MemAvailable, shot stacks, image
```
`setup.sh` creates `~/astera-heavy/{w,home,locks,bin,runner-src}` + `heavy.env`, builds the runner image on the VM (no apt on the
host) and pre-pulls `itzg/minecraft-server:java25`, `nginx:1.27-alpine`, `redis:7-alpine`. The first `vfx-shot` downloads the
Minecraft client (~1 GB) into `~/astera-heavy/home/.cache/astera-vfx-shot`; the first Gradle run downloads dependencies into
`~/astera-heavy/home/.gradle` (`make skill-trace` passes `--offline`: run `make build` once first).

## Memory budget

The VM has ~5 GB free beside the `astera-dev-*` containers. One vfx run can ask for: runner <= 4g (MC clients plus the shadowJar
Gradle run, both inside the runner cgroup) + shot server `mem_limit` 3g + pack host / redis 128m each. A full multi-client
`VIEW=all` can therefore exceed free RAM. Protection: vfx-shot's per-start memory guard (`SHOT_MIN_FREE_GB=3`) and
`oom_score_adj 500` on the runner and shot containers, so the kernel kills those before `astera-dev-*`. Prefer single views
(`VIEW=side`) on astera-mc.

## Knobs

| Variable | Default | Meaning |
|---|---|---|
| `ASTERA_HEAVY_HOST` | `astera@192.168.14.3` | ssh target; **set but empty = run locally** (old behaviour); `ASTERA_HEAVY_INSIDE=1` (inside the runner) also runs locally |
| `ASTERA_HEAVY_BASE` | `astera-heavy` | VM base dir (relative to its home) |
| `HEAVY_SLOTS` | 1 (`heavy.env`) | concurrent runner containers on the VM |
| `HEAVY_MEM` | 4g (vfx and gradle) | runner memory cap (swap = same) |
| `HEAVY_CPUS` | 6 | runner cpu cap, also Gradle workers |
| `HEAVY_GRADLE_XMX` | 2g | Gradle JVM heap in the runner |
| `HEAVY_MIN_FREE_GB` | vfx 4 / gradle 3 | wait until the VM has this much MemAvailable |
| `HEAVY_MEM_WAIT` | 600 s | give up waiting for memory |
| `HEAVY_QUEUE_TIMEOUT` | 3600 s | give up waiting for a slot |
| `HEAVY_RUN_TIMEOUT` | 7200 s | hard limit of one run |

All `HEAVY_*` set in the caller's environment are forwarded to the VM.

## Troubleshooting

- **ssh fails** (`[heavy] ssh to ... failed`): `ssh astera@192.168.14.3 true`. Only while the VM is down: `ASTERA_HEAVY_HOST= make <target>` runs it on this machine.
- **docker permission denied / image not found**: `make heavy-setup`; if it says docker is unusable run `sudo usermod -aG docker astera` on the VM and log in again.
- **queued**: another run holds the slot; `make heavy-status` shows who (`caller=user@host:checkout`).
- **memory guard waits / OOM**: the dev containers use ~9 GB; lower `HEAVY_GRADLE_XMX`, or wait. An OOM kills the runner (exit 137), not the dev stack.
- **stale state**: `rm -rf` the checkout's dir under `~/astera-heavy/w/` on the VM (it is rebuilt by the next sync).

## Limits

- No `SHOT_KEEP=1` / `make vfx-shot-up` remotely (the runner is per run); use `ASTERA_HEAVY_HOST= make vfx-shot-up` locally.
- `make vfx-shot-status|down|reap` run in a fresh runner (queue behind a running shot).
- `private/` (submodule) is not synced.
