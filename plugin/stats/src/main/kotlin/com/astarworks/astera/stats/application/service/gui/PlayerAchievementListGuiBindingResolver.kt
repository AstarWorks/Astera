package com.astarworks.astera.stats.application.service.gui

import com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver
import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.ResolvedReactiveSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.IAchievementRepository
import org.slf4j.LoggerFactory

/**
 * Wave M 5polish-quickwin (ADR-0130 §B) — projects the achievement catalog
 * into `player.achievement.list.<n>` reactive bindings consumed by
 * [com.astarworks.astera.core.application.gui.lobby.AchievementViewerGuiSpec].
 *
 * # Catalog order
 *
 * Iteration follows [IAchievementRepository.list]'s registration order —
 * which is deterministic per `AchievementContents.bootstrap`. The same
 * achievement always lands on the same tile across opens (within a server
 * session), which matters for the GUI animation pipeline (Wave 5gui-depth)
 * that tracks per-tile diffs.
 *
 * # Item id mapping
 *
 * - unlocked → `indicator-achievement-unlocked`
 * - locked   → `indicator-achievement-locked`
 *
 * The `displayOverride` carries an
 * [com.astarworks.astera.core.domain.i18n.MessageKey] resolved at render
 * time so achievement-display localisation stays in `content/languages/`.
 *
 * # Cross-plugin classloader rule
 *
 * Lives in `:plugin:stats` — depends on Core ports + Stats's own
 * [IAchievementRepository]. Combat / Match / Items not touched. Registered
 * with the composite resolver at Phase C wiring from
 * `AsteraStatsPlugin.onEnable`.
 */
public class PlayerAchievementListGuiBindingResolver(
    private val achievementsProvider: () -> IAchievementRepository?,
) : IGuiBindingResolver {

    public constructor(achievements: IAchievementRepository?) : this({ achievements })

    private val log = LoggerFactory.getLogger(javaClass)

    override fun resolve(binding: BindingKey, viewer: PlayerId?): ResolvedReactiveSlot? {
        if (viewer == null) return null
        val key = binding.value
        if (!key.startsWith(PREFIX)) return null
        val indexToken = key.removePrefix(PREFIX)
        val index = indexToken.toIntOrNull()
        if (index == null || index < 0) {
            log.warn(
                "PlayerAchievementListGuiBindingResolver: index out of range '{}'",
                indexToken,
            )
            return null
        }
        val achievements = achievementsProvider()
        if (achievements == null) {
            log.debug("Achievement provider not wired yet; binding '{}' returns null.", key)
            return null
        }
        val catalog = achievements.list()
        val entry = catalog.getOrNull(index) ?: return null
        val unlocked = achievements.isUnlocked(viewer, entry.id)
        val itemId = if (unlocked) TILE_UNLOCKED else TILE_LOCKED
        return ResolvedReactiveSlot(
            itemId = ItemId(itemId),
            displayOverride = entry.nameKey,
        )
    }

    public companion object {
        public const val PREFIX: String = "player.achievement.list."
        public const val TILE_UNLOCKED: String = "indicator-achievement-unlocked"
        public const val TILE_LOCKED: String = "indicator-achievement-locked"
    }
}
