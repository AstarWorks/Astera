# Vanilla parity (SKL-17)

What a shipped skill hands to vanilla Bukkit (display spawns, per-tick writes, removals, `spawnParticle` arguments) is the
founder-visible truth. `VanillaParityScenarioTest` pins it per skill and tick against
`tools/game-sim/src/test/resources/vanilla-parity/golden.txt` (`skill<TAB>tick<TAB>hash of that tick's events`).

## Workflow

- Dump: `ASTERA_VANILLA_DUMP_DIR=<dir> ./gradlew :tools:game-sim:test --tests '*VanillaParityScenarioTest*'` writes one JSONL per skill.
- Compare with a reference dump: `python3 tools/skill-convert/vanilla_diff.py <v1-dir> <head-dir> --out report.md`
  (per skill: first divergent tick and field, grouped by root cause).
- Accept a deliberate change: `ASTERA_VANILLA_GOLDEN_OUT=<abs path of golden.txt>`, then review the golden diff in the commit.
- The v1 reference (`d41b82f1e`) is produced by a recorder patch plus a dump test in `tools/skill-convert/v1-dumper/`
  (apply the patch to a worktree of that commit, add the test as `V1VanillaDumpTest.kt`, run `run-v1dump.sh`).

## State of the golden

The golden pins HEAD as it is. HEAD does not yet equal v1; the open differences are listed in
`audit/vanilla-diff.md` of the SKL-17 report and come from presentation features the v2 schema cannot express yet
(`CONVERT-PRESENT` in `content/skills/*/look.yaml`): skin/body rig, block / quad / text elements, formation / count,
brightness, orient, `follow: false`, particle animation ring, per-skill rig scale. Closing one of them changes the golden
on purpose; that diff is the evidence.

## Gameplay parity (what a skill DOES)

The same scheme for gameplay: `GameplayParityScenarioTest` pins, per skill and tick, sounds, damage, knockback, velocity and
movement of caster and dummy, statuses, block edits, cast phases, cooldown and cost in a fixed scene (caster at the origin,
dummy 5 blocks ahead, wall 8 blocks ahead) against `tools/game-sim/src/test/resources/gameplay-parity/golden.txt`. This golden is
only a regression pin of HEAD as of its commit, NOT an agreed target.

- Dump: `ASTERA_GAMEPLAY_DUMP_DIR=<dir>`; accept a change: `ASTERA_GAMEPLAY_GOLDEN_OUT=<abs path>`.
- v1 reference: `tools/skill-convert/v1-dumper/V1GameplayDumpTest.kt.txt` (add to a `d41b82f1e` worktree) and `run-v1gameplay.sh`.
- Compare: `python3 tools/skill-convert/gameplay_diff.py <v1-dir> <head-dir> --out gameplay-diff.md` (groups by cause, counts, first
  divergent tick, worst examples). Harness limits are listed in the report header (plain-hit knockback and ego gain happen inside
  the combat use case; v1 projectiles are entities, HEAD's are frames).
