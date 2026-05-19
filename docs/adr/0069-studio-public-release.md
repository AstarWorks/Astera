---
status: Accepted
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0069: Promote Studio to a publicly-released OSS tool

## Status

Accepted

## Context

[[adr/0066-promote-studio-to-mainline]] (2026-05-19) promoted
`tools/studio/web/` from "carve-out / not-tracked" to "Astera mainline,
git-tracked". That decision intentionally stopped at the Astera repo
boundary — "mainline" meant "tracked in Astera's git", and external
distribution was never discussed.

Two things became true after ADR-0066 shipped:

1. **The Astera-specific coupling in Studio is actually shallow.** The
   `/textures`, `/models`, `/effects` routes are generic over any project
   that follows a `content/<type>/` layout convention. The `/weapons`
   route is the single ASTERA-ONLY surface (177-line backend + 2 Vue
   views) — and it works as a *reference implementation* of "how to add
   a project-specific content viewer". All other Astera-flavored bits
   (paths in `paths.ts`, texture-generation prompts in `generate.ts` and
   `effects.ts`, manifest path conventions) are parameterizable via
   env variables with a one-line refactor.

2. **There is intrinsic value in showing Studio externally.** Astera
   positions itself as a "100% AI-developed Minecraft Paper server"
   (per the repo CLAUDE.md). Studio is one of the few visible, hands-on
   parts of the Astera engineering practice that an external observer
   can pick up, run, and immediately feel — unlike, say, the Konsist
   architecture tests or the Wave-execution protocol. Releasing it
   publicly is a low-effort showcase that materially lowers the barrier
   for outside contributors and collaborators to engage with Astera.

The cost of going public is correspondingly low: one `LICENSE` file, a
rewritten README with a dual-audience structure, env-driven config
overrides, and `package.json` metadata fields. No npm publish step is
included in this ADR — the goal here is to make the codebase
**publishable**, not to publish it. Actual `gh repo create` / `npm
publish` is a downstream user action.

This ADR explicitly does **not** alter Astera's own runtime/build
invariants: Studio remains a separate Bun process, never imported by
`:plugin:*`, never invoked by `./gradlew check`. The mainline ADR-0066
and the underlying ADR-0018 carve-out both stand. This ADR adds an
external-publication track on top of ADR-0066's internal-mainline
status.

## Decision

Release the contents of `tools/studio/web/` under the MIT license,
without splitting Astera-specific functionality into a separate package
or repo. Concretely:

1. **License**: add `tools/studio/web/LICENSE` with MIT text.
2. **package.json metadata**: set `license: "MIT"`, add
   `author` / `repository` / `homepage` / `keywords` fields, drop
   `"private": true`.
3. **README**: rewrite for dual audiences. The canonical user is still
   Astera, but every section that references Astera paths or schemas is
   marked as "Astera integration notes" so an external user can grok
   the rest of the doc. Configuration via env variables is documented
   first-class. The "Extending" section calls out `/weapons` as a
   reference implementation external users can copy when adding their
   own content-preview routes.
4. **`paths.ts` env overrides**: every hardcoded
   `path.join(repoRoot, 'content', '...')` becomes
   `process.env.STUDIO_<TYPE>_DIR ?? path.join(repoRoot, 'content/<type>')`.
   `STUDIO_REPO_ROOT` overrides the auto-detected git repo root, letting
   external users point Studio at any project layout without forking.
5. **Prompt presets parameterizable**: the Astera house-style texture
   prompt (`generate.ts`) and effects prompt (`effects.ts`) move into
   `server/prompt-presets.ts` as templates. External users override via
   `STUDIO_TEXTURE_PROMPT_FILE` / `STUDIO_EFFECTS_PROMPT_FILE` pointing
   at plain-text files with `${var}` substitution.
6. **`/weapons` stays in the public release.** It serves as a working
   example of Astera-specific extension. External users will encounter
   it as "this is how you write a project-specific YAML preview route
   for your own content"; they can fork or delete it as they prefer.

No npm publish, no GitHub repo creation, no CI publish workflow is part
of this ADR. The codebase is made *publishable*; pulling the trigger
is a separate user action.

## Consequences

### Positive

- **Community access**: external users can run Studio against their own
  Minecraft projects without forking, by setting `STUDIO_REPO_ROOT` and
  the content-dir overrides.
- **Astera as showcase**: external observers get a concrete, hands-on
  artifact of Astera's "100% AI-developed" engineering practice, lower
  barrier to engagement than reading ADRs.
- **No split tax**: a single codebase to maintain. No drift between
  "public" and "Astera-internal" versions, no duplicated tests.
- **`/weapons` as reference doc**: external users see a complete
  example (177-line backend, ~250-line Vue view) of how to extend
  Studio with a project-specific content viewer.

### Negative / Trade-offs

- **OSS license obligations**: MIT is permissive but Astera contributors
  must now be aware that the studio code is freely usable in commercial
  contexts; nothing in here should depend on private credentials or
  unreleased ADR text.
- **Version discipline**: `package.json` version becomes meaningful for
  external consumers. Bumping rules need to be documented (likely
  SemVer once we publish, but this ADR defers that).
- **Astera-specific defaults**: an external user who doesn't override
  any env variable gets Astera's `content/textures/`, `content/weapons/`,
  etc. paths. If they have an empty `content/` dir, Studio boots
  with empty lists everywhere — confusing but not broken. The README
  preemptively addresses this in the "Quick start" / "Use it on your
  own project" sections.
- **Prompt assumption leakage**: even with `STUDIO_TEXTURE_PROMPT_FILE`
  overridable, the default text bakes in Astera house style (32×32
  pixel-art, LL→UR weapon diagonal). An external user who skips the
  prompt-override config gets Astera-flavored outputs — visible and
  fixable, not silent.

### Mitigations

- README explicitly distinguishes "Astera integration notes" from
  general usage so external users can navigate the doc.
- Every env variable used by `paths.ts` is documented in a table in
  the README.
- `STUDIO_TEXTURE_PROMPT_FILE` / `STUDIO_EFFECTS_PROMPT_FILE` allow
  full prompt replacement, not just suffix appending — external users
  can completely remove Astera-isms from the prompt.
- ADR-0066's invariant (Studio is never a Gradle / runtime dependency
  of Astera plugins) is reaffirmed; the public release does not
  expand the Astera plugin's build graph.

## Alternatives Considered

- **Split into `studio-core/` (public) + `studio-astera/` (internal).**
  Rejected. Splitting requires designing a plugin/extension API for
  routes, restructuring the bun workspace into two packages,
  refactoring shared types and components, and maintaining two READMEs.
  The Astera-specific surface area is one route — the cost is
  disproportionate. Keeping `/weapons` in the public release as a
  reference extension is simpler and arguably more useful to external
  users.
- **Drop `/weapons` from the public release.** Rejected. The route is
  the single best concrete example of how to extend Studio for a
  project. Removing it would force external users to invent the same
  extension pattern from scratch, undermining one of the value props
  of going public ("here is how Astera uses Studio, copy it for your
  project").
- **Build a `STUDIO_PROFILE=astera|public` flag system.** Rejected.
  Profiles introduce dead-code paths in the bundle, complicate testing
  (every test has to run under each profile), and don't materially
  reduce the coupling — the underlying issue (Astera schema baked in)
  is already addressed by env overrides + the `/weapons` reference-doc
  framing.
- **Defer the public release until model fallback (ADR-0061
  Mismatch A) and i18n (Mismatch B) are fixed.** Rejected. i18n is
  fixed in the same wave as this ADR (a `/api/languages` route +
  `<I18nText>` component, Astera-content-independent). The model
  fallback (pistol/wand showing sword) is a content-asset issue, not
  a code issue, and external users won't have the same fallback chain
  — they'll have their own archetypes. The fallback is documented as a
  known limitation and the release can ship without it.

## References

- [[adr/0066-promote-studio-to-mainline]] — internal-mainline status
  preceding this ADR
- [[adr/0061-studio-weapons-preview-route]] — `/weapons` rationale and
  known mismatches; treated here as reference implementation
- [[adr/0018-ai-asset-creation-policy]] — texture-gen carve-out;
  preserved unchanged
- [[adr/0010-postpone-generative-ai-to-phase-6]] — runtime/build
  invariant; preserved unchanged
- `tools/studio/web/LICENSE` — MIT text
- `tools/studio/web/README.md` — dual-audience rewrite
- `tools/studio/web/server/paths.ts` — env override implementation
- `tools/studio/web/server/prompt-presets.ts` — prompt parameterization
