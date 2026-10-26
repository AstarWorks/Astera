package com.astarworks.astera.core.adapter.paper.packetevents

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * Per-viewer scoreboard-team display override hook (ADR-0171 Wave
 * 6c-stub-packetevents-B).
 *
 * Implementations send a clientbound set-player-team UPDATE packet to
 * [player] **only** — the rest of the server keeps whatever the shared
 * Bukkit scoreboard last broadcast. This is the seam that lets
 * `PaperScoreboard` / `PaperTeamScoreboard` render each viewer's locale:
 * the shared Bukkit board carries the default-locale baseline, and a
 * per-viewer UPDATE overrides the team's display info (the `prefix`
 * carries the visible sidebar text) with that viewer's translation.
 *
 * ## Isolation contract (mirror of [PacketEventsBootstrap])
 *
 * This interface deliberately uses only Bukkit / Adventure types — never
 * a PacketEvents symbol — so consumers stay loadable on vanilla Paper
 * (no `NoClassDefFoundError` per JLS §5.3.4). The PacketEvents-backed
 * implementation ([PacketEventsTeamPacketSender]) is the **only** class
 * allowed to import `com.github.retrooper.packetevents.*`, and it is
 * constructed exclusively inside `AsteraCorePlugin`'s
 * `if (packetEventsInstalled)` gate. When PacketEvents is absent the
 * consumers receive `null` and skip the fan-out entirely.
 *
 * ## Wire-name precondition
 *
 * [teamName] must be **byte-identical** to the name the shared Bukkit
 * scoreboard registered the team under (≤16 chars — derive via
 * `ScoreboardTeamNames`). A mismatched name targets a team the client
 * never created and the UPDATE is silently dropped.
 *
 * ## Threading
 *
 * Safe from any thread (the PacketEvents send path ends in a Netty
 * `writeAndFlush`). Astera's scoreboard mutations run on the main server
 * thread, so callers do not need to marshal.
 */
public interface ITeamPacketSender {

    /**
     * Override the display info of the team named [teamName] for
     * [player] only. [text] becomes the team's prefix (the visible
     * sidebar row text) and display name; suffix is cleared.
     */
    public fun sendTeamUpdate(player: Player, teamName: String, text: Component)
}
