package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectRegistry
import com.astarworks.astera.combat.domain.event.StatusApplied
import com.astarworks.astera.combat.domain.event.StatusRemoved
import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper concrete of [IStatusEffectContainer] (Wave 6a-N2, ADR-0155 —
 * Combat polish).
 *
 * # Shape (ADR-0149 §C invariant carried over from PaperDamageGateway / ADR-0151)
 *
 * Astera-internal state is the **single source of truth** for active
 * status effects. The Bukkit `PotionEffect` write is a **UI mirror only**
 * — its sole purpose is the vanilla HUD icon strip + tinted screen overlay.
 *
 * Astera-side semantics live in the internal `MutableMap<PlayerId,
 * MutableMap<StatusEffectId, StatusEffectInstance>>`:
 *
 *  - `apply` / `remove` / `clear` mutate this map first, then attempt the
 *    mirror write
 *  - `active` / `has` read from this map (never from
 *    `Player.activePotionEffects`)
 *  - stacking semantics follow the spec (`stackable` + `maxStacks`) on the
 *    Astera side; the mirror write always represents the **single most
 *    recent** instance because Bukkit's potion API does not model stacks
 *
 * Vanilla potion observation (e.g. a player drinks a vanilla potion and we
 * adopt it as an Astera effect) is intentionally NOT done here — the
 * [com.astarworks.astera.combat.adapter.paper.StatusEffectTickListener]
 * owns the `EntityPotionEffectEvent` REMOVED handler so vanilla cleanup
 * (milk bucket) still maps back to an Astera-side removal, but Astera does
 * not auto-adopt arbitrary vanilla potion APPLIED events.
 *
 * # Bukkit potion mapping (best-effort, vanilla-equivalent only)
 *
 * [bukkitPotionTypeFor] returns a vanilla `PotionEffectType` for the
 * subset of [StatusKind]s that have a 1:1 vanilla analogue (BURN → harm
 * tick is delivered by tick listener, vanilla type used for HUD icon;
 * SPEED_UP → SPEED; PARALYSIS → SLOWNESS at amplifier high enough to
 * stand-still; etc.). Astera-specific kinds (STAR_GAIN, CT_FREEZE,
 * GRAVITY_UP, …) have no vanilla counterpart and skip the mirror write
 * silently (logged DEBUG). Internal state is still authoritative.
 *
 * The optional [statusEffectRegistry] is reserved for the Phase 6 mapping
 * config: a future content YAML may specify the Bukkit potion type per
 * Astera effect id, in which case the registry is consulted first and
 * the hardcoded [bukkitPotionTypeFor] is the fallback. Today only the
 * fallback fires.
 *
 * # Offline-player handling
 *
 * `server.getPlayer(uuid)` returning null is NOT treated as a hard
 * failure: the internal map mutation still proceeds, the Bukkit write is
 * skipped + logged DEBUG, and the [StatusApplied] / [StatusRemoved] event
 * is still published. This matches the Wave 6a-5 PaperPlayerFoodGateway
 * "track intent even while offline" precedent, adapted to the
 * Unit-returning port shape — the port has no `Result<T, E>` surface, so
 * the silent-degrade path is the only consistent choice.
 *
 * # Threading
 *
 * All Bukkit `Player.addPotionEffect` / `removePotionEffect` calls require
 * the main thread. The use cases that invoke this container
 * (ApplyStatusUseCase, mechanic dispatch) all run on main; off-main
 * callers must round-trip through `scheduler.runLater(Duration.ZERO, ...)`.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCombatPlugin.onEnable` (overlays
 * [com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer]
 * priority 0 — the in-memory fake is test-fixtures only; production
 * defaults to no impl pre-Wave-6a-N2, so this is the first production
 * registration). Construct alongside [StatusEffectTickListener] and pass
 * this instance into the listener so the tick loop reads Astera state
 * directly.
 *
 * @see PaperDamageGateway for the same Astera-canonical / Bukkit-mirror
 *      shape applied to HP (Wave 6a-3, ADR-0151).
 * @see com.astarworks.astera.combat.adapter.paper.StatusEffectTickListener for
 *      the per-tick expiry + DoT counterpart (Wave 6a-N2, ADR-0155).
 */
public class PaperStatusEffectContainer(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    @Suppress("UnusedPrivateProperty")
    private val statusEffectRegistry: IStatusEffectRegistry? = null,
) : IStatusEffectContainer {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Astera-canonical state. Outer key: player. Inner key: effect id.
     * Inner value: the most-recent applied [StatusEffectInstance] for that
     * id (stacking is represented inside the instance via `currentStacks`,
     * not by multiple map entries).
     *
     * ConcurrentHashMap so the [StatusEffectTickListener]'s scheduler-driven
     * tick loop can iterate without locking against the use-case caller on
     * main (both run on main, but the read-during-write guarantee is still
     * cleaner with a concurrent map).
     */
    public val activeEffects: MutableMap<PlayerId, MutableMap<StatusEffectId, StatusEffectInstance>> =
        ConcurrentHashMap()

    // ── IStatusEffectContainer ────────────────────────────────────────────

    override fun active(playerId: PlayerId): Collection<StatusEffectInstance> =
        activeEffects[playerId]?.values?.toList().orEmpty()

    override fun has(playerId: PlayerId, effectId: StatusEffectId): Boolean =
        activeEffects[playerId]?.containsKey(effectId) == true

    override fun apply(instance: StatusEffectInstance) {
        val targetId = instance.targetPlayerId
        val spec = instance.spec
        val effectId = spec.id

        // 1) Update Astera-canonical state (stacking semantics on this side).
        val perPlayer = activeEffects.computeIfAbsent(targetId) { ConcurrentHashMap() }
        val existing = perPlayer[effectId]
        val merged = when {
            existing == null -> instance
            !spec.stackable -> instance.copy(currentStacks = 1) // refresh duration, single stack
            else -> {
                val newStacks = (existing.currentStacks + 1).coerceAtMost(spec.maxStacks)
                instance.copy(currentStacks = newStacks)
            }
        }
        perPlayer[effectId] = merged

        // 2) Mirror to Bukkit (best-effort). Offline player → log + degrade.
        val player = server.getPlayer(targetId.uuid)
        if (player == null) {
            log.debug(
                "PaperStatusEffectContainer.apply: target {} offline; mirror skipped, Astera state retained",
                targetId,
            )
        } else {
            mirrorApply(player, merged)
        }

        // 3) Publish.
        eventBus.publish(
            StatusApplied(
                target = targetId,
                effect = merged,
                atTick = scheduler.currentTick(),
            ),
        )
    }

    override fun remove(playerId: PlayerId, effectId: StatusEffectId) {
        val perPlayer = activeEffects[playerId] ?: return
        val removed = perPlayer.remove(effectId) ?: return
        if (perPlayer.isEmpty()) activeEffects.remove(playerId)

        val player = server.getPlayer(playerId.uuid)
        if (player == null) {
            log.debug(
                "PaperStatusEffectContainer.remove: target {} offline; mirror skipped, Astera state cleared",
                playerId,
            )
        } else {
            mirrorRemove(player, removed.spec.kind)
        }

        eventBus.publish(
            StatusRemoved(
                target = playerId,
                effectId = effectId,
                reason = "manual",
                atTick = scheduler.currentTick(),
            ),
        )
    }

    override fun clear(playerId: PlayerId) {
        val perPlayer = activeEffects.remove(playerId) ?: return
        val player = server.getPlayer(playerId.uuid)
        val tick = scheduler.currentTick()
        perPlayer.values.forEach { inst ->
            if (player != null) mirrorRemove(player, inst.spec.kind)
            eventBus.publish(
                StatusRemoved(
                    target = playerId,
                    effectId = inst.spec.id,
                    reason = "clear",
                    atTick = tick,
                ),
            )
        }
        if (player == null) {
            log.debug(
                "PaperStatusEffectContainer.clear: target {} offline; mirror skipped, Astera state cleared",
                playerId,
            )
        }
    }

    // ── Bukkit mirror ─────────────────────────────────────────────────────

    private fun mirrorApply(player: Player, instance: StatusEffectInstance) {
        val potionType = bukkitPotionTypeFor(instance.spec.kind)
        if (potionType == null) {
            log.debug(
                "PaperStatusEffectContainer.mirrorApply: kind={} has no vanilla potion equivalent; skip",
                instance.spec.kind,
            )
            return
        }
        // Amplifier convention: stacks - 1 (vanilla Strength I = amplifier 0,
        // Strength II = amplifier 1, …). Astera magnitude is carried
        // independently — Bukkit amplifier is HUD-purposes only.
        val amplifier = (instance.currentStacks - 1).coerceAtLeast(0)
        player.addPotionEffect(
            PotionEffect(
                potionType,
                instance.remainingTicks,
                amplifier,
                /* ambient = */ false,
                /* particles = */ true,
                /* icon = */ true,
            ),
        )
    }

    private fun mirrorRemove(player: Player, kind: StatusKind) {
        val potionType = bukkitPotionTypeFor(kind) ?: return
        player.removePotionEffect(potionType)
    }

    /**
     * Map an Astera [StatusKind] to a vanilla [PotionEffectType] for the
     * mirror write. Only kinds with a sensible 1:1 vanilla analogue map;
     * Astera-specific kinds (STAR_GAIN family, CT_* family, GRAVITY_*,
     * JUMP_DISABLED) return null and the mirror write is skipped.
     *
     * BURN maps to vanilla `WITHER` (a darker-than-poison HUD tint) rather
     * than `POISON` so a separate Astera POISON-like kind (future) can take
     * the literal vanilla `POISON` slot. The tick damage itself is delivered
     * by [StatusEffectTickListener]; the Bukkit potion is icon-only.
     */
    private fun bukkitPotionTypeFor(kind: StatusKind): PotionEffectType? = when (kind) {
        StatusKind.SPEED_UP -> PotionEffectType.SPEED
        StatusKind.SPEED_DOWN -> PotionEffectType.SLOWNESS
        StatusKind.PARALYSIS -> PotionEffectType.SLOWNESS // applied at high amplifier; HUD only
        StatusKind.JUMP_DISABLED -> PotionEffectType.JUMP_BOOST // negative amplifier in vanilla; HUD-icon proxy
        StatusKind.BURN -> PotionEffectType.WITHER
        StatusKind.HEAL_AMP,
        StatusKind.HEAL_REDUCE,
        StatusKind.HEAL_NULL,
        StatusKind.STAR_GAIN,
        StatusKind.STAR_GAIN_BOOST,
        StatusKind.STAR_GAIN_NULL,
        StatusKind.GRAVITY_UP,
        StatusKind.GRAVITY_DOWN,
        StatusKind.CT_RATE_UP,
        StatusKind.CT_RATE_DOWN,
        StatusKind.CT_FREEZE,
        -> null
    }
}
