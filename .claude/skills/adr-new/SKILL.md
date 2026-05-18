---
name: adr-new
description: Scaffold a new ADR file under docs/adr/ from the template, with Status=Proposed and the right number. Use when the user says "ADR 起票", "new ADR", "create an ADR for X", or invokes /adr-new. Writes one new file; the orchestrator fills the body.
allowed-tools: Read, Write, Bash, Glob
disable-model-invocation: true
---

# adr-new — Scaffold a new ADR

Create a new `docs/adr/00NN-<slug>.md` from `docs/adr/0000-template.md`. The
orchestrator (you) fills the body afterwards; this skill only produces the
skeleton with the right number, slug, frontmatter, and Status=Proposed.

This skill is **not** auto-invoked (`disable-model-invocation: true`) — it
writes a file, which should be a deliberate action. The user or orchestrator
must invoke it explicitly via `/adr-new` or by asking for an ADR scaffold.

## Inputs

The caller should supply (ask via AskUserQuestion if any is missing):

- **`number`** — the ADR number, zero-padded to 4 digits (e.g., `0031`).
  - If blank: look up `IMPLEMENTATION-ROADMAP.md §"ADR allocation per wave"`
    for the current wave. If still unclear: `ls docs/adr/00*.md | tail` to
    find the next free number, accounting for known gaps (e.g., 0023, 0024,
    0026 are intentionally skipped).
- **`title`** — short title (e.g., `"Adopt LiteCore for projectile sim"`).
- **`slug`** — kebab-case slug for the filename (e.g.,
  `adopt-litecore-projectile-sim`). If blank, derive from `title`.
- **`wave_context`** — which wave this ADR belongs to (e.g., `Wave 5b`).
  This goes into the Context section as a one-line note; the orchestrator
  expands it.

## Workflow

1. Verify `docs/adr/00<number>-*.md` does not already exist.
   `ls docs/adr/00<number>-*.md` — if a file is returned, **stop** and ask
   the user whether this is a duplicate.
2. Read `docs/adr/0000-template.md` to confirm structure (frontmatter +
   sections).
3. Construct the new file at `docs/adr/00<number>-<slug>.md` with:
   - Frontmatter:
     ```
     ---
     status: Proposed
     date: <today, YYYY-MM-DD>
     deciders: ryuzu
     supersedes: null
     superseded_by: null
     ---
     ```
   - Title: `# ADR-<number>: <Title>`
   - Section headers as in the template: `## Status` (write `Proposed`),
     `## Context`, `## Decision`, `## Consequences` (with `### Positive`,
     `### Negative / Trade-offs`, `### Mitigations`),
     `## Alternatives Considered`, `## References`.
   - In `## Context`, insert a single placeholder line:
     `<!-- TODO: <wave_context> — fill in motivation, constraints, decision drivers -->`
   - Other sections: keep the template's prompting prose so the orchestrator
     knows what to write.
4. Write the file.
5. Report the path and remind the orchestrator:
   - The Status stays `Proposed` until `astera-reviewer` clears the wave.
   - Update `docs/INDEX.md` ADR-count line if the number bumps the total.
   - Do NOT edit a merged Accepted ADR — supersede with a new ADR instead.

## Output format

```
## adr-new scaffolded

**File:** docs/adr/00<number>-<slug>.md
**Status:** Proposed
**Next steps for orchestrator:**
1. Fill ## Context with motivation + constraints.
2. Fill ## Decision with the decision in one paragraph.
3. Fill ## Consequences (Positive / Negative / Mitigations).
4. Fill ## Alternatives Considered with at least 2 alternatives.
5. Cross-link related ADRs in ## References.
6. Update docs/INDEX.md ADR-count if total changed.
7. Leave Status=Proposed; astera-reviewer flips to Accepted at Phase C close.
```

## Notes

- The template (`docs/adr/0000-template.md`) is the SSOT for ADR structure.
  Do not invent fields the template doesn't have.
- ADR numbers have **intentional gaps** (currently 0023, 0024, 0026 are
  skipped). Respect the roadmap's pre-allocated number; do not "fill" gaps
  unless the roadmap explicitly reuses them.
- The orchestrator (not this skill) writes the body, runs the review, and
  flips Status to Accepted at the wave commit.
