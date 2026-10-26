package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.InvulnerabilityFrames
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper concrete of [IDamageGateway] (Wave 6a-redesign Step 3 / ADR-0151
 * — supersedes the original Wave 6a-3 / ADR-0146 design).
 *
 * # New shape (post-ADR-0149)
 *
 * Astera owns the damage math entirely:
 *
 *  1. Read Astera HP from `IAttributeContainer.CURRENT_HP` (Step 2 / ADR-0150).
 *  2. Compute `newHp = (currentHp - resolved.finalAmount).coerceAtLeast(0.0)`
 *     — pure Kotlin arithmetic. NO vanilla armor / Protection / Resistance /
 *     Absorption math.
 *  3. Write the new HP back to the container and publish `PlayerHealthChanged`
 *     (HUDs refresh immediately; the `Other("combat")` source keeps the vanilla
 *     kill resolver from announcing a second death).
 *  4. Update Bukkit `player.health` as a UI mirror with `MIRROR_MIN_HP = 1.0`
 *     floor (ADR-0149 §C) so vanilla `PlayerDeathEvent` NEVER fires.
 *  5. Trigger `player.playHurtAnimation(0f)` for vanilla red-flash +
 *     hurt-sound feedback (no damage applied by this call).
 *  6. Invulnerability frames are Astera-owned ([InvulnerabilityFrames], one
 *     20-tick window per target after a landed hit). The vanilla
 *     `noDamageTicks` is neither read nor written: vanilla sets it for its own
 *     reasons (join / respawn / mob hits / the core re-route) and a read made
 *     rapid, unrelated Astera hits report `Immune` with no feedback.
 *  7. Return `Killed` if newHp ≤ 0.0 else `HpReduced(newHp)`.
 *
 * `ApplyDamageUseCase` publishes `PlayerKilled` on `DamageOutcome.Killed`
 * unchanged (line ~196-205). The combat-bonus chain (Wave K / ADR-0128)
 * runs end-to-end because `finalAmount` is consumed by Astera's container
 * write without vanilla math layered on top.
 *
 * # Critical anti-patterns (Konsist Rule 57 forbids)
 *
 * This impl MUST NOT contain calls to Bukkit's Damageable
 * `damage(amount)` method (invokes vanilla armor / Protection /
 * Resistance / Absorption math — double-counts Astera's
 * ArmorReductionModifier), and MUST NOT write the Bukkit `health`
 * property to the literal zero value (fires vanilla `PlayerDeathEvent` —
 * Astera death is via `PlayerKilled` domain event instead).
 *
 * MUST contain:
 *  - `appliedThisTick` — Wave 6a-6 BukkitEventAdapter Path 2 vs Path 1
 *    coordination marker
 *  - `isDead` — dead-target short-circuit
 *  - `Attributes.CURRENT_HP` — container reads are authoritative
 *
 * # Path 1 (Astera combat death) / Path 2 (vanilla / non-combat death)
 *
 * Per ADR-0137 (still load-bearing despite ADR-0149 supersede of ADR-0146):
 *
 *  - Path 1: this gateway → container write → mirror clamp at 1.0 → no
 *    vanilla death. ApplyDamageUseCase publishes PlayerKilled.
 *  - Path 2 (vanilla / non-combat): Wave 6a-6 `BukkitEventAdapter` cancels
 *    vanilla `EntityDamageEvent` (fall / lava / void / drown / cactus)
 *    and re-routes through `IPlayerHealthGateway.damage()`. Defensive
 *    `PlayerDeathEvent` cancellation as belt-and-suspenders (should never
 *    fire under Astera management given the MIRROR_MIN_HP=1.0 floor).
 *
 * The [appliedThisTick] marker (kept from ADR-0146) signals to Wave 6a-6
 * "this target was damaged by Path 1 this tick; skip Path 2 PlayerKilled
 * publish" — preventing double-fire of `AssistAttributionUseCase`.
 *
 * # Threading
 *
 * Bukkit `player.health` / `player.playHurtAnimation` MUST run on main.
 * `ApplyDamageUseCase` is invoked from event handlers + combat use cases
 * that already run on main. Off-main callers MUST route through
 * `scheduler.run(ExecutionScope.Global, Duration.ZERO, ...)`.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCombatPlugin.onEnable` (overlays
 * `InMemoryDamageGateway` priority 0 from Wave Q / ADR-0133). Caller must
 * invoke [start] right after construction to wire the 1-tick clear task
 * for [appliedThisTick].
 */
public class PaperDamageGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    private val containerStore: IPlayerAttributeContainerStore,
    private val eventBus: IDomainEventBus,
) : IDamageGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    private val frames = InvulnerabilityFrames()

    /**
     * Per-tick marker: players whose damage was applied via Path 1
     * (this gateway). Read by Wave 6a-6 BukkitEventAdapter.onPlayerDeath
     * to skip Path 2 (vanilla-death) handler. Cleared by [start]'s 1-tick
     * scheduler task. ADR-0137 §PaperDamageGateway code sketch (still
     * load-bearing post-ADR-0149).
     */
    public val appliedThisTick: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()

    /**
     * Wire the 1-tick clear task. Returns the handle so the plugin can
     * cancel on `onDisable`.
     */
    public fun start(): IScheduler.TaskHandle =
        scheduler.runRepeatingTicks(ExecutionScope.Global, initialDelayTicks = 1L, periodTicks = 1L) {
            appliedThisTick.clear()
        }

    override fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
        ignoreInvulnerability: Boolean,
    ): Result<DamageOutcome, DamageApplicationError> {
        val player = server.getPlayer(target.uuid)
            ?: return Result.failure(DamageApplicationError.TargetOffline(target))
        check(org.bukkit.Bukkit.isOwnedByCurrentRegion(player)) { "Damage outside target region" }
        if (player.isDead) {
            return Result.failure(DamageApplicationError.TargetAlreadyDead(target))
        }

        val container = containerStore.containerFor(target)
        val currentHp = (container.get(Attributes.CURRENT_HP) as? AttributeValue.NumberValue)?.value
            ?: ((container.get(Attributes.MAX_HP) as? AttributeValue.NumberValue)?.value
                ?: VANILLA_DEFAULT_MAX_HP)
        val asteraMax = (container.get(Attributes.MAX_HP) as? AttributeValue.NumberValue)?.value
            ?: VANILLA_DEFAULT_MAX_HP
        // Astera-dead (HP 0, mirror held at 1.0 so Bukkit isDead is false):
        // reject so ApplyDamageUseCase does not re-publish PlayerKilled per hit.
        if (currentHp <= 0.0) {
            return Result.failure(DamageApplicationError.TargetAlreadyDead(target))
        }

        if (!frames.tryEnter(target, scheduler.currentTick(), ignoreInvulnerability)) {
            log.debug("PaperDamageGateway: {} is inside its Astera invulnerability window", target)
            return Result.failure(DamageApplicationError.Immune(target))
        }

        // Mark BEFORE the container write so Wave 6a-6 BukkitEventAdapter
        // sees the marker if any synchronous event chain inspects it.
        appliedThisTick.add(target)

        val newHp = (currentHp - resolved.finalAmount).coerceAtLeast(0.0)
        container.set(Attributes.CURRENT_HP, AttributeValue.NumberValue(newHp))
        // Announce the change so the HP HUD and the nameplates refresh now instead of on their next poll.
        // The source is a non-damage `Other`, so `VanillaKillResolver` ignores it: the death is announced
        // once, by `ApplyDamageUseCase` (`PlayerKilled`) on `DamageOutcome.Killed`.
        eventBus.publish(
            PlayerHealthChanged(
                playerId = target,
                oldHp = currentHp,
                newHp = newHp,
                source = COMBAT_HEALTH_SOURCE,
                atTick = scheduler.currentTick(),
            ),
        )

        // UI mirror per ADR-0149 §C: clamp ≥ MIRROR_MIN_HP to prevent
        // vanilla PlayerDeathEvent. Visual hearts ≈ proportional with
        // 0.5-heart floor at Astera HP = 0.
        val bukkitMax = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: VANILLA_DEFAULT_MAX_HP
        val mirror = ((newHp / asteraMax) * bukkitMax)
            .coerceAtLeast(MIRROR_MIN_HP)
            .coerceAtMost(bukkitMax)
        player.health = mirror

        // Vanilla feedback (red flash + hurt sound) — no damage applied
        // by this call (it just plays the animation per Paper API).
        player.playHurtAnimation(0f)

        return if (newHp <= 0.0) {
            Result.success(DamageOutcome.Killed)
        } else {
            Result.success(DamageOutcome.HpReduced(remainingHp = newHp))
        }
    }

    public companion object {
        /** `PlayerHealthChanged.source` of a combat hit; deliberately not a damage source for the vanilla kill resolver. */
        private val COMBAT_HEALTH_SOURCE: HealthChangeSource = HealthChangeSource.Other("combat")

        /**
         * Mirrors [com.astarworks.astera.core.adapter.paper.physiology.PaperPlayerHealthGateway.MIRROR_MIN_HP].
         * Duplicated here to keep the combat gateway independent of the
         * physiology gateway type at compile time (sibling-isolation rule
         * ADR-0022 — combat plugin should not import core/adapter/paper).
         */
        public const val MIRROR_MIN_HP: Double = 1.0

        /** Vanilla Bukkit max-health default. */
        public const val VANILLA_DEFAULT_MAX_HP: Double = 20.0
    }
}
