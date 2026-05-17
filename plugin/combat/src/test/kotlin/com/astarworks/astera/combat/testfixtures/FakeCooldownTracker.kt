package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.application.port.outbound.ICooldownTracker
import com.astarworks.astera.weapons.domain.weapon.WeaponId

/**
 * In-memory [ICooldownTracker] for tests.
 *
 * Set [nextIsCoolingDown] to control [isOnCooldown] return value.
 * All [start] calls are recorded in [started] for assertion.
 *
 * NOTE: [ICooldownTracker] is keyed on [WeaponId] (weapons plugin). Skill-level
 * cooldowns in [com.astarworks.astera.combat.application.usecase.skill.CastSkillUseCase]
 * are tracked independently; this fake is provided for integration tests or
 * future use cases that accept [ICooldownTracker] directly.
 */
public class FakeCooldownTracker : ICooldownTracker {

    public var nextIsCoolingDown: Boolean = false

    /** Records of (playerId, weaponId, durationTicks) passed to [start]. */
    public val started: MutableList<Triple<PlayerId, WeaponId, Int>> = mutableListOf()

    override fun isOnCooldown(playerId: PlayerId, weaponId: WeaponId): Boolean = nextIsCoolingDown

    override fun remainingTicks(playerId: PlayerId, weaponId: WeaponId): Long = if (nextIsCoolingDown) 20L else 0L

    override fun start(playerId: PlayerId, weaponId: WeaponId, durationTicks: Int) {
        started += Triple(playerId, weaponId, durationTicks)
    }
}
