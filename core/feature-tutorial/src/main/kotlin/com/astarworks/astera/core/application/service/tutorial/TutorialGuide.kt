package com.astarworks.astera.core.application.service.tutorial

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.inbound.ITutorialSequencerService
import com.astarworks.astera.core.application.port.outbound.IHologramGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.*
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialStep

/** Presentation is rebuilt from the persisted cursor on join; leaving the lobby only suspends it. */
public class TutorialGuide(
    private val sequencer: ITutorialSequencerService,
    private val server: IMcServer,
    private val scheduler: IScheduler,
    private val holograms: IHologramGateway,
    private val range: () -> ILobbyTrainingRange?,
    private val eligible: (PlayerId) -> Boolean,
    private val landmark: (String) -> Vec3?,
    /** UI-03: false when the player switched tutorial hints off; the guide hides and waits (the step is kept). */
    private val hintsEnabled: (PlayerId) -> Boolean = { true },
) {
    private data class Showing(val action: String, val since: Long, val marker: HologramHandle?)
    private val players = mutableSetOf<PlayerId>()
    private val showing = mutableMapOf<PlayerId, Showing>()

    public fun joined(player: PlayerId) { players += player }

    public fun left(player: PlayerId) { hide(player); players -= player }

    public fun refresh(player: PlayerId) {
        players += player
        tickPlayer(player)
    }

    public fun tick() { players.toList().forEach { id -> scheduler.run(ExecutionScope.Entity(id.uuid), kotlin.time.Duration.ZERO) { tickPlayer(id) } } }

    private fun tickPlayer(id: PlayerId) {
        val player = server.findPlayer(id)
        if (player == null) {
            hide(id)
            players -= id
            return
        }
        val step = sequencer.currentStep(id) as? TutorialStep.WaitForAction
        if (step == null || step.actionId !in ACTIONS) {
            hide(id)
            players -= id
            return
        }
        if (!eligible(id) || !hintsEnabled(id)) {
            hide(id)
            return
        }
        val action = step.actionId
        if (showing[id]?.action != action) {
            hide(id)
            player.sendTitle(key(action, "title"), key(action, "subtitle"))
            val training = range()
            val target = target(action) ?: player.location
            val marker = training?.let {
                holograms.spawn(Hologram(
                    id = HologramId.random(), worldId = it.world,
                    position = Vec3(target.x, target.y + 2.5, target.z),
                    kind = HologramKind.Text(listOf(key(action, "title")), shadowed = true, seeThrough = true),
                    billboard = BillboardMode.CENTER,
                    visibilityScope = HologramVisibilityScope.PerPlayer(setOf(id)),
                )).getOrNull()
            }
            showing[id] = Showing(action, scheduler.currentTick(), marker)
        }
        val shownFor = scheduler.currentTick() - checkNotNull(showing[id]).since
        // The skip line stays reachable on every step: it takes the bar for 1.5 s out of every 5 s.
        player.postActionBar(if (shownFor % SKIP_CYCLE_TICKS >= SKIP_FROM_TICK) SKIP_KEY else key(action, "hint"))
        // The HUD step teaches reading, not doing: four uninterrupted seconds.
        if (action == "hud" && shownFor >= 80) {
            sequencer.recordAction(id, action)
        }
    }

    private fun target(action: String): Vec3? {
        val plan = range()?.plan
        return when (action) {
            "range" -> plan?.spawn
            "rack", "reload", "staff" -> plan?.rackSlots?.firstOrNull()?.let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
            "primary", "secondary", "hud" -> plan?.dummies?.firstOrNull { it.id == "melee-1" }?.position
            else -> landmark(action)
        }
    }

    public fun hide(player: PlayerId) {
        showing.remove(player)?.marker?.let { holograms.despawn(it) }
    }

    public fun close() {
        showing.keys.toList().forEach(::hide)
        players.clear()
    }

    public companion object {
        public val ACTIONS: List<String> = listOf("menu", "primary", "secondary", "hud", "queue")
        private const val SKIP_CYCLE_TICKS: Long = 100L
        private const val SKIP_FROM_TICK: Long = 70L
        private val SKIP_KEY: MessageKey = MessageKey("astera.tutorial.guide.skip")
        public fun key(action: String, part: String): MessageKey = MessageKey("astera.tutorial.guide.$action.$part")
    }
}
