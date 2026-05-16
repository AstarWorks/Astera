# GitHub Actions workflows

Two workflows guard the main branch and produce release artifacts.

## `ci.yml` — Build & test

**Triggers**: every PR targeting `main`, and every push to `main`.

**What it does**:
- Sets up JDK 25 (Temurin) and the Gradle wrapper (9.5.1).
- Runs `./gradlew check` — unit tests across all modules **plus** `:tools:architecture-test`
  (Konsist) which enforces the layered Clean Architecture rules.
- On push to `main`: builds `:plugin:platform-paper-plugin:shadowJar` and uploads
  the fat jar as the `astera-paper-plugin` artifact (30-day retention) for the
  deploy / image pipeline to consume.

**Concurrency**: in-progress PR runs are cancelled on new pushes; `main` runs are not cancelled.

## `image.yml` — Container image

**Triggers**: push to `main`, and published GitHub releases (semver tags).

**What it does**:
- Builds `docker/Dockerfile` from the repo root (multi-stage; gradle build runs inside).
- Pushes to `ghcr.io/astarworks/astera` with tags produced by
  `docker/metadata-action`: branch name, short SHA, `latest` on main, and full
  semver on release tags.
- Phase 1: `linux/amd64` only. arm64 will be added once the itzg base image and
  JDK 25 toolchain are confirmed on ARM (see [ADR-0007](../../docs/adr/0007-itzg-mc-server-base-image.md)).

## Debugging failures

1. Open the failed workflow run in the Actions tab.
2. **Test failures**: the `test-reports` artifact (attached on failure) contains
   `build/reports/tests/` HTML and `build/test-results/` XML for every module.
3. **Konsist (architecture) failures**: look under
   `tools/architecture-test/build/reports/tests/` in the artifact — the failing
   rule names map 1:1 to files in `tools/architecture-test/src/test/kotlin/`.
4. **Image build failures**: check the `Build and push` step log for buildx output.
   GHA cache lives under `type=gha`; a force-clean is to bump the cache scope.
