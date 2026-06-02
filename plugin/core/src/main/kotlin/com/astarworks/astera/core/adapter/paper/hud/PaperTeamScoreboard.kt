package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.ITeamScoreboard
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Team
import org.bukkit.scoreboard.Scoreboard as BukkitScoreboard
import java.util.UUID

/**
 * Wave 6b Paper [ITeamScoreboard]. Sibling to [PaperScoreboard] but uses
 * the team-vs-team Minecraft scoreboard shape: each Astera "team" is a
 * Bukkit `Team` registered on the underlying vanilla scoreboard, and
 * scores are recorded against a synthetic entry name keyed off the
 * team id so a sidebar `Objective` can render them.
 *
 * Phase 1 PoC limits:
 *  - Bukkit `Team` color / friendlyFire / display name are set when
 *    [addTeam] is called; subsequent re-registration updates the display
 *    name only (matching `InMemoryTeamScoreboard` semantics). Color and
 *    friendly-fire defaults follow Bukkit defaults (white, allow).
 *  - Same first-viewer locale caveat as `PaperScoreboard` — proper
 *    per-viewer team-name rendering needs PacketEvents (Wave 6b-stub).
 *  - Bukkit `Team#prefix` / `suffix` are not currently used. The
 *    sidebar shows the rendered display name in the entry slot
 *    directly. Wave 7+ will route i18n through the `prefix(Component)`
 *    Adventure API once we wire `IMessageRenderer` per-viewer.
 *
 * Threading: same as `PaperScoreboard` — mutating methods run on the
 * main server thread.
 */
public class PaperTeamScoreboard(
    override val id: ScoreboardId,
    initialTitle: MessageKey,
    private val bukkitScoreboard: BukkitScoreboard,
    private val server: Server,
    private val messageRenderer: IMessageRenderer? = null,
) : ITeamScoreboard {

    private var titleBacking: MessageKey = initialTitle
    private val displayNames: MutableMap<String, MessageKey> = linkedMapOf()
    private val viewers: MutableSet<UUID> = linkedSetOf()

    private val objective: Objective = bukkitScoreboard.getObjective(OBJECTIVE_NAME)
        ?: bukkitScoreboard.registerNewObjective(
            OBJECTIVE_NAME,
            org.bukkit.scoreboard.Criteria.DUMMY,
            net.kyori.adventure.text.Component.text(renderTitle(initialTitle)),
        ).also { it.displaySlot = DisplaySlot.SIDEBAR }

    override fun setTitle(title: MessageKey) {
        titleBacking = title
        objective.displayName(net.kyori.adventure.text.Component.text(renderTitle(title)))
    }

    override fun addTeam(teamId: String, displayName: MessageKey) {
        displayNames[teamId] = displayName
        val bukkitTeam = bukkitScoreboard.getTeam(teamId)
            ?: bukkitScoreboard.registerNewTeam(teamId)
        bukkitTeam.displayName(net.kyori.adventure.text.Component.text(renderEntry(displayName)))
        // Preserve any existing score: only seed the entry on first add.
        val entry = entryNameFor(teamId)
        if (!bukkitTeam.hasEntry(entry)) {
            bukkitTeam.addEntry(entry)
            // Initialize score at 0 so the entry shows up immediately.
            objective.getScore(entry).score = 0
        }
    }

    override fun removeTeam(teamId: String) {
        displayNames.remove(teamId) ?: return
        val entry = entryNameFor(teamId)
        bukkitScoreboard.resetScores(entry)
        bukkitScoreboard.getTeam(teamId)?.unregister()
    }

    override fun setScore(teamId: String, score: Int) {
        check(teamId in displayNames) {
            "Team '$teamId' is not registered on scoreboard '$id'. Call addTeam(...) first."
        }
        objective.getScore(entryNameFor(teamId)).score = score
    }

    override fun currentScore(teamId: String): Int? {
        if (teamId !in displayNames) return null
        val entry = entryNameFor(teamId)
        val raw = objective.getScore(entry)
        return if (raw.isScoreSet) raw.score else 0
    }

    override fun teams(): Map<String, Int> =
        displayNames.keys.associateWith { currentScore(it) ?: 0 }

    /**
     * Attach [playerId] to this board. Same semantics as
     * `PaperScoreboard.addViewer`.
     */
    public fun addViewer(playerId: PlayerId) {
        viewers.add(playerId.uuid)
        server.getPlayer(playerId.uuid)?.scoreboard = bukkitScoreboard
    }

    /**
     * Detach [playerId], restoring the server main scoreboard.
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

    private fun renderTitle(key: MessageKey): String =
        truncate(messageRenderer?.render(null, key, emptyMap()) ?: key.value, TITLE_LIMIT)

    private fun renderEntry(key: MessageKey): String {
        val rendered = messageRenderer?.render(viewerForRendering(), key, emptyMap()) ?: key.value
        return truncate(rendered, ENTRY_LIMIT)
    }

    private fun viewerForRendering(): PlayerId? =
        viewers.firstOrNull()?.let(::PlayerId)

    private fun entryNameFor(teamId: String): String {
        // Stable mapping team-id → entry so [setScore] can locate the
        // existing row even if the display name was changed since add.
        val prefix = "§r"
        return truncate(prefix + teamId, ENTRY_LIMIT)
    }

    private fun truncate(s: String, limit: Int): String =
        if (s.length <= limit) s else s.substring(0, limit)

    private companion object {
        private const val OBJECTIVE_NAME = "astera-team"
        private const val TITLE_LIMIT = 32
        private const val ENTRY_LIMIT = 40
    }
}
