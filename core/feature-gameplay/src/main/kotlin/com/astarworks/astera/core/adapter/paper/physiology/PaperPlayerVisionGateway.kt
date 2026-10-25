package com.astarworks.astera.core.adapter.paper.physiology

import com.astarworks.astera.core.application.port.outbound.IPlayerVisionGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IPlayerVisionGateway] (Wave 6a-7, ADR-0152) —
 * approximates the FogOfWar anomaly's "constrict visible radius" using
 * Bukkit's vanilla [PotionEffectType.BLINDNESS].
 *
 * # Bukkit mapping
 *
 *  - `setFogRadius(playerId, radiusBlocks, ttlTicks)` →
 *    `player.addPotionEffect(PotionEffect(BLINDNESS, ttlTicks, amplifier=0))`.
 *    Vanilla BLINDNESS forces a tight render radius (~16 blocks) and
 *    overlays a darkening fog post-1.19; the port's [radiusBlocks]
 *    argument is **recorded** in the internal `fogRadius` map but the
 *    Bukkit-side write is binary (BLINDNESS on/off) — the radius
 *    parameter only affects the Wave 6b custom-packet adapter when it
 *    lands.
 *  - `resetFog(playerId)` →
 *    `player.removePotionEffect(PotionEffectType.BLINDNESS)` + state-map
 *    cleanup.
 *
 * # TTL semantics
 *
 * Bukkit's [PotionEffect] carries its own duration so we delegate
 * expiry to Bukkit native — no `IScheduler` task tracking the BLINDNESS
 * lifetime. This is the deliberate divergence from
 * [PaperPlayerSpeedGateway] / [PaperPlayerMovementGateway] (which both
 * schedule expiry via `IScheduler.runLaterTicks`): doubling up Bukkit's
 * potion-tick + an Astera-side scheduler would create the classic
 * "two timers, one truth" bug where overlap windows desync. The
 * trade-off: a query of `currentFogRadius` after expiry returns the
 * stale value until the next overwrite or [resetFog], which is
 * acceptable for the FogOfWar anomaly's bounded-cutscene use case
 * (KDoc on [IPlayerVisionGateway] permits "auto-resets once the TTL
 * elapses").
 *
 * # Offline-player semantics
 *
 * Mirrors [PaperPlayerSpeedGateway]: every mutation checks
 * `server.getPlayer(playerId.uuid)` and skips the Bukkit-side
 * `addPotionEffect` / `removePotionEffect` write when null. The
 * internal `fogRadius` map still tracks the intent. Logged at DEBUG.
 *
 * # Threading
 *
 * Bukkit `Player.addPotionEffect` requires the main thread. Same
 * contract as ADR-0144 / 0145 / 0146 / 0147 / 0148 — off-main callers
 * must round-trip through `scheduler.runLater(Duration.ZERO, ...)`.
 *
 * # Phase 1 PoC limitations
 *
 *  - The [radiusBlocks] parameter is **honoured by intent only** — the
 *    Bukkit-side write is vanilla BLINDNESS (binary). Wave 6b lands a
 *    custom-packet adapter (`ClientboundSetChunkCacheRadiusPacket` per-
 *    player) for true per-player render-distance constriction; until
 *    then the radius is recorded for HUD subscribers but unenforced.
 *  - BLINDNESS icon in the buff bar is visible to the player. The port
 *    KDoc explicitly says "icon suppression is the Paper adapter's
 *    responsibility" but Paper has no public API for per-player icon
 *    hiding without packet-level intervention — also deferred to
 *    Wave 6b.
 *  - The Wave 5g `FakePlayerVisionGateway` Fake-level signature is
 *    matched exactly here; downstream tests can pin
 *    `currentFogRadius == radiusBlocks` (read your own writes).
 *
 * # Registration
 *
 * Priority 100 in `AsteraCorePlugin.onEnable`. Wave 5g shipped only the
 * port + a Fake-level impl for tests; this adapter is the first
 * production-grade impl.
 *
 * @see IPlayerVisionGateway for the full contract.
 * @see PaperPlayerSpeedGateway for the sibling-pattern reference (Wave 6a-4, ADR-0147).
 */
public class PaperPlayerVisionGateway(
    private val server: Server,
    @Suppress("unused") private val scheduler: IScheduler,
) : IPlayerVisionGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    private val fogRadius: MutableMap<PlayerId, Int> = mutableMapOf()

    override fun setFogRadius(playerId: PlayerId, radiusBlocks: Int, ttlTicks: Int) {
        val safeRadius = radiusBlocks.coerceAtLeast(0)
        val safeTtl = if (ttlTicks > 0) ttlTicks else PERMANENT_TICKS
        fogRadius[playerId] = safeRadius

        onlinePlayer(playerId)?.let { player ->
            // Amplifier 0 = BLINDNESS I (vanilla fog tightening). Higher
            // amplifiers don't intensify the fog further per vanilla; we
            // pin 0 for predictability.
            // ambient=false + particles=false + icon=true (icon hiding
            // requires Wave 6b custom packet path).
            player.addPotionEffect(
                PotionEffect(
                    PotionEffectType.BLINDNESS,
                    safeTtl,
                    BLINDNESS_AMPLIFIER,
                    false, // ambient
                    false, // particles
                    true, // icon (Phase 1: visible; Wave 6b hides via packets)
                ),
            )
        } ?: log.debug("PaperPlayerVisionGateway.setFogRadius: target {} offline", playerId)
    }

    override fun resetFog(playerId: PlayerId) {
        fogRadius.remove(playerId) ?: return
        onlinePlayer(playerId)?.let { player ->
            player.removePotionEffect(PotionEffectType.BLINDNESS)
        } ?: log.debug("PaperPlayerVisionGateway.resetFog: target {} offline", playerId)
    }

    /** Test / HUD accessor: the most recently requested fog radius, or `null`. */
    public fun currentFogRadius(playerId: PlayerId): Int? = fogRadius[playerId]

    private fun onlinePlayer(playerId: PlayerId): Player? = server.getPlayer(playerId.uuid)

    public companion object {
        /**
         * Vanilla BLINDNESS amplifier pinned at I (`0`). Higher levels
         * do not tighten fog further; the value is recorded only to
         * keep call sites readable.
         */
        public const val BLINDNESS_AMPLIFIER: Int = 0

        /**
         * Sentinel TTL applied when callers pass `ttlTicks <= 0`
         * ("indefinite"). Bukkit treats `Int.MAX_VALUE` as effectively
         * permanent (≈ 3.4 yr at 20 TPS); the explicit named constant
         * documents the intent at call sites.
         */
        public const val PERMANENT_TICKS: Int = Int.MAX_VALUE
    }
}
