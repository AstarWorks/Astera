# Understanding before building

Root cause R1 (`fable-session-counterfactual`): we went from a reference or a founder sentence straight to edits,
with no written record of what exists, how many, attached to what. Brief, review and report then talk about
frames, numbers and files.

**Gate.** `.claude/hooks/understanding-gate.py` (PreToolUse on `Agent`, main session only) refuses a brief unless it has
a line `Understanding: <absolute path>` (anywhere in the prompt, after the preamble is fine) and that file has no empty
cell, no TODO/TBD/?? and a `## 分からないこと` section. A line `Kind: read-only` (investigation, review, analysis that
edits nothing) and the review/search agent types are exempt. Founder words quoted as 「…」 need a
`→ これは <row / screen / function> の <what>` line within 3 lines after the quote.

**Use.** The file is the reference for the brief, the review and the report; the reviewer writes its own copy first and diffs.

| Template | For |
|---|---|
| `thing.md` | skill, VFX, model, sound (Limbus skills: the v4 `inventory.md` is this template) |
| `system.md` | code, engine |
| `ui-doc.md` | UI, documents |

Worked example: appendix of the S2 analysis (couture-rapier-s2: 1 hilt, 3 thrown parts, thread, burst; white-out and orange ring not built).
