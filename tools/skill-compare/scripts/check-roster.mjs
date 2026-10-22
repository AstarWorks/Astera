// Run from any directory: bun tools/skill-compare/scripts/check-roster.mjs
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { personaWeapons, SLOTS } from "../lib/weapons.mjs";

const repo = fileURLToPath(new URL("../../../", import.meta.url));
const weapons = personaWeapons(repo);
assert.equal(weapons.length, 10, "All ADR-0230 weapons must appear even without YAML comments");
for (const weapon of weapons) {
  assert.equal(weapon.slots.filter((s) => s.trigger === "drop").length, 1, `${weapon.id}: missing Q slot`);
}
assert.equal(SLOTS.find(([trigger]) => trigger === "sneak_drop")[1], "スニーク+Q");
console.log("10 weapons, Q bindings and Shift+Q labels: OK");
