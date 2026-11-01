package com.astarworks.astera.bots

/**
 * When a bot triggers an extra item skill binding. Pure and deterministic (no randomness; the clock is passed in), keeps
 * its own cooldowns, so a game-sim scenario drives the very decision [BotBrain] makes.
 *
 * Triggers map to Bot actions: [Trigger.RIGHT_CLICK] = useItem(yaw, pitch); [Trigger.SNEAK_RIGHT] = sneak(true), useItem,
 * sneak(false) (the sneak input must reach the server before the click); [Trigger.LEFT_CLICK] = attack. The primary
 * attack of a weapon (sword slash, gun shot) is the brain's existing loop; this policy decides the EXTRA skills
 * (sword dash gap-close). Ranges are blocks of 3D distance to the target.
 */
class SkillUsePolicy(private val bindings: List<SkillBinding>) {
    enum class Trigger { RIGHT_CLICK, SNEAK_RIGHT, LEFT_CLICK }

    /** [minRange]..[maxRange] = where casting makes sense; [cooldownMs] = the skill's cooldown (cooldown_ticks * 50). */
    data class SkillBinding(
        val id: String, val trigger: Trigger, val minRange: Double, val maxRange: Double, val cooldownMs: Long,
        val needsLos: Boolean = true,
    )

    private val readyAt = HashMap<String, Long>()

    /** The binding to trigger now (its cooldown starts), or null. First matching binding in list order wins. */
    fun decide(now: Long, dist: Double, hasLos: Boolean): SkillBinding? {
        for (b in bindings) {
            if (now < (readyAt[b.id] ?: 0L)) continue
            if (dist < b.minRange || dist > b.maxRange) continue
            if (b.needsLos && !hasLos) continue
            readyAt[b.id] = now + b.cooldownMs
            return b
        }
        return null
    }

    companion object {
        /** `sword-dash` (sneak + right-click, cooldown_ticks 100): a gap closer, worth it from 6 to 16 blocks. */
        val SWORD_DASH = SkillBinding("sword-dash", Trigger.SNEAK_RIGHT, 6.0, 16.0, 5_000L)

        /** Default extra skills of a kit: melee gets the dash; guns and staffs have only their primary right-click (the fire loop). */
        fun forKind(kind: WeaponKind): SkillUsePolicy = SkillUsePolicy(if (kind == WeaponKind.MELEE) listOf(SWORD_DASH) else emptyList())
    }
}
