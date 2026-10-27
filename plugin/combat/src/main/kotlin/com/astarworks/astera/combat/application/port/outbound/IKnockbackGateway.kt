package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.damage.KnockbackPush
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound: apply an impulse (knockback) vector to a player.
 *
 * Separate from `IWorldGateway` because knockback is a combat-physics
 * concept; the world gateway handles world geometry / effects / teleport.
 * Separate from `IDamageGateway` so tests can verify knockback application
 * without coupling damage-gateway assertions to physics state.
 *
 * Wave 6 supplies `PaperKnockbackGateway` (sets `Player.velocity`). Wave 5a
 * uses `NoOpKnockbackGateway` (logs only) as the default binding when no
 * runtime impl is registered.
 *
 * # Knockback resistance contract (ADR-0136 / Wave S 5knockback-resistance)
 *
 * Implementations MUST consult the target's effective knockback resistance
 * (0..1 fraction) and scale the impulse before applying. The domain
 * pipeline ([com.astarworks.astera.combat.domain.rule.KnockbackModifier])
 * computes the **base** impulse only; resistance lives at the gateway
 * boundary because resistance sources can differ per adapter:
 *
 *  - `PaperKnockbackGateway` (Wave 6a): read
 *    `target.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.value` — Bukkit
 *    already aggregates equipped-item `ItemAttributes.knockbackResistance`
 *    into this attribute (via `PaperItemMaterializer` at item
 *    materialization time), so the gateway gets the authoritative
 *    Phase 1 source for free.
 *  - Future Astera-native source: read
 *    `defenderContainer.effectiveNumber(Attributes.RESIST_KNOCKBACK)` if
 *    a feature wave wires the equip-sync use case for non-Bukkit-backed
 *    deployments.
 *
 * Scaling formula: `scaledImpulse = impulse * (1.0 - resistance.coerceIn(0.0, 1.0))`.
 * Resistance of 1.0 zeroes the impulse (target is fully immune). Resistance
 * of 0.0 leaves the impulse unchanged. Implementations MUST cap at
 * `[0.0, 1.0]` so configuration errors cannot invert the impulse vector.
 *
 * `NoOpKnockbackGateway` is exempt from this contract (it does not apply
 * physical knockback, so reading resistance is wasted work).
 */
public interface IKnockbackGateway {
    public fun applyImpulse(target: PlayerId, impulse: Vec3)

    /**
     * Pushes [target] horizontally away from [push].awayFrom (the attacker) with [push].strength. The
     * direction is the target's live position minus the attacker's, resolved here because only the
     * adapter knows where the target is. Resistance applies as in [applyImpulse].
     */
    public fun applyPush(target: PlayerId, push: KnockbackPush)
}
