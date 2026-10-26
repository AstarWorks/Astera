# Folia E2E lane (T14)

The Paper lane remains the production baseline. The Folia lane uses the same
compose profile, proxy, resource pack, protocol clients and full E2E suite,
plus `FoliaConcurrencyE2eTest`. See ADR-0252.

## Run

Use the same external lock as the Paper lane; the Make target does not acquire
a second lock internally:

```sh
flock /tmp/claude-1000/astera-e2e.lock make e2e-folia
# Optional sizing:
E2E_CONCURRENT_MATCHES=8 E2E_FOLIA_THREADS=4 \
  flock /tmp/claude-1000/astera-e2e.lock make e2e-folia
```

Defaults: four concurrent matches, four region threads, Velocity forwarding.
`E2E_ONLY=FoliaConcurrencyE2eTest` narrows the suite for diagnosis; the default
runs the existing suites too. Existing `E2E_KEEP`, `E2E_REUSE`, `E2E_VIA` and
load controls still apply.

The project is `astera-e2e-folia<checkout-id>`, with separate image tags,
volumes, runner and stamp from Paper's `astera-e2e-<checkout-id>`.
Reports/logs are `build/e2e-folia-report.md`,
`build/e2e-folia-boot.log`, `build/e2e-folia-server.log` and
`build/e2e-folia-velocity.log`. JUnit XML is copied into
`build/e2e-folia-junit/` when a runner produces it.
Teardown a kept Folia project with `SERVER_TYPE=FOLIA make e2e-down`.
The lane never operates on `astera-dev-*`.

Fresh-volume startup seeds `paper-global.yml` before itzg applies the Velocity
patch, explicitly setting `threaded-regions.threads` and `grid-exponent: 4`.
This avoids the first-boot patch omission recorded in the boot spike.

## Concurrency assertions

The test images generate N independent compact skirmish stages/modes/queues;
repository content is unchanged. Every lane holds exactly two players. All
join commands are issued before waiting for any match; all N must be ACTIVE
at the same observation and have distinct slot origins.

A separate bot stays in the lobby and must still receive command replies.
The test-only `FoliaE2eProbe` runs location snapshots on Entity schedulers.
Each player's owner checks `Bukkit.isOwnedByCurrentRegion` for every sampled
location. A match must own exactly its two participants and the lobby must own
only itself. This tests actual ownership rather than inferring regions from
slot distance or worker names. Chunk coordinates and thread names are logged
in the report; Folia may move regions between worker threads.

The participants deliberately remain stationary, with no generators in their
stage copies. The 45-second timer must end every match naturally with a tied Norma reason (a draw), zero scores for both teams, one summary per participant,
and identical scores seen by the pair. Admin termination is cleanup only and
cannot satisfy the result checks. This is concurrent lifecycle coverage, not
AI combat coverage.

Both the concurrency test and the final whole-server-log gate reject
`Thread failed main thread check`, `not owned by current region` and
ownership/thread/region `IllegalStateException` messages. A missing probe
sample fails the region assertion; it cannot silently pass as isolation.

## Recorded attempt — 2026-10-26

Command (invoked exactly once):

```sh
flock /tmp/claude-1000/astera-e2e.lock make e2e-folia
```

Project: `astera-e2e-folia656c1725`. Defaults: N=4, region threads=4.
**0 passed / 0 checks executed; harness exit 2.**
The backend image build failed before any Minecraft server or bot started.
Consequently there is no player-visible pass/total or measured region
separation. The 0/0 figure is an unexecuted suite, not a pass.

Local evidence: `build/e2e-folia-report.md` and
`build/e2e-folia-boot.log`. The server log is empty because no server started.
No second E2E invocation was made, respecting the one-run instruction.

### Failures grouped by cause

| Cause / owner | Observed result | Evidence / next action |
|---|---|---|
| Scoreboard API → T9 | Not reached; no runtime verdict | No players joined. Do not interpret this as scoreboard safety. |
| Blocking JDBC → T10 | Not reached; no runtime verdict | This reused harness has in-memory repositories, with no Postgres URL. A later successful run would still not verify JDBC latency/thread safety. |
| Citizens → T12 | Not reached; no runtime verdict | No NPC/player interaction occurred; the E2E image does not install Citizens. |
| Other: harness build context → T14 | Backend build failed | `docker/Dockerfile:81` copies `docker/e2e-server-start.sh`, but `docker/Dockerfile.dockerignore:25` excluded `docker/*`. BuildKit reported the source path missing. Added the explicit exception at `docker/Dockerfile.dockerignore:30`. |

No product code was fixed. The build-context defect was corrected after the
single attempt. A complete Folia run after that correction remains required
before this task can claim gameplay verification or classify product failures.

### Verification

- `./gradlew :tools:e2e:compileTestKotlin :tools:e2e:foliaProbeJar --console=plain`: passed (compile/package only).
- Bash/POSIX shell syntax, compose config validation and `git diff --check`: passed.
- Post-correction Folia backend image build: passed; image digest
  `sha256:3c001d217ac8c9b9084cf2326f8afcabb1cff2757916ad9758e386bc4e378279`.
  This was an image build only; no server or E2E suite was started.
- No unit tests or `check` tasks were run.
