package com.astarworks.astera.combat.platform.wiring

import com.astarworks.astera.combat.application.port.outbound.IBlockEditGuard
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway
import com.astarworks.astera.combat.application.port.outbound.ISummonGateway
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
 *   port (Paper `PaperProjectileService` / simulator tick-stepped service, both on `ProjectileStepper`).
 * @property nearbyTargetQuery host spatial query over players *and mobs*; registered
 *   first so every later `services.get<INearbyTargetQuery>()` sees it (WP3).
 * @property entityDamageGateway non-player (mob) damage; null = mobs are not damageable (WP3).
 * @property entityEffectsGateway velocity / fire / freeze for players and mobs (WP4); null = those mechanics warn and drop.
 * @property playerFeedbackGateway action bar / title output (WP4); null = those mechanics warn and drop.
 * @property displayEntityGateway SpawnDisplay output (WP5); null = the mechanic warns and drops.
 * @property summonGateway Summon mob spawning (WP5); null = the mechanic warns and drops.
 * @property blockEditGuard overrides the default arena-only terrain-edit guard (WP5); null = `ArenaBlockEditGuard`.
 * @property trainingDummyShells visible bodies of the lobby training dummies (F3-11); null = no dummies on this host.
 * @property explosionGateway receives the full-pipeline damage applier built
 *   from `ApplyDamageUseCase` (Wave 6e-N1 / ADR-0164), so it is a 2-arg factory.
 */
public class CombatPlatformPorts(
    public val toolBlocks: ((ServiceRegistry) -> com.astarworks.astera.combat.application.port.outbound.IToolBlockGateway)? = null,
    public val projectileService: (ServiceRegistry) -> IProjectileService,
    public val lightningGateway: ((ServiceRegistry) -> ILightningGateway)? = null,
    public val fireSpreadController: ((ServiceRegistry) -> IFireSpreadController)? = null,
    public val areaEffectGateway: ((ServiceRegistry) -> IAreaEffectGateway)? = null,
    public val damageGateway: ((ServiceRegistry) -> IDamageGateway)? = null,
    public val statusEffectContainer: ((ServiceRegistry) -> IStatusEffectContainer)? = null,
    /** Worn Astera armor items per player; with it the real item-driven armor provider replaces the stub. */
    public val wornItems: ((ServiceRegistry) -> com.astarworks.astera.combat.application.port.outbound.IWornItems)? = null,
    public val knockbackGateway: ((ServiceRegistry) -> IKnockbackGateway)? = null,
    public val explosionGateway: ((ServiceRegistry, ExplosionDamageApplier) -> IExplosionGateway)? = null,
    public val nearbyTargetQuery: ((ServiceRegistry) -> INearbyTargetQuery)? = null,
    public val entityDamageGateway: ((ServiceRegistry) -> IEntityDamageGateway)? = null,
    public val entityEffectsGateway: ((ServiceRegistry) -> IEntityEffectsGateway)? = null,
    public val playerFeedbackGateway: ((ServiceRegistry) -> IPlayerFeedbackGateway)? = null,
    /** Which block keys exist (vanilla + custom); `null` = this host has no block world and world-op block keys go unchecked. */
    public val blockKeys: ((ServiceRegistry) -> com.astarworks.astera.core.application.skillengine.load.IBlockKeyCatalog)? = null,
    public val displayEntityGateway: ((ServiceRegistry) -> IDisplayEntityGateway)? = null,
    public val summonGateway: ((ServiceRegistry) -> ISummonGateway)? = null,
    public val blockEditGuard: ((ServiceRegistry) -> IBlockEditGuard)? = null,
    /** Non-player casters (preview dummies, mobs): pose + teleport by entity uuid; `null` = players only. */
    public val entityCasterBody: ((ServiceRegistry) -> com.astarworks.astera.combat.application.port.outbound.IEntityCasterBody)? = null,
    /** `/astera skill preview` bodies + camera; with [tickRateControl] enables the command. */
    public val skillPreviewStage: ((ServiceRegistry) -> com.astarworks.astera.combat.application.port.outbound.ISkillPreviewStage)? = null,
    public val tickRateControl: ((ServiceRegistry) -> com.astarworks.astera.combat.application.port.outbound.ITickRateControl)? = null,
    public val trainingDummyShells: ((ServiceRegistry) -> com.astarworks.astera.combat.application.port.outbound.ITrainingDummyShells)? = null,
)
