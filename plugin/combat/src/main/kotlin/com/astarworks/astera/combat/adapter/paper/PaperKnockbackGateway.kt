package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.entity.LivingEntity
import org.bukkit.attribute.Attribute
import org.bukkit.util.Vector
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IKnockbackGateway] (Wave 6a-1, ADR-0144) — closes the
 * Wave S 5knockback-resistance / ADR-0136 gateway-boundary contract.
 *
 * # Resistance consultation (ADR-0136 §A contract)
 *
 * Reads `Player.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.value` per
 * the Paper 1.21+ Attribute enum (no `GENERIC_` prefix — matches
 * `PaperItemMaterializer:215-222` convention). Bukkit aggregates equipped-
 * item `ItemAttributes.knockbackResistance` (set as Bukkit
 * `AttributeModifier` by `PaperItemMaterializer:220`) into the player's
 * effective resistance value, so this gateway gets the authoritative
 * Phase 1 source for free.
 *
 * Scaling formula per `IKnockbackGateway.applyImpulse` KDoc:
 *   `scaledImpulse = impulse * (1.0 - resistance.coerceIn(0.0, 1.0))`
 *
 * Resistance of 1.0 zeroes the impulse (full immunity); 0.0 leaves it
 * unchanged. The `coerceIn` cap prevents misconfigured equipment from
 * inverting the impulse vector.
 *
 * # Threading
 *
 * Bukkit `Player.setVelocity` MUST be called on the main thread. Astera's
 * combat pipeline runs `ApplyDamageUseCase` synchronously from event
 * handlers that already execute on main, so direct invocation is safe.
 * Callers from off-main paths (e.g. async tasks) MUST round-trip through
 * `IScheduler.run(Duration.ZERO, ...)` before invoking this gateway.
 *
 * # Failure modes
 *
 * - Target offline (no Bukkit Player) → DEBUG log + no-op. The combat
 *   pipeline already published `KnockbackApplied` before calling this
 *   gateway; an offline target simply doesn't receive the physical write.
 * - Zero impulse vector → no-op (no point in calling setVelocity(0,0,0);
 *   Bukkit treats that as "stop moving" which is undesirable).
 *
 * # Registration
 *
 * Registered at priority 100 in `AsteraCombatPlugin.onEnable` (overlays
 * `NoOpKnockbackGateway` priority 0). The InMemory `NoOpKnockbackGateway`
 * (which is exempt from the resistance contract per ADR-0136 §Exemption)
 * remains as the test / non-Paper fallback.
 */
public class PaperKnockbackGateway(
    private val server: Server,
    private val ownership: PaperCombatOwnership,
) : IKnockbackGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun applyImpulse(target: PlayerId, impulse: Vec3) {
        if (ownership.deferIfForeign(target) { applyImpulse(target, impulse) }) return
        ownership.assertOwned(target)
        // Players and non-player living entities (mobs travel as PlayerId(entityUuid), WP3).
        val player = server.getEntity(target.uuid) as? LivingEntity
        if (player == null) {
            if (log.isDebugEnabled) {
                log.debug("PaperKnockbackGateway: target {} offline; skipping impulse {}", target, impulse)
            }
            return
        }
        if (impulse.x == 0.0 && impulse.y == 0.0 && impulse.z == 0.0) {
            // Bukkit treats setVelocity(0,0,0) as "stop in place"; a zero
            // impulse from the domain layer means "no knockback" — preserve
            // the player's current velocity instead.
            return
        }
        val resistance = (player.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.value ?: 0.0)
            .coerceIn(0.0, 1.0)
        val scale = 1.0 - resistance
        if (scale == 0.0) {
            // Resistance == 1.0; full immunity. ADR-0136 contract requires
            // we honor this without dropping the call entirely (the domain
            // already published KnockbackApplied).
            if (log.isDebugEnabled) {
                log.debug("PaperKnockbackGateway: target {} fully immune (resistance=1.0); no setVelocity", target)
            }
            return
        }
        player.velocity = Vector(impulse.x * scale, impulse.y * scale, impulse.z * scale)
    }
}
