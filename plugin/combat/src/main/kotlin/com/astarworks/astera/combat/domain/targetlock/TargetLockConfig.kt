package com.astarworks.astera.combat.domain.targetlock

/**
 * Tuning knobs for ranged target-lock acquisition (Wave 5combat-depth,
 * ADR-0105).
 *
 * Target lock is the "lock-on" mechanic for bow / gun / scope weapons:
 * after the player aims at an entity for [acquisitionTicks] continuous
 * server ticks, the entity becomes locked. Locked targets gain HUD
 * highlight (Wave 5j path; lit up on this PoC via `ITargetLockController`
 * events) and the next ranged hit lands the [damageBonusMultiplier].
 *
 * # Why a single-target lock, not multi-target
 *
 * Astera's combat philosophy keeps ranged play deliberate. Multi-target
 * lock turns the bow / gun into an aimbot — the very thing competitive
 * integrity must avoid. Per ADR-0105, single-target lock is the only
 * shape that ships in Phase 1; multi-target is a deferred experiment.
 *
 * @property acquisitionTicks Continuous ticks the player must aim at the
 *   same entity before lock acquires. Default 10 ticks (~0.5 s) so a
 *   trained player can snap-lock but a flick can't.
 * @property maxDistanceBlocks Maximum acquisition distance in blocks.
 *   Default 50 — covers Annihilation engagement ranges without scoping
 *   the whole map.
 * @property maxAngleDegrees Maximum angular deviation between the
 *   player's look vector and the target direction (in degrees) for the
 *   acquisition tick to count. Default 5° — tight enough to require
 *   aim, loose enough that target movement doesn't drop the lock on
 *   every frame.
 * @property lockLifetimeTicks How long the lock survives after
 *   acquisition without re-aiming. Default 40 ticks (~2 s).
 * @property damageBonusMultiplier Multiplier on the **first** ranged hit
 *   landed on the locked entity. Default 1.25 — a small reward, not a
 *   one-shot kit.
 */
public data class TargetLockConfig(
    public val acquisitionTicks: Int = DEFAULT_ACQUISITION_TICKS,
    public val maxDistanceBlocks: Double = DEFAULT_MAX_DISTANCE_BLOCKS,
    public val maxAngleDegrees: Double = DEFAULT_MAX_ANGLE_DEGREES,
    public val lockLifetimeTicks: Int = DEFAULT_LOCK_LIFETIME_TICKS,
    public val damageBonusMultiplier: Double = DEFAULT_DAMAGE_BONUS_MULTIPLIER,
) {
    init {
        require(acquisitionTicks > 0) {
            "TargetLockConfig.acquisitionTicks must be > 0: $acquisitionTicks"
        }
        require(maxDistanceBlocks > 0.0 && maxDistanceBlocks.isFinite()) {
            "TargetLockConfig.maxDistanceBlocks must be finite and > 0: $maxDistanceBlocks"
        }
        require(maxAngleDegrees > 0.0 && maxAngleDegrees <= 180.0) {
            "TargetLockConfig.maxAngleDegrees must be in (0, 180]: $maxAngleDegrees"
        }
        require(lockLifetimeTicks > 0) {
            "TargetLockConfig.lockLifetimeTicks must be > 0: $lockLifetimeTicks"
        }
        require(damageBonusMultiplier.isFinite()) {
            "TargetLockConfig.damageBonusMultiplier must be finite: $damageBonusMultiplier"
        }
        require(damageBonusMultiplier in 1.0..MAX_DAMAGE_BONUS_MULTIPLIER) {
            "TargetLockConfig.damageBonusMultiplier must be in [1.0, $MAX_DAMAGE_BONUS_MULTIPLIER]: $damageBonusMultiplier"
        }
    }

    public companion object {
        public const val DEFAULT_ACQUISITION_TICKS: Int = 10
        public const val DEFAULT_MAX_DISTANCE_BLOCKS: Double = 50.0
        public const val DEFAULT_MAX_ANGLE_DEGREES: Double = 5.0
        public const val DEFAULT_LOCK_LIFETIME_TICKS: Int = 40
        public const val DEFAULT_DAMAGE_BONUS_MULTIPLIER: Double = 1.25

        /** Cap on damageBonusMultiplier to preserve competitive integrity. */
        public const val MAX_DAMAGE_BONUS_MULTIPLIER: Double = 2.0

        public val DEFAULT: TargetLockConfig = TargetLockConfig()
    }
}
