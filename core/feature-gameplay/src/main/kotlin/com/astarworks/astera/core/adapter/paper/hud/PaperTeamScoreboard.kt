package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.ITeamScoreboard
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.adapter.paper.packetevents.ITeamPacketSender
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard as BukkitScoreboard
import java.util.UUID

/**
 * Paper [ITeamScoreboard] with per-viewer locale rendering via
 * PacketEvents (ADR-0171 Wave 6c-stub-packetevents-B; supersedes the
 * Wave 6b first-viewer-locale variant).
 *
 * Sidebar model — "opaque entry + team-prefix":
 *  - Each Astera team gets an **opaque entry token** (two §-color codes +
 *    `§r`, invisible when rendered) assigned from a monotonically
 *    increasing ordinal at first [addTeam]. The token is the vanilla
 *    score-holder; ordinals are never reused after [removeTeam], giving
 *    256 unique tokens per board lifetime.
 *  - The **visible row text lives in the Bukkit `Team` prefix** (Adventure
 *    Component), not in the entry. The Bukkit team is registered under a
 *    derived ≤16-char name from [ScoreboardTeamNames] — never the raw
 *    caller-supplied team id, which CraftBukkit's `registerNewTeam`
 *    rejects past 16 chars.
 *
 * i18n:
 *  - The shared Bukkit scoreboard carries the **default-locale baseline**
 *    (`render(null, …)`) in each team's prefix — every viewer sees this
 *    when PacketEvents is absent.
 *  - When [teamPacketSender] is wired (PacketEvents installed), each
 *    [addTeam] fans a per-viewer team UPDATE packet out to every current
 *    viewer, and each [addViewer] fans every registered team out to the
 *    new viewer — overriding the prefix with that viewer's locale. The
 *    update must target the derived team name byte-identically or the
 *    client silently drops it (see [ScoreboardTeamNames]).
 *  - A viewer's locale change applies on relog (re-`show` → [addViewer]
 *    re-fans); no live locale-change listener in Phase 1 PoC.
 *
 * No fan-out happens on [setScore] (objective packet, not team),
 * [removeTeam] (Bukkit `unregister()` already broadcasts REMOVE),
 * [setTitle] (objective display name — different packet, stays shared
 * per ADR-0171 scope), or [removeViewer] (reassignment to the main
 * scoreboard wipes this board's teams client-side).
 *
 * Phase 1 PoC limits:
 *  - Bukkit `Team` color / friendlyFire follow Bukkit defaults (white,
 *    allow); only prefix + display name are managed here.
 *  - Title rendering stays default-locale + shared (TITLE_LIMIT applies
 *    to the objective display name).
 *
 * Threading: same as `PaperScoreboard` — mutating methods run on the
 * main server thread. [ITeamPacketSender.sendTeamUpdate] is safe from
 * any thread, so the fan-out needs no marshalling.
 */
public class PaperTeamScoreboard(
    override val id: ScoreboardId,
    initialTitle: MessageKey,
    private val bukkitScoreboard: BukkitScoreboard,
    private val server: Server,
    private val messageRenderer: IMessageRenderer? = null,
    private val teamPacketSender: ITeamPacketSender? = null,
) : ITeamScoreboard {

    private var titleBacking: MessageKey = initialTitle
    private val displayNames: MutableMap<String, MessageKey> = linkedMapOf()

    /** teamId → derived ≤16-char Bukkit team name (stored so [removeTeam]
     *  and the fan-out reuse the registration name byte-identically). */
    private val teamNames: MutableMap<String, String> = linkedMapOf()

    /** teamId → opaque entry token holding the team's sidebar score. */
    private val entryTokens: MutableMap<String, String> = linkedMapOf()

    /** Next opaque-entry ordinal. Monotonic — never decremented, never
     *  reused after removal, so stale client-side rows can't collide. */
    private var nextOrdinal: Int = 0

    private val viewers: MutableSet<UUID> = linkedSetOf()

    private val objective: Objective = bukkitScoreboard.getObjective(OBJECTIVE_NAME)
        ?: bukkitScoreboard.registerNewObjective(
            OBJECTIVE_NAME,
            org.bukkit.scoreboard.Criteria.DUMMY,
            Component.text(renderTitle(initialTitle)),
        ).also { it.displaySlot = DisplaySlot.SIDEBAR }

    override fun setTitle(title: MessageKey) {
        titleBacking = title
        objective.displayName(Component.text(renderTitle(title)))
    }

    override fun addTeam(teamId: String, displayName: MessageKey) {
        displayNames[teamId] = displayName

        val teamName = teamNames.getOrPut(teamId) { ScoreboardTeamNames.teamName(id, teamId) }
        val bukkitTeam = bukkitScoreboard.getTeam(teamName)
            ?: bukkitScoreboard.registerNewTeam(teamName)

        // Default-locale baseline — what every viewer sees until (unless)
        // the per-viewer fan-out overrides their copy.
        val baseline = Component.text(messageRenderer?.render(null, displayName, emptyMap()) ?: displayName.value)
        bukkitTeam.prefix(baseline)
        bukkitTeam.displayName(baseline)

        // Preserve any existing score: only assign the opaque entry and
        // seed the score on first add for this teamId.
        if (teamId !in entryTokens) {
            val entry = nextEntryToken()
            entryTokens[teamId] = entry
            bukkitTeam.addEntry(entry)
            // Initialize score at 0 so the row shows up immediately.
            objective.getScore(entry).score = 0
        }

        fanOutTeam(teamId)
    }

    override fun removeTeam(teamId: String) {
        displayNames.remove(teamId) ?: return
        entryTokens.remove(teamId)?.let { bukkitScoreboard.resetScores(it) }
        teamNames.remove(teamId)?.let { bukkitScoreboard.getTeam(it)?.unregister() }
    }

    override fun setScore(teamId: String, score: Int) {
        check(teamId in displayNames) {
            "Team '$teamId' is not registered on scoreboard '$id'. Call addTeam(...) first."
        }
        objective.getScore(entryTokens.getValue(teamId)).score = score
    }

    override fun currentScore(teamId: String): Int? {
        val entry = entryTokens[teamId] ?: return null
        val raw = objective.getScore(entry)
        return if (raw.isScoreSet) raw.score else 0
    }

    override fun teams(): Map<String, Int> =
        displayNames.keys.associateWith { currentScore(it) ?: 0 }

    /**
     * Attach [playerId] to this board, then fan every registered team's
     * locale-rendered prefix out to the new viewer. Same base semantics
     * as `PaperScoreboard.addViewer`; no-op if the player is offline.
     */
    public fun addViewer(playerId: PlayerId) {
        viewers.add(playerId.uuid)
        server.getPlayer(playerId.uuid)?.scoreboard = bukkitScoreboard
        fanOutAllTeamsTo(playerId.uuid)
    }

    /**
     * Detach [playerId], restoring the server main scoreboard. No
     * fan-out needed: the reassignment wipes this board's teams
     * client-side.
     */
    public fun removeViewer(playerId: PlayerId) {
        viewers.remove(playerId.uuid)
        val mainScoreboard = server.scoreboardManager?.mainScoreboard
        server.getPlayer(playerId.uuid)?.let { p ->
            if (mainScoreboard != null) p.scoreboard = mainScoreboard
        }
    }

    /** Snapshot of the UUIDs currently attached to this scoreboard. */
    public fun viewers(): Set<UUID> = viewers.toSet()

    /**
     * Per-viewer override of [teamId]'s prefix for ALL current viewers.
     * Skipped entirely when either collaborator is absent: without a
     * renderer every viewer's text equals the baseline, and without a
     * sender (PacketEvents not installed) there is no override channel.
     */
    private fun fanOutTeam(teamId: String) {
        val sender = teamPacketSender ?: return
        val renderer = messageRenderer ?: return
        val teamName = teamNames[teamId] ?: return
        val key = displayNames[teamId] ?: return
        for (uuid in viewers) {
            val player = server.getPlayer(uuid) ?: continue
            val rendered = renderer.render(PlayerId(uuid), key, emptyMap())
            sender.sendTeamUpdate(player, teamName, Component.text(rendered))
        }
    }

    /** Per-viewer override of EVERY registered team for [viewerId] only
     *  (new-viewer attach path). Same skip guard as [fanOutTeam]. */
    private fun fanOutAllTeamsTo(viewerId: UUID) {
        val sender = teamPacketSender ?: return
        val renderer = messageRenderer ?: return
        val player = server.getPlayer(viewerId) ?: return
        for ((teamId, key) in displayNames) {
            val teamName = teamNames[teamId] ?: continue
            val rendered = renderer.render(PlayerId(viewerId), key, emptyMap())
            sender.sendTeamUpdate(player, teamName, Component.text(rendered))
        }
    }

    private fun nextEntryToken(): String {
        val ordinal = nextOrdinal++
        check(ordinal < CODES.length * CODES.length) {
            "Scoreboard '$id' exhausted its ${CODES.length * CODES.length} opaque entry tokens " +
                "(ordinals are never reused after removeTeam)."
        }
        // Two color codes + reset: invisible when the client renders the
        // entry, unique per ordinal, and never collides with player names.
        return "§${CODES[ordinal / CODES.length]}§${CODES[ordinal % CODES.length]}§r"
    }

    private fun renderTitle(key: MessageKey): String =
        truncate(messageRenderer?.render(null, key, emptyMap()) ?: key.value, TITLE_LIMIT)

    private fun truncate(s: String, limit: Int): String =
        if (s.length <= limit) s else s.substring(0, limit)

    private companion object {
        private const val OBJECTIVE_NAME = "astera-team"
        private const val TITLE_LIMIT = 32
        private const val CODES = "0123456789abcdef"
    }
}
