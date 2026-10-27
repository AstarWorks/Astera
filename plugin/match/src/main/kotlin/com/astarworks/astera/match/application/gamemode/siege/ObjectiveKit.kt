package com.astarworks.astera.match.application.gamemode.siege

import com.astarworks.astera.core.domain.player.physiology.SpeedAxis
import com.astarworks.astera.core.application.port.outbound.post
import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.ObjectiveBarColor
import com.astarworks.astera.match.application.port.outbound.ObjectiveLook
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The shared "objective feels good" kit of the carry / steal modes (Rainmaker-style): the carrier is slowed, glows and
 * wears the objective on the head; a pickup or deposit is a short channel with a progress bar; the loose objective is
 * a glowing marker with a light beam; everybody gets a compass arrow in the action bar. The kit holds no match rules,
 * only presentation state, and every call is a no-op for the ports the host did not provide.
 *
 * Tunables come from the mode's `default_config` (all optional):
 * `channel_ticks`, `drop_return_seconds`, `pickup_radius`, `warn_distance`, `carrier_slow_pct_per_item`,
 * `carrier_min_speed_pct`.
 */
public class ObjectiveKit(private val env: SiegeEnv, private val modeKey: String) {

    private class Channel(val key: String, val startedAt: Long)

    private val channels: MutableMap<PlayerId, Channel> = mutableMapOf()
    private val carriers: MutableSet<PlayerId> = mutableSetOf()

    public val channelTicks: Long get() = env.int("channel_ticks", DEFAULT_CHANNEL_TICKS).toLong()

    /** How long a dropped objective lies there before it goes home, in ticks. */
    public val dropReturnTicks: Long get() = env.int("drop_return_seconds", DEFAULT_DROP_RETURN_SECONDS) * 20L

    /** Horizontal reach (blocks) of a dropped objective's pickup. */
    public val pickupRadius: Double get() = env.int("pickup_radius", DEFAULT_PICKUP_RADIUS).toDouble()

    /** Distance (blocks) at which an enemy carrier counts as "at the gates" for the defenders. */
    public val warnDistance: Double get() = env.int("warn_distance", DEFAULT_WARN_DISTANCE).toDouble()

    /** Display name of the mode's objective for [viewer] ("TNT", "jewel", ...). */
    private val extraBars: MutableMap<PlayerId, MutableSet<String>> = mutableMapOf()

    public fun itemName(viewer: PlayerId): String = env.messages.render(viewer, MessageKey("astera.objective.item.$modeKey"))

    public fun positionOf(player: PlayerId, includeDead: Boolean = false): Vec3? =
        env.positions()?.positionOf(player, includeDead)?.position

    // ---- carrier ----------------------------------------------------------

    /** [player] carries [count] objective pieces: glow + block on the head + slowdown by `carrier_slow_pct_per_item` each. */
    public fun carry(player: PlayerId, count: Int, look: ObjectiveLook) {
        carriers += player
        env.stage()?.setCarrying(player, look, count)
        val pct = max(env.int("carrier_min_speed_pct", DEFAULT_MIN_SPEED_PCT), 100 - env.int("carrier_slow_pct_per_item", DEFAULT_SLOW_PCT) * count)
        env.speed()?.setMultiplier(player, SpeedAxis.WALK, pct / 100.0, Int.MAX_VALUE)
    }

    /** [player] no longer carries anything. */
    public fun uncarry(player: PlayerId) {
        if (!carriers.remove(player)) return
        env.stage()?.setCarrying(player, null, 0)
        env.speed()?.resetMultiplier(player, SpeedAxis.WALK)
    }

    /** [player] died or left: whatever the kit showed on them goes away. */
    public fun playerDown(player: PlayerId) {
        uncarry(player)
        env.guide()?.hide(player)
        cancelChannel(player)
    }

    /** Match over: strips every carrier, bar and marker of the match. */
    public fun endMatch(match: MatchId, players: Collection<PlayerId>) {
        for (player in players) {
            uncarry(player)
            cancelChannel(player)
            env.guide()?.hide(player)
            env.stage()?.hideBar(player, STATUS_BAR)
            extraBars.remove(player)?.forEach { env.stage()?.hideBar(player, it) }
        }
        env.stage()?.clearMatch(match, players)
    }

    // ---- channel ----------------------------------------------------------

    /**
     * One observation of [player] standing on [key] (`null` = on nothing actionable): shows the progress bar and
     * returns true on the observation that completes the hold. Moving to another target restarts it.
     */
    public fun channel(player: PlayerId, key: String?, label: MessageKey, color: ObjectiveBarColor = ObjectiveBarColor.GREEN): Boolean {
        if (key == null) {
            cancelChannel(player)
            return false
        }
        val now = env.now()
        val ch = channels[player]?.takeIf { it.key == key } ?: Channel(key, now).also {
            channels[player] = it
            moment(PresentationMoments.OBJECTIVE_CHANNEL, player, at = positionOf(player))
        }
        val need = channelTicks
        val progress = if (need <= 0L) 1.0 else ((now - ch.startedAt).toDouble() / need).coerceIn(0.0, 1.0)
        if (progress >= 1.0) {
            cancelChannel(player)
            return true
        }
        env.stage()?.showBar(player, CHANNEL_BAR, env.messages.render(player, label), progress, color)
        return false
    }

    private fun cancelChannel(player: PlayerId) {
        if (channels.remove(player) != null) env.stage()?.hideBar(player, CHANNEL_BAR)
    }

    // ---- world feedback ---------------------------------------------------

    public fun marker(match: MatchId, key: String, world: WorldId, at: Vec3, look: ObjectiveLook) {
        env.stage()?.showMarker(match, key, world, at, look)
    }

    public fun unmarker(match: MatchId, key: String) {
        env.stage()?.removeMarker(match, key)
    }

    /** A pillar of light over [at]; call about once a second. */
    public fun beam(world: WorldId, at: Vec3, colorName: String?) {
        val gateway = env.world ?: return
        val dust = dustOf(colorName)
        for (i in 0 until BEAM_HEIGHT) {
            gateway.spawnParticle(world, Vec3(at.x, at.y + i, at.z), ParticleSpec("minecraft:dust", 2, Vec3(0.15, 0.4, 0.15), 0.0, dust, 1.4f, force = true))
        }
        gateway.spawnParticle(world, Vec3(at.x, at.y + BEAM_HEIGHT, at.z), ParticleSpec("minecraft:end_rod", 3, Vec3(0.1, 0.5, 0.1), 0.02, force = true))
    }

    /**
     * The big team-wide delivery burst at [at]: two rings of dust, a firework fountain and a column of end rods, so a
     * bank reads from across the base. Call once per delivery.
     */
    public fun burst(world: WorldId, at: Vec3, colorName: String?) {
        val gateway = env.world ?: return
        val dust = dustOf(colorName)
        for (ring in 0 until BURST_RINGS) {
            val radius = 1.2 + ring * 1.3
            val y = at.y + 0.3 + ring * 0.7
            for (i in 0 until BURST_POINTS) {
                val a = 2 * PI * i / BURST_POINTS
                gateway.spawnParticle(world, Vec3(at.x + radius * cos(a), y, at.z + radius * sin(a)), ParticleSpec("minecraft:dust", 2, Vec3(0.05, 0.05, 0.05), 0.0, dust, 1.8f, force = true))
            }
        }
        gateway.spawnParticle(world, Vec3(at.x, at.y + 0.5, at.z), ParticleSpec("minecraft:firework", 60, Vec3(0.4, 0.2, 0.4), 0.35, force = true))
        gateway.spawnParticle(world, Vec3(at.x, at.y + 2.0, at.z), ParticleSpec("minecraft:end_rod", 40, Vec3(0.2, 1.5, 0.2), 0.08, force = true))
    }

    /** The carrier's wake: coloured dust at [at]. */
    public fun trail(world: WorldId, at: Vec3, colorName: String?) {
        env.world?.spawnParticle(world, Vec3(at.x, at.y + 1.0, at.z), ParticleSpec("minecraft:dust", 3, Vec3(0.3, 0.5, 0.3), 0.0, dustOf(colorName), 1.2f))
    }

    // ---- HUD ----------------------------------------------------------------

    /** The objective status bar of [player]. */
    public fun statusBar(player: PlayerId, key: MessageKey, params: Map<String, String>, progress: Double, color: ObjectiveBarColor) {
        env.stage()?.showBar(player, STATUS_BAR, env.messages.render(player, key, params), progress.coerceIn(0.0, 1.0), color)
    }

    /** A further boss bar [barKey] of [player] (e.g. one per team: its bank progress), next to the personal [statusBar]. */
    public fun bar(player: PlayerId, barKey: String, key: MessageKey, params: Map<String, String>, progress: Double, color: ObjectiveBarColor) {
        extraBars.getOrPut(player) { mutableSetOf() } += barKey
        env.stage()?.showBar(player, barKey, env.messages.render(player, key, params), progress.coerceIn(0.0, 1.0), color)
    }

    /** Hides the personal [statusBar] of [player]. */
    public fun hideStatusBar(player: PlayerId) {
        env.stage()?.hideBar(player, STATUS_BAR)
    }

    /** An action-bar toast. */
    public fun toast(player: PlayerId, key: MessageKey, params: Map<String, String> = emptyMap(), ttlTicks: Int = 30) {
        env.actionBar()?.post(player, key, params, ttlTicks = ttlTicks)
    }

    /** Action-bar line [key] with `{arrow}` (where [target] lies relative to where [player] looks) and `{dist}` (blocks). */
    public fun compass(player: PlayerId, target: Vec3, key: MessageKey, params: Map<String, String> = emptyMap()) {
        val pose = env.poses()?.poseOf(player)
        val from = pose?.position ?: positionOf(player) ?: return
        val dx = target.x - from.x
        val dz = target.z - from.z
        val arrow = if (pose == null) "" else arrowFor(pose.direction.x, pose.direction.z, dx, dz)
        toast(player, key, params + mapOf("arrow" to arrow, "dist" to hypot(dx, dz).roundToInt().toString()))
        pointGuide(player, target, key, params)
    }

    /**
     * The hologram arrow (ADR-0295) beside the action-bar compass: same target, label = the hint's `match.nav.*` twin.
     * Players who switched the HUD off get no hologram.
     */
    private fun pointGuide(player: PlayerId, target: Vec3, hint: MessageKey, params: Map<String, String>) {
        val guide = env.guide() ?: return
        if (env.settings()?.findOrDefault(player)?.hpLineVisible == false) {
            guide.hide(player)
            return
        }
        val label = env.messages.render(player, MessageKey(hint.value.replace(HINT_PREFIX, NAV_PREFIX)), params)
        guide.point(player, target, label)
    }

    /** The arrow glyph for where [target] lies relative to where [player] looks (empty when the pose is unknown). */
    public fun arrow(player: PlayerId, target: Vec3): String {
        val pose = env.poses()?.poseOf(player) ?: return ""
        return arrowFor(pose.direction.x, pose.direction.z, target.x - pose.position.x, target.z - pose.position.z)
    }

    /** Plays the presentation row [moment] for [subject]; [audience] hears / sees the bystander part. */
    public fun moment(moment: String, subject: PlayerId, args: Map<String, String> = emptyMap(), at: Vec3? = null, world: WorldId? = null, audience: Collection<PlayerId> = emptyList()) {
        env.presentation()?.play(moment, subject, args, at, world, audience)
    }

    /** [moment] for every member of [players] as its own subject (each reads the title in their language, with `{item}` filled in). */
    public fun broadcast(players: Collection<PlayerId>, moment: String, args: Map<String, String> = emptyMap(), at: Vec3? = null, world: WorldId? = null) {
        for (player in players) moment(moment, player, args + ("item" to itemName(player)), at, world)
    }

    private fun dustOf(colorName: String?): ParticleColor = when (colorName) {
        "red" -> ParticleColor(255, 70, 70)
        "blue" -> ParticleColor(80, 120, 255)
        "green" -> ParticleColor(80, 230, 110)
        "yellow", "gold" -> ParticleColor(255, 205, 60)
        else -> ParticleColor(255, 255, 255)
    }

    public companion object {
        public const val STATUS_BAR: String = "objective"
        public const val CHANNEL_BAR: String = "channel"
        private const val HINT_PREFIX: String = "objective.hint."
        private const val NAV_PREFIX: String = "match.nav."
        private const val BEAM_HEIGHT: Int = 8
        private const val BURST_RINGS: Int = 3
        private const val BURST_POINTS: Int = 18
        private const val DEFAULT_CHANNEL_TICKS: Int = 15
        private const val DEFAULT_DROP_RETURN_SECONDS: Int = 20
        private const val DEFAULT_PICKUP_RADIUS: Int = 3
        private const val DEFAULT_WARN_DISTANCE: Int = 24
        private const val DEFAULT_SLOW_PCT: Int = 30
        private const val DEFAULT_MIN_SPEED_PCT: Int = 60
        private val ARROWS: List<String> = listOf("↑", "↖", "←", "↙", "↓", "↘", "→", "↗")

        /** Eight-way arrow for a target offset ([dx], [dz]) seen from a viewer facing ([fx], [fz]). */
        public fun arrowFor(fx: Double, fz: Double, dx: Double, dz: Double): String {
            if (hypot(fx, fz) < 1e-6 || hypot(dx, dz) < 1e-6) return ARROWS[0]
            var rel = atan2(dx, dz) - atan2(fx, fz)
            while (rel > PI) rel -= 2 * PI
            while (rel <= -PI) rel += 2 * PI
            val octant = Math.floorMod((rel / (PI / 4)).roundToInt(), 8)
            return ARROWS[octant]
        }

        /** Boss-bar colour matching a team colour name. */
        public fun barColorOf(colorName: String?): ObjectiveBarColor = when (colorName) {
            "red" -> ObjectiveBarColor.RED
            "blue" -> ObjectiveBarColor.BLUE
            "green" -> ObjectiveBarColor.GREEN
            "yellow", "gold" -> ObjectiveBarColor.YELLOW
            else -> ObjectiveBarColor.WHITE
        }

        /** Block-grid distance on the ground plane. */
        public fun flatDistance(a: Vec3, b: Vec3): Double = hypot(a.x - b.x, a.z - b.z)

        /** Centre of [region] at [lift] above its floor. */
        public fun centreOf(region: com.astarworks.astera.core.domain.stage.Region, lift: Double = 0.0): Vec3 =
            Vec3((region.min.x + region.max.x) / 2, region.min.y + lift, (region.min.z + region.max.z) / 2)

        /** MiniMessage colour tag name for a team colour [name] (unknown colours read white). */
        public fun tagOf(name: String?): String = when (name) {
            "red", "blue", "green", "yellow", "gold", "aqua" -> name
            else -> "white"
        }

        /** Team colour name for glow / dust of [team] (`red`, `blue`, ...). */
        public fun colorOf(team: TeamId): String = team.value
    }
}
