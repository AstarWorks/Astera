package com.astarworks.astera.core.adapter.paper.physiology

import com.astarworks.astera.core.application.port.outbound.HealthMutationError
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.service.InMemoryPlayerHealthGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IPlayerHealthGateway] (Wave 6a-redesign Step 3 /
 * ADR-0151 — supersedes the original Wave 6a-2 / ADR-0145 design).
 *
 * # New shape (post-ADR-0149)
 *
 * This adapter is a **thin wrapper around [InMemoryPlayerHealthGateway]**
 * (which routes HP through `IAttributeContainer.CURRENT_HP / MAX_HP` per
 * ADR-0150). Every mutation method:
 *
 *  1. Delegates to the wrapped in-memory gateway → updates the canonical
 *     Astera HP store (`Attributes.CURRENT_HP`).
 *  2. Reads the resulting Astera HP back from the container.
 *  3. Updates Bukkit `player.health` as a **UI mirror** using the formula:
 *
 *     ```
 *     mirror = ((asteraHp / asteraMax) * bukkitMax).coerceAtLeast(MIRROR_MIN_HP)
 *     ```
 *
 *     where `MIRROR_MIN_HP = 1.0`. The clamp ensures `player.health > 0.0`
 *     always — Bukkit's `PlayerDeathEvent` fires at `health <= 0`, so the
 *     floor prevents the vanilla death sequence from ever firing under
 *     Astera-mediated damage.
 *
 * # No vanilla death (per ADR-0149 §D)
 *
 * At Astera HP = 0 the player visually shows ~0.5 hearts (mirror clamp
 * fires) and the Astera death sequence proceeds via `PlayerKilled`
 * (published by `ApplyDamageUseCase` on `DamageOutcome.Killed`). Bukkit
 * `PlayerDeathEvent` is never triggered.
 *
 * Custom HUD (Wave 6b `PaperHudGateway`) will hide the vanilla heart bar
 * and render Astera HP via BossBar / scoreboard slot.
 *
 * # Why wrap the in-memory gateway instead of re-implementing
 *
 * The IPlayerHealthGateway contract has 7 methods + regen scheduling
 * lifecycle. Re-implementing all 7 against the container would duplicate
 * ~200 lines of code that already passed ADR-0150 review. The Paper
 * adapter's distinct concern is **Bukkit mirror sync** — wrapping keeps
 * the Astera HP logic in one place and lets the adapter focus on its
 * unique value-add.
 *
 * # Offline-player semantics
 *
 * When a mutation succeeds (container write OK) but the player is offline
 * (no `server.getPlayer(uuid)`), the Bukkit mirror write is skipped with
 * a DEBUG log. The mutation still returns `Result.success(Unit)` because
 * the canonical Astera state is updated; the mirror will sync on next
 * `setHealth`-equivalent call once the player rejoins. (Different from
 * Wave 6a-2 original which returned `BackendUnavailable` for offline —
 * that framing assumed Bukkit was source of truth.)
 *
 * # Threading
 *
 * Bukkit `player.health` setter requires main thread. The wrapped
 * in-memory gateway has no threading requirement. Callers MUST be on
 * main thread for the Bukkit-mirror write to succeed; the IScheduler
 * regen task runs on main per its contract.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCorePlugin.onEnable` (overlays the InMemory
 * gateway at priority 0). Both gateways share the **same** container
 * store reference, so both read/write the same canonical Astera HP —
 * the Paper adapter just adds the Bukkit-mirror side effect.
 */
public class PaperPlayerHealthGateway(
    private val containerStore: IPlayerAttributeContainerStore,
    private val server: Server,
    scheduler: IScheduler,
    eventBus: IDomainEventBus,
    defaultMaxHp: Double = 20.0,
) : IPlayerHealthGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    private val delegate: InMemoryPlayerHealthGateway =
        InMemoryPlayerHealthGateway(containerStore, scheduler, eventBus, defaultMaxHp)

    override fun setMaxHealth(playerId: PlayerId, maxHp: Double): Result<Unit, HealthMutationError> {
        val outcome = delegate.setMaxHealth(playerId, maxHp)
        if (outcome is Result.Success) mirrorToBukkit(playerId)
        return outcome
    }

    override fun setHealth(
        playerId: PlayerId,
        hp: Double,
        source: HealthChangeSource,
    ): Result<Unit, HealthMutationError> {
        val outcome = delegate.setHealth(playerId, hp, source)
        if (outcome is Result.Success) mirrorToBukkit(playerId)
        return outcome
    }

    override fun heal(
        playerId: PlayerId,
        amount: Double,
        source: HealthChangeSource,
    ): Result<Unit, HealthMutationError> {
        val outcome = delegate.heal(playerId, amount, source)
        if (outcome is Result.Success) mirrorToBukkit(playerId)
        return outcome
    }

    override fun damage(
        playerId: PlayerId,
        amount: Double,
        source: HealthChangeSource,
    ): Result<Unit, HealthMutationError> {
        // ADR-0149 §D: this is the ADMIN/SYSTEM damage path (suicide /
        // void / fall / /kill — NOT combat). It still goes through the
        // container and mirror. Combat damage is IDamageGateway.apply
        // (Wave 6a-3 PaperDamageGateway in Step 3).
        val outcome = delegate.damage(playerId, amount, source)
        if (outcome is Result.Success) mirrorToBukkit(playerId)
        return outcome
    }

    override fun setRegenRate(
        playerId: PlayerId,
        hpPerSecond: Double,
        ttlTicks: Int,
    ): Result<Unit, HealthMutationError> = delegate.setRegenRate(playerId, hpPerSecond, ttlTicks)

    override fun clearRegen(playerId: PlayerId) = delegate.clearRegen(playerId)

    override fun getCurrentHealth(playerId: PlayerId): Double? = delegate.getCurrentHealth(playerId)

    override fun getMaxHealth(playerId: PlayerId): Double? = delegate.getMaxHealth(playerId)

    /**
     * Update Bukkit `player.health` to reflect the Astera HP value in
     * the container, applying [MIRROR_MIN_HP] floor to prevent vanilla
     * `PlayerDeathEvent` from firing.
     */
    private fun mirrorToBukkit(playerId: PlayerId) {
        val player = server.getPlayer(playerId.uuid) ?: run {
            if (log.isDebugEnabled) {
                log.debug("PaperPlayerHealthGateway.mirrorToBukkit: target {} offline; skipped", playerId)
            }
            return
        }
        val container = containerStore.containerFor(playerId)
        val asteraHp = (container.get(Attributes.CURRENT_HP) as? AttributeValue.NumberValue)?.value
        val asteraMax = (container.get(Attributes.MAX_HP) as? AttributeValue.NumberValue)?.value
        if (asteraHp == null || asteraMax == null || asteraMax <= 0.0) {
            // No Astera HP / MAX recorded yet — leave Bukkit health alone
            // (vanilla default applies). This case fires on the very first
            // mutation before MAX is set; subsequent mirrorToBukkit will
            // sync once both keys exist.
            return
        }
        val bukkitMax = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: VANILLA_DEFAULT_MAX_HP
        val mirror = ((asteraHp / asteraMax) * bukkitMax).coerceAtLeast(MIRROR_MIN_HP)
        // Belt + suspenders: also clamp to the bukkit attribute max so the
        // mirror never exceeds vanilla heart-bar capacity (Bukkit throws
        // IllegalArgumentException on setHealth(value > max)).
        player.health = mirror.coerceAtMost(bukkitMax)
    }

    public companion object {
        /**
         * Minimum value written to Bukkit `player.health` by any
         * Astera-mediated path (ADR-0149 §C). Bukkit fires
         * `PlayerDeathEvent` at `health <= 0`; the 1.0 floor prevents
         * vanilla death sequence from ever firing under Astera control.
         */
        public const val MIRROR_MIN_HP: Double = 1.0

        /** Bukkit's vanilla `Attribute.MAX_HEALTH` default when unset. */
        public const val VANILLA_DEFAULT_MAX_HP: Double = 20.0
    }
}
