package com.astarworks.astera.stats.application.service.leaderboard

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IDynamicMessages
import com.astarworks.astera.core.application.port.outbound.IHologramGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerProfileRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.BillboardMode
import com.astarworks.astera.core.domain.hologram.Hologram
import com.astarworks.astera.core.domain.hologram.HologramHandle
import com.astarworks.astera.core.domain.hologram.HologramId
import com.astarworks.astera.core.domain.hologram.HologramKind
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.stats.application.usecase.leaderboard.QueryLeaderboardUseCase
import org.slf4j.LoggerFactory

/**
 * The season ranking as a floating text board in the lobby (ADR-0210). Every [refreshTicks] it
 * reads the top [rows] entries, resolves display names, publishes the current text of the row keys
 * through [IDynamicMessages] (hologram lines can only carry message keys) and re-renders the board.
 * Plain text with section-sign colours, because a text display takes a legacy string.
 *
 * The board is spawned lazily: while the lobby world is not loaded yet the spawn fails and the next
 * refresh tries again; a board that vanished (despawned chunk) is respawned.
 */
public class LobbyLeaderboardBoard(
    private val query: QueryLeaderboardUseCase,
    private val profiles: () -> IPlayerProfileRepository?,
    private val holograms: IHologramGateway,
    private val dynamic: IDynamicMessages,
    private val scheduler: IScheduler,
    private val world: WorldId,
    private val position: Vec3,
    private val rows: Int = DEFAULT_ROWS,
    refreshTicks: Long = DEFAULT_REFRESH_TICKS,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var handle: HologramHandle? = null

    private val task: IScheduler.TaskHandle =
        scheduler.runRepeatingTicks(ExecutionScope.Io, initialDelayTicks = FIRST_REFRESH_TICKS, periodTicks = refreshTicks) { kotlinx.coroutines.runBlocking { refresh() } }

    public fun stop() {
        task.cancel()
        handle?.let { holograms.despawn(it) }
        handle = null
    }

    /** Rebuild the text from the current ranking and show it. */
    public suspend fun refresh() {
        val keys = publishRows()
        // Re-render even when the keys are unchanged: the text under them is what moved.
        scheduler.run(regionScope(world, position), kotlin.time.Duration.ZERO) {
            handle?.let { current -> holograms.updateTextContent(current, keys).onFailure { handle = null } }
            if (handle == null) spawn(keys)
        }
    }

    private fun spawn(keys: List<MessageKey>) {
        val hologram = Hologram(
            id = HologramId.random(),
            worldId = world,
            position = position,
            kind = HologramKind.Text(lines = keys, lineWidth = 260, defaultBackground = true),
            billboard = BillboardMode.CENTER,
            viewRangeBlocks = 48f,
            ownerKey = "leaderboard:${world.value}:${position.x},${position.y},${position.z}",
        )
        when (val result = holograms.spawn(hologram)) {
            is Result.Success -> handle = result.value
            is Result.Failure -> log.debug("Lobby leaderboard board not spawned yet: {}", result.error)
        }
    }

    /** Publishes row texts and returns the keys to show, title first. */
    private suspend fun publishRows(): List<MessageKey> {
        val entries = (query.execute(rows) as? Result.Success)?.value.orEmpty()
        if (entries.isEmpty()) return listOf(TITLE, EMPTY)
        val names = profiles()
        val keys = ArrayList<MessageKey>(entries.size + 1)
        keys += TITLE
        entries.forEachIndexed { i, entry ->
            val name = names?.find(entry.playerId)?.displayName ?: entry.playerId.uuid.toString().take(8)
            val key = rowKey(i)
            dynamic.set(key, rowText(i + 1, name, entry.rating, entry.wins, entry.losses))
            keys += key
        }
        return keys
    }

    public companion object {
        public const val DEFAULT_ROWS: Int = 10
        public const val DEFAULT_REFRESH_TICKS: Long = 600L
        public const val FIRST_REFRESH_TICKS: Long = 100L
        public val TITLE: MessageKey = MessageKey("astera.board.leaderboard.title")
        public val EMPTY: MessageKey = MessageKey("astera.board.leaderboard.empty")

        public fun rowKey(index: Int): MessageKey = MessageKey("astera.board.leaderboard.row-${index + 1}")

        /** One ranking row as a legacy-coloured string; the first three ranks get gold / silver / bronze. */
        public fun rowText(rank: Int, name: String, rating: Int, wins: Long, losses: Long): String {
            val colour = when (rank) {
                1 -> "§6"
                2 -> "§f"
                3 -> "§c"
                else -> "§7"
            }
            return "$colour$rank. §f$name §e$rating §8(${wins}W ${losses}L)"
        }
    }
}
