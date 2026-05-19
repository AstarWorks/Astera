---
status: Accepted
date: 2026-05-19
deciders: orchestrator (Wave _infra-4)
supersedes: null
superseded_by: null
---

# ADR-0067: `contentLint` Gradle Pipeline

## Status

Accepted

## Context

The repository ships 11 JSON Schemas under `content/schemas/` (one per
content type — weapon, skill, status-effect, stage, item, gui, norma,
generator, anomaly, shop, element-matrix). They are referenced from
IntelliJ's YAML schema mappings so authors get autocomplete and inline
hint, **but nothing executes them**. A drift between a schema and the
content YAML it describes — e.g. an `archetype` enum widening that lands
in Kotlin without the schema getting updated — is invisible until a
plugin's runtime test happens to load the bad YAML and fail with a
`SerializationException` deep in `kaml` decoding.

Adjacent waves narrow this gap from other directions:

- **Wave _infra-1 (ADR-0059)** — `AsteraTestHarness` runs scenario tests
  without Paper, so production YAML can be exercised end-to-end in JVM
  unit tests. Still slow to run per-PR; still triggered only by tests
  that explicitly load each file.
- **Wave _infra-2a (ADR-0060)** — `DataDrivenContents` substrate +
  `YamlDynamicContents.bootstrap()` reloads YAML into typed registries.
  Failures here become operator-visible (server log on `onEnable`), not
  PR-visible.
- **Wave _infra-2b (ADR-0065)** — `/astera reload <category>`
  Brigadier command. Same Paper-side surface; same "operator notices
  after deploy" failure mode.
- **Wave _infra-3 (ADR-0061)** — Studio `/weapons` route renders YAML
  in the studio, but is read-only and never runs the schema.

None of these catch a schema mismatch at PR time. The intent of this
wave is to add the missing **PR-time schema gate**: validate every
`content/<type>/*.yaml` against `content/schemas/<type>.schema.json`
in one Gradle invocation, fail the build on any violation.

A close-by concern is i18n key parity (`en.yaml` ↔ `ja.yaml`).
That is **already** enforced by Konsist
(`i18n keys must match across en and ja language files`) and stays in
Konsist's lane. ADR-0067 carves out schema-only responsibility so the
two checks don't drift toward overlap.

`@ref:` cross-file resolution (`ContentRefResolver`) is **out of scope**
for this wave per the handoff prompt — the resolver is currently
disabled and scope-creep would balloon a single CLI module into a graph
walker. A future ADR can fold ref-resolution into this pipeline when
the resolver lands.

## Decision

1. **New Gradle module `tools/content-lint/`**, a Kotlin JVM CLI:
   - Single entrypoint `com.astarworks.astera.contentlint.MainKt`.
   - CLI flags: `--content-dir <path>` (default `content/`),
     `--schemas-dir <path>` (default `content/schemas/`), `--strict`
     (reserved; currently no warnings — every check is hard pass/fail).
   - Exit 0 on success, exit 1 if any file failed validation, any schema
     is missing, or any YAML failed to parse.

2. **Library choice: `com.networknt:json-schema-validator` 1.5.4**
   plus `org.yaml:snakeyaml` 2.4 and Jackson `databind` 2.18.2 as the
   YAML → `JsonNode` bridge. Rationale:
   - networknt actively maintained, supports JSON Schema **Draft-07**
     (matches every `content/schemas/*.schema.json`'s declared `$schema`)
     as well as 2019-09 / 2020-12 when we migrate later.
   - SnakeYAML is the JVM-canonical YAML 1.1 parser; pairing it with
     Jackson's `ObjectMapper.valueToTree(...)` is the documented bridge.
   - The alternative `everit-org/json-schema` only handles Draft-07 and
     has been quiet for two years.

3. **Not wired into `check`.** The root project adds a
   `./gradlew contentLint` alias (`dependsOn(":tools:content-lint:run")`)
   but does **not** add it to `check.dependsOn`. CI invokes
   `./gradlew check contentLint` in parallel so a content schema breach
   doesn't gate Kotlin unit-test iteration on unrelated code. The two
   tasks have orthogonal failure modes; coupling them would make
   developers wait for an unrelated lint before seeing their compile
   error.

4. **Schema ↔ directory mapping is hard-coded** in
   `SchemaMapping.all` (a `List<SchemaTarget>` in `Main.kt`). One row
   per schema, with an `arrayOfEntries: Boolean` flag for schemas that
   describe a single entry where the on-disk file is an array (today:
   only `generator.schema.json`) and a `singleFile: String?` for
   singleton balance files (today: `element-matrix.schema.json`).
   Auto-discovery (schema filename → directory name) was considered
   and rejected as a Phase 2 follow-up — Phase 1 has 11 schemas and
   no naming convention is yet load-bearing. When a new schema
   lands, the same PR adds a row here; CI fails loudly if not.

## Consequences

### Positive

- **PR-time schema gate.** Schema drift surfaces in the diff that
  caused it, not three commits later when a test happens to load the
  affected YAML.
- **Orthogonal to Konsist.** i18n parity stays in Konsist; schema
  validation lives in `tools/content-lint/`. No double-source-of-truth.
- **Reusable for reload-time hook.** The same `LintRunner` could be
  wired into `IReloadableContentsRegistry` (ADR-0065) so
  `/astera reload <category>` runs the schema check before swapping
  the registry. Not done in this wave.
- **Author-visible feedback.** Validator output is one line per file
  plus a summary; familiar `path: OK` / `path: FAIL\n  - …` format.
- **Konsist scope unchanged.** `tools/content-lint/` is not a Paper
  plugin and not under the sibling-isolation matrix (ADR-0022); the
  new module needs no new Konsist exceptions.

### Negative / Trade-offs

- **`check` non-membership** means CI configuration is the source of
  truth for "is contentLint run on every PR". Forgetting to add
  `contentLint` to the CI command will silently bypass the gate.
- **Hard-coded mapping** means adding a new content type requires
  touching `Main.kt`, not just `content/schemas/`. Mitigated by the
  small surface (one `SchemaTarget(...)` per row) and by Phase 2
  auto-discovery as a known follow-up.
- **No source-line numbers** in violation messages: networknt reports
  JSON-path-style locations (`$.damage.base`), not YAML line numbers.
  Acceptable for Phase 1; future work could fold in SnakeYAML's
  `Mark` API to recover positions.
- **Bumps build classpath** with three additional libraries
  (networknt validator, SnakeYAML, Jackson). All transitive of typical
  Java tooling and only used in `:tools:content-lint`.

### Mitigations

- CI workflow updates land in the same wave as a new content type
  (caught at review by the per-wave `astera-reviewer` subagent).
- `Main.kt` keeps the mapping table at file top so new entries are
  obvious in PR diffs.

## Alternatives Considered

- **Konsist rule "every content YAML parses against its schema".**
  Rejected: Konsist is structured around Kotlin source AST, not
  arbitrary JSON-Schema validation; embedding a JSON-Schema engine
  into the architecture-test module muddies its single responsibility
  (sibling-isolation enforcement).
- **Custom Gradle convention plugin under `buildSrc/`.** Rejected: the
  validator needs a real runtime dependency on networknt; a convention
  plugin would either pull networknt into every module's buildscript
  classpath or duplicate the launcher logic. A dedicated `application`
  module is cleaner.
- **Inline into existing `:tools:schema-gen`.** Rejected: schema-gen
  *writes* schemas from `@Serializable` DTOs; content-lint *reads*
  them and validates content. Different lifecycles (schema-gen runs
  on demand at content/schema authoring; content-lint runs on every
  PR), different dependency sets.
- **`everit-org/json-schema`.** Rejected: Draft-07-only, lower
  maintenance velocity; networknt's broader spec support gives runway
  if we migrate any schema to 2020-12.

## References

- Adjacent ADRs: [[adr/0016-content-schema-policy]] (schema SSoT
  intent), [[adr/0017-adopt-datadriven-contents-pattern]] (substrate
  origin), [[adr/0060-datadriven-contents-implementation]]
  (`DataDrivenContents` substrate from Wave _infra-2a),
  [[adr/0065-per-category-reload-dispatcher]] (future reload-time
  hook target).
- Module: `tools/content-lint/` (this wave).
- Schemas validated: `content/schemas/*.schema.json` (11 files).
- Konsist i18n parity rule:
  `tools/architecture-test/src/test/kotlin/.../ArchitectureTest.kt`
  → `"i18n keys must match across en and ja language files"`.
- External: <https://github.com/networknt/json-schema-validator>.
