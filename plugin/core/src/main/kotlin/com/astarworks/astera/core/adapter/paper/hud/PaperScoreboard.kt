package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.IScoreboard
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.adapter.api.hud.ScoreboardLine
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard as BukkitScoreboard
import java.util.UUID

/**
 * Wave 6b Paper [IScoreboard] backing one vanilla `Scoreboard`.
 *
 * Wraps a single `org.bukkit.scoreboard.Scoreboard` with a sidebar
 * `Objective` named after the Astera [ScoreboardId]. Each [addLine]
 * call upserts a per-line virtual entry (the entry name encodes the
 * sidebar slot so vanilla's score-driven sort produces the requested
 * vertical order — slot 0 at the bottom, 14 at the top).
 *
 * Phase 1 PoC limits:
 *  - Vanilla legacy `Objective.getScore(String).score` is used because
 *    the Adventure-style `displayName(Component)` path on `Objective`
 *    only updates the title, not the per-row text. The per-row text is
 *    therefore truncated to the legacy 40-char entry-name limit (vanilla
 *    actually allows 32768 in 1.21 but cosmetic legibility caps near 40).
 *  - Per-viewer locale rendering happens via [IMessageRenderer] when one
 *    is supplied; if not, the raw [MessageKey] value is used and
 *    placeholders are substituted in-process so dev-time use without
 *    locale infra still produces readable text.
 *  - When multiple viewers share this scoreboard, the rendered string
 *    comes from the first viewer's locale (vanilla Scoreboard packets
 *    are server-wide per scoreboard — true per-viewer rendering needs
 *    PacketEvents and is deferred to Wave 6b-stub / Wave 7).
 *
 * Threading: mutating methods (setTitle / addLine / removeLine /
 * addViewer / removeViewer) must run on the main server thread. Callers
 * dispatch via `IScheduler` when invoked from async contexts. The
 * underlying maps are not synchronized — this class is not thread-safe.
 */
public class PaperScoreboard(
    override val id: ScoreboardId,
    initialTitle: MessageKey,
    private val bukkitScoreboard: BukkitScoreboard,
    private val server: Server,
    private val messageRenderer: IMessageRenderer? = null,
) : IScoreboard {

    private var titleBacking: MessageKey = initialTitle
    private val lineMap: MutableMap<Int, RenderedLine> = linkedMapOf()
    private val viewers: MutableSet<UUID> = linkedSetOf()

    private val objective: Objective = bukkitScoreboard.getObjective(OBJECTIVE_NAME)
        ?: bukkitScoreboard.registerNewObjective(
            OBJECTIVE_NAME,
            org.bukkit.scoreboard.Criteria.DUMMY,
            net.kyori.adventure.text.Component.text(renderTitle(initialTitle)),
        ).also { it.displaySlot = DisplaySlot.SIDEBAR }

    override val title: MessageKey get() = titleBacking

    override fun setTitle(title: MessageKey) {
        titleBacking = title
        objective.displayName(net.kyori.adventure.text.Component.text(renderTitle(title)))
    }

    override fun addLine(index: Int, text: MessageKey, placeholders: Map<String, String>) {
        require(index in 0..14) {
            "Scoreboard line index must be in 0..14: $index"
        }
        // Remove previous line at this slot (vanilla entry name changes
        // when text changes; resetting first avoids stale ghost rows).
        lineMap.remove(index)?.let { stale ->
            bukkitScoreboard.resetScores(stale.entryName)
        }
        val rendered = render(text, placeholders)
        val entryName = uniqueEntryName(index, rendered)
        objective.getScore(entryName).score = index
        lineMap[index] = RenderedLine(entryName, rendered)
    }

    override fun removeLine(index: Int) {
        lineMap.remove(index)?.let { bukkitScoreboard.resetScores(it.entryName) }
    }

    override fun lines(): List<ScoreboardLine> =
        lineMap.entries
            .sortedBy { it.key }
            .map { (idx, row) -> ScoreboardLine(idx, row.rendered) }

    /**
     * Attach [playerId] to this board's underlying Bukkit scoreboard.
     * No-op if the player is offline.
     */
    public fun addViewer(playerId: PlayerId) {
        viewers.add(playerId.uuid)
        server.getPlayer(playerId.uuid)?.scoreboard = bukkitScoreboard
    }

    /**
     * Detach [playerId] from this board, restoring the main server
     * scoreboard. No-op if the player is offline.
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

    private fun render(key: MessageKey, placeholders: Map<String, String>): String {
        val rendered = messageRenderer?.render(viewerForRendering(), key, placeholders)
            ?: naiveRender(key, placeholders)
        return truncate(rendered, LINE_LIMIT)
    }

    private fun viewerForRendering(): PlayerId? =
        viewers.firstOrNull()?.let(::PlayerId)

    private fun naiveRender(key: MessageKey, placeholders: Map<String, String>): String {
        if (placeholders.isEmpty()) return key.value
        var out = key.value
        for ((name, value) in placeholders) out = out.replace("{$name}", value)
        return out
    }

    private fun truncate(s: String, limit: Int): String =
        if (s.length <= limit) s else s.substring(0, limit)

    private fun uniqueEntryName(index: Int, rendered: String): String {
        // Vanilla scoreboard entries are unique strings; we prepend the
        // slot index in §-coded form so two slots with identical text
        // do not collide and the user-visible portion remains [rendered].
        val prefix = "§${(index % 16).toString(16)}"
        val combined = prefix + rendered
        return truncate(combined, ENTRY_NAME_LIMIT)
    }

    private data class RenderedLine(val entryName: String, val rendered: String)

    private companion object {
        private const val OBJECTIVE_NAME = "astera"
        private const val TITLE_LIMIT = 32
        private const val LINE_LIMIT = 40
        private const val ENTRY_NAME_LIMIT = 40
    }
}
