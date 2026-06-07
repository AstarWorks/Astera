package com.astarworks.astera.core.adapter.paper.packetevents

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.EnumSet
import java.util.UUID

/**
 * PacketEvents-backed [ITabListPacketSender] (ADR-0172 Wave 6c-stub-
 * packetevents-C).
 *
 * Sends per-viewer `WrapperPlayServerPlayerInfoUpdate` packets so each
 * client sees its own display-name + latency badge for the listed
 * subjects — while the shared Player List packet keeps broadcasting the
 * default-locale / no-badge baseline to everyone else.
 *
 * ## Isolation contract (mirror of [PacketEventsTeamPacketSender])
 *
 * This class — alongside [PacketEventsBootstrap], [PaperPacketInterceptor],
 * and [PacketEventsTeamPacketSender] — is one of the few in Astera
 * allowed to import `com.github.retrooper.packetevents.*`. AsteraCorePlugin
 * constructs it strictly inside the `if (packetEventsInstalled)` gate, so
 * when PacketEvents is absent the JVM never loads this class and the
 * `com.github.retrooper.packetevents.*` symbols stay unresolved per JLS
 * §5.3.4 (lazy resolution at first active use). Construction itself is
 * side-effect-free: `PacketEvents.getAPI()` is only touched inside the
 * `send*` methods, by which point AsteraCorePlugin's `onEnable` has
 * already completed the PE `init()` phase.
 *
 * ## PE 2.x `PlayerInfo` constructor choice
 *
 * PE 2.12.x exposes a 7-arg [WrapperPlayServerPlayerInfoUpdate.PlayerInfo]
 * `(profile, listed, latency, gameMode, displayName?, chatSession?,
 * listOrder)` plus a deprecated `Optional<Component>` variant. We use the
 * nullable-Component overload and fill in the fields irrelevant to the
 * requested action with neutral defaults — PE serialises only the fields
 * whose `Action` is present in the EnumSet, so the placeholders never
 * reach the wire. `UserProfile(uuid, null)` is the minimum profile shape
 * the constructor accepts; the client already holds the real profile for
 * the targeted subject (the shared Player List packet sent it at join),
 * so the placeholder profile is harmless for UPDATE_* actions.
 *
 * ## Threading
 *
 * Safe from any thread: `User.sendPacket` ends in a Netty
 * `writeAndFlush`. Astera's tab-list mutations run on the main server
 * thread, so callers do not marshal.
 */
public class PacketEventsTabListPacketSender : ITabListPacketSender {

    override fun sendDisplayNameUpdate(viewer: Player, subjectUuid: UUID, displayName: Component) {
        val info = WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            UserProfile(subjectUuid, null),
            false,             // listed — not serialised for UPDATE_DISPLAY_NAME
            0,                 // latency — not serialised for UPDATE_DISPLAY_NAME
            GameMode.SURVIVAL, // gameMode — not serialised for UPDATE_DISPLAY_NAME
            displayName,       // the field this UPDATE actually changes
            null,              // chatSession
            0,                 // listOrder — not serialised for UPDATE_DISPLAY_NAME
        )
        val packet = WrapperPlayServerPlayerInfoUpdate(
            EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME),
            listOf(info),
        )
        PacketEvents.getAPI().playerManager.getUser(viewer).sendPacket(packet)
    }

    override fun sendLatencyUpdate(viewer: Player, subjectUuid: UUID, latencyMs: Int) {
        val info = WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            UserProfile(subjectUuid, null),
            false,
            latencyMs.coerceAtLeast(0), // the field this UPDATE actually changes
            GameMode.SURVIVAL,
            null,
            null,
            0,
        )
        val packet = WrapperPlayServerPlayerInfoUpdate(
            EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY),
            listOf(info),
        )
        PacketEvents.getAPI().playerManager.getUser(viewer).sendPacket(packet)
    }
}
