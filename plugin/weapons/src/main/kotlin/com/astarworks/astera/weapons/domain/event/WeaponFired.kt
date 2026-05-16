package com.astarworks.astera.weapons.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.domain.weapon.WeaponId

/**
 * Emitted by `FireWeaponUseCase` when a weapon's fire action resolves
 * successfully (cooldown OK, weapon known). Subscribers in `:plugin:combat`
 * apply damage; subscribers in `:plugin:stats` accumulate fire counts; etc.
 *
 * ADR-0022 Phase 3: extracted from `DomainEventVariants.kt` into Weapons.
 * Remaining Phase 2 variants (DamageDealt / PlayerKilled / SkillCast /
 * StatusApplied / MatchStarted / MatchEnded / PhaseChanged / CurrencyChanged /
 * XpGranted) move to their owning feature plugins in Phase 4.
 */
public data class WeaponFired(
    val playerId: PlayerId,
    val weaponId: WeaponId,
    val at: Vec3,
) : DomainEvent
