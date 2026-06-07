package com.astarworks.astera.core.adapter.paper.packetevents

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * PacketEvents-backed [ITeamPacketSender] (ADR-0171 Wave 6c-stub-
 * packetevents-B).
 *
 * Sends a clientbound set-player-team UPDATE packet to a single viewer,
 * overriding the team's display info (prefix carries the visible sidebar
 * text) with that viewer's locale rendering — while the shared Bukkit
 * scoreboard keeps broadcasting the default-locale baseline to everyone
 * else.
 *
 * ## Isolation contract (mirror of [PacketEventsBootstrap])
 *
 * This class — alongside [PacketEventsBootstrap] and
 * [PaperPacketInterceptor] — is the **only** code in Astera allowed to
 * import `com.github.retrooper.packetevents.*`. AsteraCorePlugin constructs
 * it strictly inside the `if (packetEventsInstalled)` gate, so when
 * PacketEvents is absent the JVM never loads this class and the
 * `com.github.retrooper.packetevents.*` symbols stay unresolved per JLS
 * §5.3.4 (lazy resolution at first active use). Construction itself is
 * side-effect-free: `PacketEvents.getAPI()` is only touched inside
 * [sendTeamUpdate], by which point AsteraCorePlugin's `onEnable` has
 * already completed the PE `init()` phase.
 *
 * ## Why UPDATE mode needs no entity list
 *
 * `ClientboundSetPlayerTeamPacket` only serializes the member-entity list
 * for the CREATE / ADD_ENTITIES / REMOVE_ENTITIES modes; UPDATE carries
 * display info exclusively. The viewer's client already holds the team
 * (created by the shared Bukkit scoreboard broadcast), so the override
 * rewrites its display info in place without touching membership.
 *
 * ## Why vanilla defaults (ALWAYS / ALWAYS / WHITE / NONE)
 *
 * UPDATE replaces the *entire* display-info block, so every field must be
 * supplied. The per-viewer override only cares about display text — the
 * Paper scoreboard adapters never customize name-tag visibility, collision,
 * color, or friendly-fire options (Bukkit defaults throughout, per the
 * Phase 1 PoC limits in `PaperTeamScoreboard`) — so the vanilla defaults
 * are restated verbatim rather than read back from the Bukkit team.
 *
 * ## Threading
 *
 * Safe from any thread: `User.sendPacket` ends in a Netty
 * `writeAndFlush`. Astera's scoreboard mutations run on the main server
 * thread, so callers do not marshal.
 */
public class PacketEventsTeamPacketSender : ITeamPacketSender {

    override fun sendTeamUpdate(player: Player, teamName: String, text: Component) {
        val info = WrapperPlayServerTeams.ScoreBoardTeamInfo(
            text, // displayName
            text, // prefix — carries the visible sidebar text
            Component.empty(), // suffix
            WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
            WrapperPlayServerTeams.CollisionRule.ALWAYS,
            NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE,
        )
        val packet = WrapperPlayServerTeams(teamName, WrapperPlayServerTeams.TeamMode.UPDATE, info)
        PacketEvents.getAPI().playerManager.getUser(player).sendPacket(packet)
    }
}
