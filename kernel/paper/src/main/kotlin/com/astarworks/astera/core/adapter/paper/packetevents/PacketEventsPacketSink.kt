package com.astarworks.astera.core.adapter.paper.packetevents

import com.astarworks.astera.core.adapter.api.hud.HudFrame
import com.astarworks.astera.core.adapter.api.hud.HudObjective
import com.astarworks.astera.core.adapter.api.hud.HudSurface
import com.astarworks.astera.core.adapter.api.hud.HudTeam
import com.astarworks.astera.core.adapter.api.hud.PacketSink
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.player.PlayerId
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.protocol.player.User
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.protocol.score.ScoreFormat
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** The only client scoreboard owner on both Paper and Folia (ADR-0260). */
public class PacketEventsPacketSink(private val server: Server, private val scheduler: IScheduler) : PacketSink, Listener, AutoCloseable {
    private class ViewerState(val session: Player) {
        // Read and mutated only on this player's Entity scope.
        val surfaces = mutableMapOf<HudSurface, HudFrame>()
    }
    private val viewers = ConcurrentHashMap<UUID, ViewerState>()
    private val mini = MiniMessage.miniMessage()
    private data class PendingKey(val session: Player, val surface: HudSurface)
    private val pending = ConcurrentHashMap<PendingKey, HudFrame>()
    private data class EncodingKey(val old: HudFrame, val new: HudFrame, val surface: HudSurface, val version: ClientVersion)
    private val encoded = linkedMapOf<EncodingKey, List<Any>>()
    @Volatile private var closed = false

    override fun submit(viewer: PlayerId, surface: HudSurface, frame: HudFrame) {
        if (closed) return
        val session = server.getPlayer(viewer.uuid) ?: return
        val key = PendingKey(session, surface)
        pending[key] = frame
        scheduler.run(ExecutionScope.Entity(viewer.uuid), Duration.ZERO) {
            if (!pending.remove(key, frame) || closed) return@run
            if (server.getPlayer(viewer.uuid) !== session || !session.isOnline) return@run
            val user = PacketEvents.getAPI().playerManager.getUser(session) ?: return@run
            val state = viewers.compute(viewer.uuid) { _, previous ->
                previous?.takeIf { it.session === session } ?: ViewerState(session)
            }!!
            val previous = state.surfaces[surface] ?: HudFrame()
            if (previous == frame) return@run
            val tabChanged = surface == HudSurface.TAB || surface == HudSurface.COSMETIC_TAB
            val before = if (tabChanged) tabFrame(state.surfaces) else previous
            val after = if (tabChanged) tabFrame(state.surfaces + (surface to frame)) else frame
            val wireSurface = if (tabChanged) HudSurface.TAB else surface
            for (buffer in buffers(before, after, wireSurface, user)) user.sendPacket(buffer)
            state.surfaces[surface] = frame
        }.onRetired {
            pending.remove(key, frame)
            viewers.computeIfPresent(viewer.uuid) { _, state -> state.takeUnless { it.session === session } }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onQuit(event: PlayerQuitEvent) {
        pending.keys.removeIf { it.session === event.player }
        viewers.computeIfPresent(event.player.uniqueId) { _, state -> state.takeUnless { it.session === event.player } }
    }

    /** Explicit tab rows override cosmetic names; cosmetics never change the header, footer or real ping. */
    private fun tabFrame(surfaces: Map<HudSurface, HudFrame>): HudFrame {
        val tab = surfaces[HudSurface.TAB] ?: HudFrame()
        return tab.copy(rows = surfaces[HudSurface.COSMETIC_TAB]?.rows.orEmpty() + tab.rows)
    }

    /** Encode a transition once per protocol version; each write owns a retained duplicate of the same buffers. */
    @Synchronized
    private fun buffers(old: HudFrame, new: HudFrame, surface: HudSurface, user: User): List<Any> {
        if (closed) return emptyList()
        val key = EncodingKey(old, new, surface, user.clientVersion)
        val cached = encoded.getOrPut(key) {
            val buffers = mutableListOf<Any>()
            try {
                for (packet in diff(old, new, surface)) {
                    buffers.addAll(PacketEvents.getAPI().protocolManager.transformWrappers(packet, user.channel, true))
                }
                buffers
            } catch (failure: Exception) {
                buffers.forEach { ByteBufHelper.release(it) }
                throw failure
            }
        }
        val retained = cached.map { ByteBufHelper.retainedDuplicate(it) }
        // ponytail: bounded FIFO under one encoding lock; shard only if HUD encoding contention is measured.
        if (encoded.size > 256) encoded.remove(encoded.keys.first())?.forEach { ByteBufHelper.release(it) }
        return retained
    }

    @Synchronized
    override fun close() {
        closed = true
        encoded.values.flatten().forEach { ByteBufHelper.release(it) }
        encoded.clear()
        pending.clear()
        viewers.clear()
    }

    private fun text(value: String): Component = mini.deserialize(value)

    private fun teamInfo(team: HudTeam): WrapperPlayServerTeams.ScoreBoardTeamInfo =
        WrapperPlayServerTeams.ScoreBoardTeamInfo(
            text(team.prefix), text(team.prefix), Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.ALWAYS, WrapperPlayServerTeams.CollisionRule.ALWAYS,
            NamedTextColor.NAMES.value(team.color.lowercase()) ?: NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE,
        )

    private fun objective(name: String, model: HudObjective, mode: WrapperPlayServerScoreboardObjective.ObjectiveMode): PacketWrapper<*> =
        WrapperPlayServerScoreboardObjective(name, mode, text(model.title),
            WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
            if (model.blankNumbers) ScoreFormat.blankScore() else null)

    /** Delete stale data first; create objectives/teams before scores and display-slot binding. */
    private fun diff(old: HudFrame, new: HudFrame, surface: HudSurface): List<PacketWrapper<*>> = buildList {
        for (key in old.scores.keys - new.scores.keys) {
            add(WrapperPlayServerResetScore(key.entry, key.objective))
        }
        for (name in old.objectives.keys - new.objectives.keys) {
            add(objective(name, old.objectives.getValue(name), WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE))
        }
        for (name in old.teams.keys - new.teams.keys) {
            add(WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.REMOVE, teamInfo(old.teams.getValue(name))))
        }
        // All membership removals precede additions: a player can move between colour groups in one frame.
        for ((name, team) in new.teams) {
            val gone = old.teams[name]?.members.orEmpty() - team.members
            if (gone.isNotEmpty()) add(WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES, teamInfo(team), gone))
        }
        for ((name, model) in new.objectives) {
            val previous = old.objectives[name]
            if (previous != model) add(objective(name, model,
                if (previous == null) WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE
                else WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE))
        }
        for ((name, team) in new.teams) {
            val previous = old.teams[name]
            if (previous == null) {
                add(WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.CREATE, teamInfo(team), team.members))
            } else {
                if (previous.prefix != team.prefix || previous.color != team.color) {
                    add(WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.UPDATE, teamInfo(team)))
                }
                val added = team.members - previous.members
                if (added.isNotEmpty()) add(WrapperPlayServerTeams(name, WrapperPlayServerTeams.TeamMode.ADD_ENTITIES, teamInfo(team), added))
            }
        }
        for ((key, value) in new.scores) if (old.scores[key] != value) {
            add(WrapperPlayServerUpdateScore(key.entry, WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
                key.objective, value, null, null))
        }
        for ((name, model) in new.objectives) if (old.objectives[name]?.slot != model.slot) {
            add(WrapperPlayServerDisplayScoreboard(model.slot, name))
        }
        if (surface == HudSurface.TAB) {
            if (old.header != new.header || old.footer != new.footer) {
                add(WrapperPlayServerPlayerListHeaderAndFooter(text(new.header), text(new.footer)))
            }
            for ((uuid, row) in old.rows) if (uuid !in new.rows && row.synthetic) add(WrapperPlayServerPlayerInfoRemove(uuid))
            for ((uuid, row) in new.rows) if (old.rows[uuid] != row) {
                val actions = EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME)
                if (row.latency != null) actions.add(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY)
                if (row.synthetic && old.rows[uuid] == null) {
                    actions.add(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)
                    actions.add(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED)
                }
                add(WrapperPlayServerPlayerInfoUpdate(actions, listOf(WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                    UserProfile(uuid, row.name), true, row.latency ?: 0, GameMode.SURVIVAL, text(row.displayName), null, 0))))
            }
        }
    }
}
