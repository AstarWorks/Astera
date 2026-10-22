package com.astarworks.astera.combat.platform.wiring

import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityEffectsGateway
import com.astarworks.astera.combat.application.port.outbound.IPlayerFeedbackGateway
import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.IProjectileService
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.aoe.IAreaEffectGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway
import com.astarworks.astera.combat.application.port.outbound.aoe.IFireSpreadController
import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.combat.application.service.aoe.ExplosionDamageApplier
import com.astarworks.astera.core.application.services.ServiceRegistry

/**
 * Host-specific ports [CombatWiring.install] consumes (ADR-0174).
 *
 * Every overlay is a factory over the [ServiceRegistry] because the Paper
 * impls resolve Core ports (scheduler, event bus, attribute store, ...) at the
 * exact moment of registration. A `null` overlay means "leave the InMemory
 * priority-0 default in place" (the headless simulator may do this, or supply
 * its own fakes).
 *
 * The wiring registers the overlays at priority 100 *in this order*, mirroring
 * the pre-ADR-0174 `onEnable` so mid-wiring `services.get<...>()` lookups see
 * the same impl: lightning, fire-spread, area-effect, damage gateway, status
 * container, knockback, projectile, then (once `ApplyDamageUseCase` exists)
 * explosion.
 *
 * @property projectileService required: no InMemory default exists for this
 *   port (Paper `PaperProjectileService` / simulator tick-stepped service).
 * @property nearbyTargetQuery host spatial query over players *and mobs*; registered
 *   first so every later `services.get<INearbyTargetQuery>()` sees it (WP3).
 * @property entityDamageGateway non-player (mob) damage; null = mobs are not damageable (WP3).
 * @property entityEffectsGateway velocity / fire / freeze for players and mobs (WP4); null = those mechanics warn and drop.
 * @property playerFeedbackGateway action bar / title output (WP4); null = those mechanics warn and drop.
 * @property explosionGateway receives the full-pipeline damage applier built
 *   from `ApplyDamageUseCase` (Wave 6e-N1 / ADR-0164), so it is a 2-arg factory.
 */
public class CombatPlatformPorts(
    public val projectileService: (ServiceRegistry) -> IProjectileService,
    public val lightningGateway: ((ServiceRegistry) -> ILightningGateway)? = null,
    public val fireSpreadController: ((ServiceRegistry) -> IFireSpreadController)? = null,
    public val areaEffectGateway: ((ServiceRegistry) -> IAreaEffectGateway)? = null,
    public val damageGateway: ((ServiceRegistry) -> IDamageGateway)? = null,
    public val statusEffectContainer: ((ServiceRegistry) -> IStatusEffectContainer)? = null,
    public val knockbackGateway: ((ServiceRegistry) -> IKnockbackGateway)? = null,
    public val explosionGateway: ((ServiceRegistry, ExplosionDamageApplier) -> IExplosionGateway)? = null,
    public val nearbyTargetQuery: ((ServiceRegistry) -> INearbyTargetQuery)? = null,
    public val entityDamageGateway: ((ServiceRegistry) -> IEntityDamageGateway)? = null,
    public val entityEffectsGateway: ((ServiceRegistry) -> IEntityEffectsGateway)? = null,
    public val playerFeedbackGateway: ((ServiceRegistry) -> IPlayerFeedbackGateway)? = null,
)
