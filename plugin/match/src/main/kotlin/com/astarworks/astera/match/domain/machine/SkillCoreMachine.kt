package com.astarworks.astera.match.domain.machine

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * A machine that fires a combat skill when its [trigger] condition is met
 * (Wave 5d, P2 minimal).
 *
 * Authored at stage-load time; runtime evaluation lands in Wave 6 once the
 * skill execution pipeline + trigger-condition resolver are wired. Each
 * machine instance references a skill by its raw kebab-case id (matching
 * `combat.domain.skill.SkillId.value`).
 *
 * **Why a raw String, not `SkillId`?** The full dependency cycle currently
 * holds Match ← Combat ← Weapons ← Match (Weapons subscribes to Match's
 * `MatchStarted`), so Match cannot import Combat without breaking gradle.
 * For Wave 5d's domain-only stub the raw id is sufficient — Wave 6 machine
 * activation can introduce a lookup helper that wraps `combat.SkillId`
 * inside `plugin/combat` where the cycle does not apply, or this field can
 * be promoted along with a future `SkillId` move to Core.
 *
 * Future waves may add: cooldown overrides, owner-affiliation gating,
 * multi-skill rotations. Today the type is intentionally narrow so the
 * sealed [TriggerCondition] hierarchy stays the only extension point.
 */
public data class SkillCoreMachine(
    override val position: Vec3,
    val skillId: String,
    val trigger: TriggerCondition,
) : Machine() {
    init {
        require(skillId.matches(Regex("^[a-z][a-z0-9_-]*$"))) {
            "SkillCoreMachine.skillId must be lower-kebab-case (^[a-z][a-z0-9_-]*\$): '$skillId'"
        }
    }
}
