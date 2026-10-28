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
