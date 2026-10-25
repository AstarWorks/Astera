package com.astarworks.astera.core.adapter.paper.physiology

import com.astarworks.astera.core.application.port.outbound.IPlayerMovementGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IPlayerMovementGateway] (Wave 6a-7, ADR-0152) —
 * the gravity-multiplier sibling of Wave 6a-4's
 * [PaperPlayerSpeedGateway].
 *
 * # Bukkit mapping
 *
 *  - `setGravityMultiplier(playerId, m, ttl)` → reads
 *    `player.getAttribute(Attribute.GRAVITY)` (Paper 1.21+ exposes a
 *    GRAVITY attribute with vanilla baseline `0.08`) and writes
 *    `attr.baseValue = GRAVITY_BASELINE * m`. The TTL is enforced by an
 *    `IScheduler.runLaterTicks` one-shot that calls [resetGravity].
 *  - `resetGravity(playerId)` → `attr.baseValue = GRAVITY_BASELINE` and
 *    drops the entry from the internal state map.
 *
 * Bukkit also exposes `Player.setGravity(Boolean)` for full gravity
 * disable, but that's coarse-grained (on/off) and would collide with
 * the multiplier intent — the attribute path is the only way to honour
 * `multiplier = 0.5` (slow-fall) or `multiplier = 2.0` (heavy-fall).
 *
 * # TTL semantics
 *
 * Matches [PaperPlayerSpeedGateway] exactly — `IScheduler.runLaterTicks`
 * one-shot expiry per player, cancelled on overwrite or explicit
 * [resetGravity]. The port returns `Unit` (no `Result`) so input
 * validation is best-effort: non-finite multipliers are clamped to
 * `1.0` and zero/negative TTLs are treated as "indefinite until reset".
 *
 * # Offline-player semantics
 *
 * Mirroring [PaperPlayerSpeedGateway]: every mutation checks
 * `server.getPlayer(playerId.uuid)` and skips the Bukkit-side
 * `Attribute.GRAVITY` write when null. The internal `multipliers` map
 * still tracks the intent so a later overwrite from another wave
 * doesn't lose the prior value's expiry tick (consistency with the
 * Wave 6a-4 / 6a-5 contract). Logged at DEBUG.
 *
 * # Threading
 *
 * Bukkit `AttributeInstance.setBaseValue` requires the main thread.
 * Same contract as ADR-0144 / 0145 / 0146 / 0147 / 0148 — off-main
 * callers must round-trip through `scheduler.runLater(Duration.ZERO,
 * ...)`.
 *
 * # Phase 1 PoC limitations
 *
 *  - `Attribute.GRAVITY` is the only Bukkit knob for granular gravity
 *    in Paper 1.21+. Older Paper builds (1.20.x) had only the boolean
 *    `setGravity` — the port is intentionally Paper-1.21+-only. If
 *    the attribute isn't available on a given player (e.g. NPC backend
 *    without attributes), the write silently logs DEBUG and continues.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCorePlugin.onEnable`. Wave 5g shipped only the
 * port + a Fake-level impl for tests; this adapter is the first
 * production-grade impl.
 *
 * @see IPlayerMovementGateway for the full contract.
 * @see PaperPlayerSpeedGateway for the sibling-pattern reference (Wave 6a-4, ADR-0147).
 */
public class PaperPlayerMovementGateway(
    private val server: Server,
    private val scheduler: IScheduler,
) : IPlayerMovementGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    private val multipliers: MutableMap<PlayerId, Double> = mutableMapOf()
    private val expiry: MutableMap<PlayerId, IScheduler.TaskHandle> = mutableMapOf()

    override fun setGravityMultiplier(playerId: PlayerId, multiplier: Double, ttlTicks: Int) {
        val safe = if (multiplier.isFinite() && multiplier >= 0.0) multiplier else {
            log.debug("PaperPlayerMovementGateway.setGravityMultiplier: invalid {} → clamped to 1.0", multiplier)
            1.0
        }
        multipliers[playerId] = safe
        expiry.remove(playerId)?.cancel()

        // Bukkit write — best-effort if online.
        onlinePlayer(playerId)?.let { player ->
            val attr = player.getAttribute(Attribute.GRAVITY)
            if (attr == null) {
                log.debug(
                    "PaperPlayerMovementGateway.setGravityMultiplier: target {} has no GRAVITY attribute (skipped)",
                    playerId,
                )
            } else {
                attr.baseValue = GRAVITY_BASELINE * safe
            }
        } ?: log.debug("PaperPlayerMovementGateway.setGravityMultiplier: target {} offline", playerId)

        // Indefinite when ttl <= 0; per port KDoc the implementation auto-resets
        // when ttlTicks elapses, but a non-positive TTL is treated as "until
        // reset()" to mirror PaperPlayerSpeedGateway's MAX_VALUE convention.
        if (ttlTicks > 0) {
            expiry[playerId] = scheduler.runLaterTicks(ttlTicks.toLong()) {
                resetGravity(playerId)
            }
        }
    }

    override fun resetGravity(playerId: PlayerId) {
        multipliers.remove(playerId) ?: return
        expiry.remove(playerId)?.cancel()
        onlinePlayer(playerId)?.let { player ->
            val attr = player.getAttribute(Attribute.GRAVITY)
            if (attr == null) {
                log.debug(
                    "PaperPlayerMovementGateway.resetGravity: target {} has no GRAVITY attribute (skipped)",
                    playerId,
                )
            } else {
                attr.baseValue = GRAVITY_BASELINE
            }
        } ?: log.debug("PaperPlayerMovementGateway.resetGravity: target {} offline", playerId)
    }

    private fun onlinePlayer(playerId: PlayerId): Player? = server.getPlayer(playerId.uuid)

    public companion object {
        /**
         * Vanilla Minecraft baseline for `Attribute.GRAVITY` in Paper 1.21+
         * (`0.08` blocks/tick²; documented in
         * https://minecraft.wiki/w/Attribute#Effective_Attributes). `m = 1.0`
         * ⇒ baseline, `m = 0.0` ⇒ no gravity (floaty), `m > 1.0` ⇒
         * accelerated fall.
         */
        public const val GRAVITY_BASELINE: Double = 0.08
    }
}
