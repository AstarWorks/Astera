package com.astarworks.astera.combat.adapter.paper.aoe

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway
import com.astarworks.astera.combat.application.service.aoe.ExplosionDamageApplier
import com.astarworks.astera.combat.domain.aoe.BlockDamagePolicy
import com.astarworks.astera.combat.domain.aoe.ExplosionError
import com.astarworks.astera.combat.domain.aoe.ExplosionResult
import com.astarworks.astera.combat.domain.aoe.ExplosionSpec
import com.astarworks.astera.combat.domain.event.aoe.ExplosionTriggered
import com.astarworks.astera.core.application.port.outbound.IBlockStateGateway
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.block.BlockDamageOutcome
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.ToolType
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.slf4j.LoggerFactory
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.time.Duration

/**
 * Paper concrete of [IExplosionGateway] (Wave 6e, ADR-0162).
 *
 * Visual blast via Bukkit `World.createExplosion` + per-target entity
 * damage via [INearbyTargetQuery] + per-block damage routed through
 * [IBlockStateGateway] (which writes through to the Wave 6c
 * `IBlockChangeJournalRepository`). Vanilla damage path NEVER engages
 * (ADR-0149 §C): the gateway does not call any method that would let
 * Bukkit reduce player health through vanilla armor / Resistance /
 * Protection — Astera owns the damage math end-to-end.
 *
 * # Side-effect anatomy
 *
 * Each successful `trigger` performs (in order, all main-thread):
 *
 *  1. **Visual blast.** `World.createExplosion(loc, power, setFire,
 *     breakBlocks)` provides the explosion particles, screen shake, and
 *     vanilla sound. The `breakBlocks` flag is gated by
 *     [BlockDamagePolicy] — only [BlockDamagePolicy.Vanilla] passes
 *     `true`. We never spawn vanilla entity damage through this call by
 *     design: even when `breakBlocks=true` Wave 6a-6's `BukkitEventAdapter`
 *     cancels the resulting `EntityDamageEvent` so Astera's per-target
 *     loop (step 3) is the only source of damage applied to players.
 *  2. **Astera block damage.** When [BlockDamagePolicy.Astera] is selected,
 *     we walk a sphere around `center` with radius `floor(spec.radius)`
 *     (capped at [ASTERA_BLOCK_RADIUS_CAP] to prevent runaway iteration
 *     in a 10k-radius "destroy the world" boss skill misconfig) and call
 *     [IBlockStateGateway.applyDamageFromSkill] per block-cell. The
 *     [BlockDamagePolicy.Astera.damageRatio] scales
 *     [BlockDamageProfile.baseAmount] linearly so a `damageRatio = 0.5`
 *     explosion deals half of the matrix cell's damage per block. The
 *     gateway publishes `BlockBrokenBySkill` / `BlockDamagedBySkill`
 *     itself; we only count broken blocks for [ExplosionResult.affectedBlocks].
 *  3. **Astera entity damage.** `nearbyQuery.findInRadius(center, radius,
 *     world)` returns the candidate `PlayerId` set. We re-resolve each
 *     player's world-space position via [Server.getPlayer] and apply
 *     linear distance falloff:
 *       `dmg = baseDamage * (1.0 - (distance / radius)).coerceIn(0.0, 1.0)`
 *     The [ExplosionDamageApplier] SPI is consulted with the falloff'd
 *     amount so production wiring routes through `ApplyDamageUseCase`
 *     (full modifier pipeline: armor, crits, FF, combo, focus-fire) and
 *     tests can inject [ExplosionDamageApplier.None] for record-only
 *     verification. Per-target radial knockback uses the actual `(target
 *     - center)` direction (not the placeholder
 *     [com.astarworks.astera.combat.application.service.aoe.InMemoryExplosionGateway.RADIAL_UNIT])
 *     — the whole point of
 *     splitting damage falloff to the Paper impl is so world-space
 *     positions can do the geometry work.
 *  4. **Domain event.** Publish [ExplosionTriggered] with the
 *     accumulated per-player damage list + block count.
 *
 * # Block damage policy
 *
 *  - [BlockDamagePolicy.None] — `breakBlocks=false`. No block damage path
 *    runs. `affectedBlocks = 0`.
 *  - [BlockDamagePolicy.Vanilla] — `breakBlocks=true` on the visual call;
 *    vanilla explosion algorithm computes blast-resistance, breaks
 *    blocks, drops items. We do not count broken blocks here — vanilla
 *    does not surface a count, and the world-side block-break listener
 *    (Wave 6c persistence path) journals them independently.
 *    `affectedBlocks` is reported as `0` so callers don't misread an
 *    uncountable value as "no blocks broken".
 *  - [BlockDamagePolicy.Astera] — `breakBlocks=false` on the visual call;
 *    we route block damage through [IBlockStateGateway.applyDamageFromSkill]
 *    per cell. This is the path skill-authors should use for bounded,
 *    matrix-driven destruction (Wave 5x ADR-0090 contract).
 *
 * # ADR-0149 §C invariant
 *
 * This gateway MUST NOT:
 *  - Call `player.damage(amount)` (invokes vanilla armor / Protection /
 *    Resistance / Absorption math; double-counts Astera's modifier
 *    pipeline).
 *  - Trigger an `EntityDamageEvent` via a vanilla explosion entity-damage
 *    path that downstream code might consume as "real" damage. The
 *    Wave 6a-6 `BukkitEventAdapter` cancels such events defensively;
 *    we still don't rely on that as our primary contract — the visual
 *    blast simply must not be the source of damage.
 *  - Set `player.health` to anything (UI mirror is owned by
 *    `PaperDamageGateway`).
 *
 * # Threading
 *
 * `World.createExplosion` and `IBlockStateGateway` mutations are
 * main-thread only. When [trigger] is invoked off-main we defer the work
 * via `scheduler.run(regionScope(spec.world, spec.center), Duration.ZERO, ...)` and return
 * `Result.success(ExplosionResult.empty())` immediately — callers get
 * the same "fire and forget" semantics the in-memory impl provides
 * synchronously. Off-main callers who NEED the actual damage list must
 * round-trip themselves and call this gateway from the main thread.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCombatPlugin.onEnable` (overlays
 * `InMemoryExplosionGateway` at priority 0). The in-memory impl stays
 * registered as a fallback for the boot-order window before all Paper
 * collaborators are available — `ServiceRegistry` resolves highest
 * priority first per ADR-0022 contract.
 */
public class PaperExplosionGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val nearbyQuery: INearbyTargetQuery,
    private val knockbackGateway: IKnockbackGateway,
    private val blockStateGateway: IBlockStateGateway,
    private val damageApplier: ExplosionDamageApplier = ExplosionDamageApplier.None,
    private val radiusCap: Double? = null,
) : IExplosionGateway, Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * True only while this gateway's own synchronous `World.createExplosion`
     * call is on the stack. Vanilla entity damage dispatched from inside that
     * call is the visual blast's side effect; Astera applies its own damage
     * via [damageApplier], so [onVanillaBlastDamage] cancels the vanilla copy.
     * Without it core's BukkitEventAdapter would re-route the BLOCK_EXPLOSION
     * damage into IPlayerHealthGateway on top of the Astera hit (double damage).
     * Main-thread only.
     */
    private var inVisualBlast: Boolean = false

    /**
     * Cancels vanilla damage caused by this gateway's visual blast. Must be
     * registered as a Bukkit listener by the plugin; runs at LOWEST so it
     * precedes core's BukkitEventAdapter (LOW, ignoreCancelled).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public fun onVanillaBlastDamage(event: EntityDamageEvent) {
        if (!inVisualBlast) return
        when (event.cause) {
            EntityDamageEvent.DamageCause.BLOCK_EXPLOSION,
            EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
            -> event.isCancelled = true
            else -> Unit
        }
    }

    override fun trigger(spec: ExplosionSpec): Result<ExplosionResult, ExplosionError> {
        radiusCap?.let { cap ->
            if (spec.radius > cap) {
                return Result.failure(ExplosionError.RadiusExceedsCap(spec.radius, cap))
            }
        }

        if (server.getWorld(spec.world.value)?.let { org.bukkit.Bukkit.isOwnedByCurrentRegion(org.bukkit.Location(it, spec.center.x, spec.center.y, spec.center.z)) } != true) {
            // Off-main: defer to next tick and return a placeholder success.
            // The deferred run publishes the actual ExplosionTriggered event;
            // synchronous callers that need the damage list must call us on
            // main. Matches the Wave 6d defer pattern.
            scheduler.run(regionScope(spec.world, spec.center), Duration.ZERO) {
                runOnMain(spec)
            }
            return Result.success(EMPTY_RESULT)
        }

        return runOnMain(spec)
    }

    private fun runOnMain(spec: ExplosionSpec): Result<ExplosionResult, ExplosionError> {
        val world = server.getWorld(spec.world.value)
            ?: return Result.failure(ExplosionError.WorldNotLoaded(spec.world))

        val center = spec.center
        val location = Location(world, center.x, center.y, center.z)

        // 1. Visual blast. The `breakBlocks` flag is the ONLY place vanilla
        //    block destruction can fire — Astera and None policies pass
        //    false; Vanilla passes true. Entity damage from this call is
        //    expected to be cancelled by Wave 6a-6's BukkitEventAdapter;
        //    we never rely on it as the source of player damage.
        val breakVanillaBlocks = spec.blockDamage is BlockDamagePolicy.Vanilla
        inVisualBlast = true
        runCatching {
            world.createExplosion(
                location,
                spec.radius.toFloat(),
                /* setFire = */ false,
                /* breakBlocks = */ breakVanillaBlocks,
            )
        }.also { inVisualBlast = false }.onFailure { t ->
            // Bukkit can throw on bizarre worlds (e.g. y outside build limits
            // on certain server forks). We continue with the damage path —
            // the visual is cosmetic.
            log.warn("PaperExplosionGateway: createExplosion threw for {}: {}", spec.world, t.message)
        }

        // 2. Astera block damage (None / Vanilla skip this path).
        val brokenBlocks = if (spec.blockDamage is BlockDamagePolicy.Astera) {
            applyAsteraBlockDamage(spec, spec.blockDamage)
        } else {
            0
        }

        // 3. Astera entity damage with linear distance falloff.
        val candidates: List<PlayerId> = nearbyQuery.findInRadius(
            center = center,
            radius = spec.radius,
            world = spec.world,
        )

        val affected = mutableListOf<Pair<PlayerId, Double>>()
        val baseDamage = spec.damageProfile.base
        if (baseDamage > 0.0 && spec.radius > 0.0) {
            for (target in candidates) {
                // Players and mobs alike (mobs travel as PlayerId(entityUuid), WP3).
                val victim = server.getEntity(target.uuid) ?: continue
                val loc = victim.location
                val dx = loc.x - center.x
                val dy = loc.y - center.y
                val dz = loc.z - center.z
                val distance = sqrt(dx * dx + dy * dy + dz * dz)
                val falloff = (1.0 - (distance / spec.radius)).coerceIn(0.0, 1.0)
                if (falloff <= 0.0) continue
                val falloffedDamage = baseDamage * falloff
                val applied = damageApplier.apply(target, falloffedDamage, spec)
                affected += target to applied

                if (spec.knockbackMultiplier > 0.0) {
                    val radial = radialUnit(loc.x, loc.y, loc.z, center)
                    val magnitude = spec.knockbackMultiplier * falloff
                    knockbackGateway.applyImpulse(
                        target = target,
                        impulse = Vec3(
                            x = radial.x * magnitude,
                            y = radial.y * magnitude,
                            z = radial.z * magnitude,
                        ),
                    )
                }
            }
        } else if (baseDamage > 0.0 && spec.radius == 0.0) {
            // Special case: radius == 0 means "only entities AT center" per
            // ExplosionSpec.radius KDoc. INearbyTargetQuery's radius=0 query
            // is implementation-defined; we mirror the InMemory's "uniform
            // base damage" semantics for the degenerate case.
            for (target in candidates) {
                val applied = damageApplier.apply(target, baseDamage, spec)
                affected += target to applied
            }
        }

        val result = ExplosionResult(
            affectedPlayers = affected.toList(),
            affectedBlocks = brokenBlocks,
        )

        eventBus.publish(
            ExplosionTriggered(
                world = spec.world,
                center = center,
                radius = spec.radius,
                source = spec.source,
                affectedPlayers = result.affectedPlayers,
                affectedBlocks = result.affectedBlocks,
                atTick = scheduler.currentTick(),
            ),
        )

        return Result.success(result)
    }

    /**
     * Walk an integer-coord sphere of side `2 * intRadius + 1` and call
     * [IBlockStateGateway.applyDamageFromSkill] on each cell whose
     * Euclidean distance is within `spec.radius`. Returns the number of
     * cells that came back with a [com.astarworks.astera.core.domain.block.BlockDamageOutcome.Broken]
     * outcome.
     *
     * Cap the radius at [ASTERA_BLOCK_RADIUS_CAP] so a misconfigured 1000-
     * radius boss skill doesn't iterate ~4 billion cells.
     */
    private fun applyAsteraBlockDamage(
        spec: ExplosionSpec,
        policy: BlockDamagePolicy.Astera,
    ): Int {
        val rawRadius = ceil(spec.radius).toInt().coerceAtLeast(0)
        val intRadius = rawRadius.coerceAtMost(ASTERA_BLOCK_RADIUS_CAP)
        if (intRadius == 0) return 0
        if (rawRadius > ASTERA_BLOCK_RADIUS_CAP) {
            log.warn(
                "PaperExplosionGateway: spec.radius {} exceeds block-walk cap {}; clamped",
                spec.radius,
                ASTERA_BLOCK_RADIUS_CAP,
            )
        }

        val cx = floor(spec.center.x).toInt()
        val cy = floor(spec.center.y).toInt()
        val cz = floor(spec.center.z).toInt()
        val radiusSq = spec.radius * spec.radius

        // Scale the per-cell damage by policy.damageRatio so a ratio=0.5
        // explosion deals half of the matrix cell's damage.
        val baseAmount = max(
            1,
            (BlockDamageProfile.DEFAULT_BASE * policy.damageRatio).toInt(),
        )
        val profile = BlockDamageProfile(
            baseAmount = baseAmount,
            effectiveTool = EXPLOSION_TOOL,
            materialOverride = null,
        )

        // SkillId convention: caster-attributed explosions ideally pass the
        // originating skill id through; for the gateway-default case we
        // synthesize [EXPLOSION_SKILL_ID] so the journal entry has a stable
        // attribution.
        val skillId = EXPLOSION_SKILL_ID
        val attackerId = explosionAttacker(spec) ?: NO_ATTACKER

        var broken = 0
        for (dx in -intRadius..intRadius) {
            for (dy in -intRadius..intRadius) {
                for (dz in -intRadius..intRadius) {
                    val pos = Vec3(
                        x = (cx + dx).toDouble() + BLOCK_CENTER_OFFSET,
                        y = (cy + dy).toDouble() + BLOCK_CENTER_OFFSET,
                        z = (cz + dz).toDouble() + BLOCK_CENTER_OFFSET,
                    )
                    val ddx = pos.x - spec.center.x
                    val ddy = pos.y - spec.center.y
                    val ddz = pos.z - spec.center.z
                    if (ddx * ddx + ddy * ddy + ddz * ddz > radiusSq) continue

                    val outcome = blockStateGateway.applyDamageFromSkill(
                        world = spec.world,
                        pos = pos,
                        skillId = skillId,
                        attackerId = attackerId,
                        profile = profile,
                    )
                    val unwrapped = outcome.getOrNull() ?: continue
                    if (unwrapped is BlockDamageOutcome.Broken) {
                        broken++
                    }
                }
            }
        }
        return broken
    }

    private fun radialUnit(px: Double, py: Double, pz: Double, center: Vec3): Vec3 {
        val dx = px - center.x
        val dy = py - center.y
        val dz = pz - center.z
        val l2 = dx * dx + dy * dy + dz * dz
        if (l2 == 0.0) {
            // Target sits exactly at center — no unique radial direction.
            // Vanilla TNT defaults to a straight-up impulse for the same
            // case; mirror that so the player still gets knocked off the
            // explosion seat.
            return STRAIGHT_UP
        }
        val inv = 1.0 / sqrt(l2)
        return Vec3(dx * inv, dy * inv, dz * inv)
    }

    /**
     * Best-effort attacker id for the explosion's per-block journal entry.
     *
     * `DamageSource` is a sealed hierarchy in `:plugin:combat`; we don't
     * import its concrete variants from this adapter (sibling isolation
     * keeps the adapter focused on Paper-side concerns). The skill / block
     * pipeline accepts a null attacker by way of [NO_ATTACKER]; future
     * waves can promote attacker extraction to the application layer if a
     * boss-skill attribution test demands it.
     */
    private fun explosionAttacker(spec: ExplosionSpec): PlayerId? {
        @Suppress("UNUSED_PARAMETER") spec
        return null
    }

    public companion object {

        /**
         * Maximum block-walk radius for [BlockDamagePolicy.Astera]
         * destruction. 32 blocks ≈ 137k cells iterated worst-case, which
         * still completes in well under a tick budget; larger explosions
         * are clamped with a WARN log so a misconfigured 1000-radius boss
         * skill doesn't iterate ~4 billion cells.
         */
        public const val ASTERA_BLOCK_RADIUS_CAP: Int = 32

        /**
         * Offset added to integer block coords so a Vec3 sampled at the
         * cell's geometric center (not a corner) — this matches how
         * [IBlockStateGateway.applyDamageFromSkill] resolves block cells
         * (floor-coords) without ambiguity on the boundary.
         */
        public const val BLOCK_CENTER_OFFSET: Double = 0.5

        /**
         * Skill-id attributed to gateway-default explosion block damage.
         * Kebab-case per [SkillId] validation. Skill-system callers that
         * want their own skill-id attribution should publish through their
         * own block-damage path; the gateway's default is for raw
         * environmental explosions (TNT, creepers via a future bridge).
         */
        public val EXPLOSION_SKILL_ID: SkillId = SkillId("astera-explosion")

        /**
         * Synthetic tool used for explosion block damage. PICKAXE/DIAMOND
         * is a reasonable "anything below netherite" approximation — the
         * per-block-spec `toolEffectiveness` matrix can override per
         * material. Authors who want explosion-specific cells can use the
         * `materialOverride` field on a custom profile if they thread
         * their own skill through `IBlockStateGateway.applyDamageFromSkill`.
         */
        public val EXPLOSION_TOOL: ToolType = ToolType.DIAMOND_PICKAXE

        /**
         * Placeholder attacker for gateway-default explosions whose
         * originating `PlayerId` is unknown. Bound to a zero-UUID so the
         * journal entry stays type-correct without claiming a real player
         * triggered the destruction.
         */
        public val NO_ATTACKER: PlayerId = PlayerId(java.util.UUID(0L, 0L))

        /**
         * Empty result returned by off-main `trigger` invocations. The
         * deferred main-thread run does the real work and publishes
         * [ExplosionTriggered] independently.
         */
        public val EMPTY_RESULT: ExplosionResult = ExplosionResult(
            affectedPlayers = emptyList(),
            affectedBlocks = 0,
        )

        /**
         * Default radial impulse for a target sitting exactly at the
         * explosion center. Matches the in-memory fallback semantics so
         * the test suite's degenerate-position cases observe the same
         * direction across both impls.
         */
        public val STRAIGHT_UP: Vec3 = Vec3(0.0, 1.0, 0.0)
    }
}
