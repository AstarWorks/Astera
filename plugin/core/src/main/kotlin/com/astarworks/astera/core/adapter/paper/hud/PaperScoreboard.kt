package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.IScoreboard
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.adapter.api.hud.ScoreboardLine
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
 * Wave 6b Paper [IScoreboard] backing one vanilla `Scoreboard`, extended
 * with ADR-0171 per-viewer locale rendering via PacketEvents.
 *
 * Wraps a single `org.bukkit.scoreboard.Scoreboard` with a sidebar
 * `Objective` named after the Astera [ScoreboardId]. Each sidebar slot
 * (0..14) follows the modern **opaque entry + per-line team prefix**
 * model: the entry is a stable invisible token (`"§<code>§r"`, one legacy
 * color code per slot), its score is the slot index (vanilla's
 * score-driven sort produces the requested vertical order — slot 0 at
 * the bottom, 14 at the top), and the visible row text lives in the
 * prefix of a per-line Bukkit `Team`. Because the entry never changes
 * when the text does, a text update is a single team-prefix mutation.
 *
 * The shared Bukkit scoreboard carries the **default-locale baseline**
 * (was first-viewer locale before this wave). When PacketEvents is
 * installed, [ITeamPacketSender] overrides each line per viewer: every
 * [addLine] fans the line out to all current viewers, and every
 * [addViewer] fans every present line out to the new viewer. The wire
 * team name is derived via [ScoreboardTeamNames.lineName] on both the
 * register and the UPDATE paths so they stay byte-identical.
 *
 * Phase 1 PoC limits:
 *  - The title stays shared (default locale, [TITLE_LIMIT]-truncated):
 *    the objective display name travels in a different packet and is out
 *    of ADR-0171 scope.
 *  - Locale changes apply on relog: a re-`show` → [addViewer] re-runs
 *    the fan-out; there is no live locale-change listener.
 *  - Without an [IMessageRenderer] the raw [MessageKey] value is used
 *    and placeholders are substituted in-process so dev-time use without
 *    locale infra still produces readable text. Per-viewer fan-out is
 *    skipped in that case (every viewer's text would equal the baseline).
 *
 * Threading: mutating methods (setTitle / addLine / removeLine /
 * addViewer / removeViewer) must run on the main server thread. Callers
 * dispatch via `IScheduler` when invoked from async contexts.
 * [ITeamPacketSender.sendTeamUpdate] is safe from any thread, so the
 * fan-out needs no marshalling. The underlying maps are not
 * synchronized — this class is not thread-safe.
 */
public class PaperScoreboard(
    override val id: ScoreboardId,
    initialTitle: MessageKey,
    private val bukkitScoreboard: BukkitScoreboard,
    private val server: Server,
    private val messageRenderer: IMessageRenderer? = null,
    private val teamPacketSender: ITeamPacketSender? = null,
) : IScoreboard {

    private var titleBacking: MessageKey = initialTitle
    private val lineMap: MutableMap<Int, LineState> = linkedMapOf()
    private val viewers: MutableSet<UUID> = linkedSetOf()

    private val objective: Objective = bukkitScoreboard.getObjective(OBJECTIVE_NAME)
        ?: bukkitScoreboard.registerNewObjective(
            OBJECTIVE_NAME,
            org.bukkit.scoreboard.Criteria.DUMMY,
            Component.text(renderTitle(initialTitle)),
        ).also { it.displaySlot = DisplaySlot.SIDEBAR }

    override val title: MessageKey get() = titleBacking

    override fun setTitle(title: MessageKey) {
        titleBacking = title
        objective.displayName(Component.text(renderTitle(title)))
    }

    override fun addLine(index: Int, text: MessageKey, placeholders: Map<String, String>) {
        require(index in 0..14) {
            "Scoreboard line index must be in 0..14: $index"
        }
        val teamName = ScoreboardTeamNames.lineName(id, index)
        val team = bukkitScoreboard.getTeam(teamName)
            ?: bukkitScoreboard.registerNewTeam(teamName).also {
                // First add at this slot: bind the opaque entry token and
                // seed the score. Neither changes on later text updates —
                // the entry is stable per slot, and the score (= slot
                // index) only drives vanilla's vertical sort.
                val token = entryToken(index)
                it.addEntry(token)
                objective.getScore(token).score = index
            }
        val baseline = renderBaseline(text, placeholders)
        team.prefix(Component.text(baseline))
        lineMap[index] = LineState(text, placeholders, baseline, teamName)
        fanOutLine(index)
    }

    override fun removeLine(index: Int) {
        val line = lineMap.remove(index) ?: return
        bukkitScoreboard.resetScores(entryToken(index))
        bukkitScoreboard.getTeam(line.teamName)?.unregister()
    }

    override fun lines(): List<ScoreboardLine> =
        lineMap.entries
            .sortedBy { it.key }
            .map { (idx, line) -> ScoreboardLine(idx, line.baseline) }

    /**
     * Attach [playerId] to this board's underlying Bukkit scoreboard and
     * fan out every present line in the viewer's locale (when PacketEvents
     * + a renderer are wired). No-op if the player is offline.
     */
    public fun addViewer(playerId: PlayerId) {
        viewers.add(playerId.uuid)
        server.getPlayer(playerId.uuid)?.scoreboard = bukkitScoreboard
        fanOutAllLinesTo(playerId)
    }

    /**
     * Detach [playerId] from this board, restoring the main server
     * scoreboard. No-op if the player is offline. No per-viewer cleanup
     * packet is needed — reassignment to the main scoreboard wipes this
     * board's teams client-side.
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

    /**
     * Default-locale rendering for the shared Bukkit board. The component
     * prefix is unbounded, so unlike the title no truncation applies.
     */
    private fun renderBaseline(key: MessageKey, placeholders: Map<String, String>): String =
        messageRenderer?.render(null, key, placeholders) ?: naiveRender(key, placeholders)

    private fun naiveRender(key: MessageKey, placeholders: Map<String, String>): String {
        if (placeholders.isEmpty()) return key.value
        var out = key.value
        for ((name, value) in placeholders) out = out.replace("{$name}", value)
        return out
    }

    private fun truncate(s: String, limit: Int): String =
        if (s.length <= limit) s else s.substring(0, limit)

    /** Per-viewer override of the line at [index] for all online viewers. */
    private fun fanOutLine(index: Int) {
        val sender = teamPacketSender ?: return
        val renderer = messageRenderer ?: return
        val line = lineMap[index] ?: return
        for (uuid in viewers) {
            val player = server.getPlayer(uuid) ?: continue
            val rendered = renderer.render(PlayerId(uuid), line.key, line.placeholders)
            sender.sendTeamUpdate(player, line.teamName, Component.text(rendered))
        }
    }

    /** Per-viewer override of every present line for [playerId] only. */
    private fun fanOutAllLinesTo(playerId: PlayerId) {
        val sender = teamPacketSender ?: return
        val renderer = messageRenderer ?: return
        val player = server.getPlayer(playerId.uuid) ?: return
        for (line in lineMap.values) {
            val rendered = renderer.render(playerId, line.key, line.placeholders)
            sender.sendTeamUpdate(player, line.teamName, Component.text(rendered))
        }
    }

    private fun entryToken(index: Int): String = "§" + ENTRY_CODES[index] + "§r"

    /**
     * Per-slot line state. [baseline] is the default-locale rendering
     * shown on the shared board; [key] + [placeholders] are kept so the
     * fan-out can re-render per viewer; [teamName] is the
     * [ScoreboardTeamNames.lineName] used at registration — the UPDATE
     * path must reuse it byte-identically.
     */
    private data class LineState(
        val key: MessageKey,
        val placeholders: Map<String, String>,
        val baseline: String,
        val teamName: String,
    )

    private companion object {
        private const val OBJECTIVE_NAME = "astera"
        private const val TITLE_LIMIT = 32

        /**
         * One legacy color code per sidebar slot 0..14. `"§<code>§r"` is
         * a stable, unique, non-empty entry that renders as zero visible
         * characters — the visible row text lives in the team prefix.
         */
        private const val ENTRY_CODES = "0123456789abcde"
    }
}
