package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IDamageGateway
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper concrete of [IDamageGateway] (Wave 6a-3, ADR-0146) — closes the
 * ADR-0137 damage/death-cycle Path 1 contract.
 *
 * # Path 1 (Astera combat death) per ADR-0137
 *
 * Flow:
 *
 *  1. `ApplyDamageUseCase.execute(attempt)` calls
 *     `damageGateway.apply(target, resolved, source)` here.
 *  2. We `getPlayer(target.uuid)` → null = `TargetOffline`.
 *  3. `player.isDead` → return `TargetAlreadyDead`.
 *  4. `player.noDamageTicks > 0` → return `Immune` (Bukkit invulnerability
 *     frames after a recent hit).
 *  5. Add `target` to [appliedThisTick] marker so a future Wave 6a-6
 *     `BukkitEventAdapter.onPlayerDeath` handler can detect that this
 *     death came from Path 1 (Astera combat) and SKIP the Path 2
 *     vanilla-death PlayerKilled publish — preventing the double-fire
 *     that would otherwise pollute `AssistAttributionUseCase`.
 *  6. `player.damage(resolved.finalAmount)` — Bukkit fires
 *     `EntityDamageEvent` (vanilla particles + sound) and, if HP→0,
 *     `PlayerDeathEvent` synchronously inside this call.
 *  7. Read post-damage `player.health` → return
 *     `HpReduced(remainingHp)` or `Killed`.
 *  8. `ApplyDamageUseCase` publishes `PlayerKilled` on
 *     `DamageOutcome.Killed` (existing line ~196-205).
 *
 * # Path 2 (vanilla/non-combat death) — NOT in this wave
 *
 * ADR-0137 §Path 2 design assigns vanilla-death routing
 * (suicide / void / fall / `/kill`) to a future Wave 6a-6
 * `BukkitEventAdapter.onPlayerDeath` handler that consults
 * [appliedThisTick] to distinguish Path 1 (skip) from Path 2 (synthesize
 * `DamageSource.Environment.<cause>` from `EntityDamageEvent.DamageCause`
 * + publish `PlayerKilled(killer = null, source = synthesized)`).
 *
 * This wave ships ONLY Path 1. Vanilla deaths in Phase 1 PoC remain
 * untracked by Astera (they fire vanilla death message + drops via
 * Bukkit's default path); the [appliedThisTick] marker is wired so
 * Wave 6a-6 can ship Path 2 without changing this gateway.
 *
 * # `appliedThisTick` lifecycle
 *
 * Cleared by a 1-tick scheduler task started in [start]; held by a
 * `ConcurrentHashMap`-backed `Set<PlayerId>`. Bounded by "max players
 * receiving damage in a single tick" — typically <10 even on busy
 * servers. The clear runs on the main thread (matching
 * `Player.damage` ordering); the data structure is concurrent only as a
 * defensive choice in case future off-main consumers (Wave 6a-6
 * adapter) read it.
 *
 * # Threading
 *
 * `Player.damage` MUST run on main. `ApplyDamageUseCase.execute` is
 * called from event handlers (Wave 6a-6 BukkitEventAdapter) and combat
 * use cases that already run on main. Off-main callers MUST round-trip
 * through `scheduler.runLater(Duration.ZERO, ...)`.
 *
 * # Registration
 *
 * Registered at priority 100 in `AsteraCombatPlugin.onEnable` (overlays
 * `InMemoryDamageGateway` priority 0 from Wave Q / ADR-0133). Caller
 * must invoke [start] right after construction to wire the 1-tick clear
 * task; the returned `TaskHandle` is held by the plugin's subscription
 * list for `onDisable` cancellation per ADR-0123.
 */
public class PaperDamageGateway(
    private val server: Server,
    private val scheduler: IScheduler,
) : IDamageGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Per-tick marker: players whose damage was applied via Path 1
     * (this gateway). Read by Wave 6a-6 BukkitEventAdapter.onPlayerDeath
     * to skip Path 2 (vanilla-death) handler. Cleared by [start]'s 1-tick
     * scheduler task.
     *
     * Public + concurrent so Wave 6a-6 can read across tick boundaries
     * defensively. ADR-0137 §PaperDamageGateway code sketch.
     */
    public val appliedThisTick: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()

    /**
     * Wire the 1-tick clear task. Returns the handle so the plugin can
     * cancel on `onDisable`. Idempotent within a single instance — calling
     * twice schedules two clear tasks (which is harmless but wasteful;
     * the second clear no-ops on an already-empty set).
     */
    public fun start(): IScheduler.TaskHandle =
        scheduler.runRepeatingTicks(initialDelayTicks = 1L, periodTicks = 1L) {
            appliedThisTick.clear()
        }

    override fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
    ): Result<DamageOutcome, DamageApplicationError> {
        val player = server.getPlayer(target.uuid)
            ?: return Result.failure(DamageApplicationError.TargetOffline(target))
        if (player.isDead) {
            return Result.failure(DamageApplicationError.TargetAlreadyDead(target))
        }
        if (player.noDamageTicks > 0) {
            return Result.failure(DamageApplicationError.Immune(target))
        }
        // Mark BEFORE calling Bukkit damage so Bukkit's synchronous
        // PlayerDeathEvent firing inside damage() sees the marker.
        appliedThisTick.add(target)
        player.damage(resolved.finalAmount)
        val postHp = player.health
        return if (postHp <= 0.0) {
            Result.success(DamageOutcome.Killed)
        } else {
            Result.success(DamageOutcome.HpReduced(remainingHp = postHp))
        }
    }
}
