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
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialStep
import org.joml.Quaternionf
import kotlin.math.sin

/**
 * Presentation of the "how to progress" tutorial (UI-11): per step a title, a 2-4 line explanation in chat, and a
 * per-player hologram assist (speech bubble, plus a bobbing arrow over the target NPC). Rebuilt from the persisted
 * cursor on join; leaving the lobby only suspends it. Self-contained: no dependency on any other guide port.
 */
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
    /** Chat text for the step explanation (a MiniMessage string already rendered for the player). */
    private val render: (PlayerId, MessageKey) -> String = { _, key -> key.value },
    /** Step-completion feedback hook (the wiring plays the sound cue). */
    private val onStepDone: (PlayerId) -> Unit = {},
) {
    private data class Showing(
        val action: String,
        val since: Long,
        val bubble: HologramHandle?,
        val arrow: HologramHandle?,
        val anchor: Vec3?,
    )
    private val players = mutableSetOf<PlayerId>()
    private val showing = mutableMapOf<PlayerId, Showing>()
    private val lastIndex = mutableMapOf<PlayerId, Int>()
    private val justDone = mutableSetOf<PlayerId>()
    /** Actions that happened while the player was away from the lobby (a match); applied once they are back. */
    private val noted = mutableMapOf<PlayerId, MutableSet<String>>()

    public fun joined(player: PlayerId) { players += player }

    public fun left(player: PlayerId) {
        hide(player)
        players -= player
        lastIndex.remove(player)
        justDone -= player
        noted.remove(player)
    }

    /** [stepIndex] (from TutorialStepEntered) lets the guide tell "advanced one step" from "resumed". */
    public fun refresh(player: PlayerId, stepIndex: Int? = null) {
        players += player
        if (stepIndex != null) {
            val previous = lastIndex.put(player, stepIndex)
            if (previous != null && stepIndex == previous + 1) justDone += player
        }
        tickPlayer(player)
    }

    /**
     * Remembers that [action] happened for [player] while the lobby presentation was suspended (finishing a match
     * happens in the match). Applied on the next tick in the lobby, only if that is still the current step.
     */
    public fun note(player: PlayerId, action: String) {
        if ((sequencer.currentStep(player) as? TutorialStep.WaitForAction)?.actionId == action) {
            noted.getOrPut(player) { mutableSetOf() } += action
        }
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
        if (noted[id]?.remove(action) == true) {
            sequencer.recordAction(id, action)
            return
        }
        if (showing[id]?.action != action) {
            hide(id)
            if (justDone.remove(id)) {
                player.sendTitle(DONE_TITLE, key(action, "title"))
                onStepDone(id)
            } else {
                player.sendTitle(key(action, "title"), key(action, "subtitle"))
            }
            player.sendMessage(render(id, key(action, "body")))
            val anchor = landmark(action) ?: player.location
            val world = range()?.world
            val bubble = world?.let {
                holograms.spawn(Hologram(
                    id = HologramId.random(), worldId = it,
                    position = Vec3(anchor.x, anchor.y + BUBBLE_HEIGHT, anchor.z),
                    kind = HologramKind.Text(listOf(key(action, "bubble")), lineWidth = 140, shadowed = true, seeThrough = true, defaultBackground = true),
                    billboard = BillboardMode.CENTER,
                    visibilityScope = HologramVisibilityScope.PerPlayer(setOf(id)),
                )).getOrNull()
            }
            val arrow = if (action in POINTED) world?.let {
                holograms.spawn(Hologram(
                    id = HologramId.random(), worldId = it,
                    position = Vec3(anchor.x, anchor.y + ARROW_HEIGHT, anchor.z),
                    kind = HologramKind.Item(ARROW_ITEM, ItemDisplayMode.FIXED),
                    // The vanilla arrow sprite points up-right; roll it so the tip points straight down at the target.
                    transform = HologramTransform(leftRotation = Quaternionf().rotateZ(Math.toRadians(-135.0).toFloat()), scale = Vec3(1.2, 1.2, 1.2)),
                    billboard = BillboardMode.CENTER,
                    interpolation = HologramInterpolation(teleportDurationTicks = 10),
                    visibilityScope = HologramVisibilityScope.PerPlayer(setOf(id)),
                )).getOrNull()
            } else null
            showing[id] = Showing(action, scheduler.currentTick(), bubble, arrow, anchor)
        }
        val shown = checkNotNull(showing[id])
        val shownFor = scheduler.currentTick() - shown.since
        shown.arrow?.let { handle ->
            val anchor = checkNotNull(shown.anchor)
            holograms.teleport(handle, Vec3(anchor.x, anchor.y + ARROW_HEIGHT + 0.25 * sin(shownFor * 0.4), anchor.z))
        }
        // The skip line stays reachable on every step: it takes the bar for 1.5 s out of every 5 s.
        player.postActionBar(if (shownFor % SKIP_CYCLE_TICKS >= SKIP_FROM_TICK) SKIP_KEY else key(action, "hint"))
    }

    public fun hide(player: PlayerId) {
        showing.remove(player)?.let { shown ->
            shown.bubble?.let { holograms.despawn(it) }
            shown.arrow?.let { holograms.despawn(it) }
        }
    }

    public fun close() {
        showing.keys.toList().forEach(::hide)
        players.clear()
        lastIndex.clear()
        justDone.clear()
        noted.clear()
    }

    public companion object {
        public val ACTIONS: List<String> = listOf("menu", "queue", "match", "result", "shop", "equip", "missions")
        /** Steps whose target is an NPC: they get the arrow over its head as well as the bubble. */
        private val POINTED: Set<String> = setOf("queue", "match", "shop")
        private val ARROW_ITEM: ItemId = ItemId("gui-tile-back")
        private const val BUBBLE_HEIGHT: Double = 2.9
        private const val ARROW_HEIGHT: Double = 2.0
        private const val SKIP_CYCLE_TICKS: Long = 100L
        private const val SKIP_FROM_TICK: Long = 70L
        private val SKIP_KEY: MessageKey = MessageKey("astera.tutorial.guide.skip")
        private val DONE_TITLE: MessageKey = MessageKey("astera.tutorial.guide.done.title")
        public fun key(action: String, part: String): MessageKey = MessageKey("astera.tutorial.guide.$action.$part")
    }
}
