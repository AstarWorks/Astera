package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.PlotId

/**
 * Expected failure modes of [AssignIslandPlotUseCase] (Wave 5pve-survival /
 * ADR-0107).
 */
public sealed class PlotError {
    public abstract val messageKey: MessageKey

    /** No island with [islandId] in the repository. */
    public data class IslandNotFound(val islandId: IslandId) : PlotError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.plot.error.island-not-found")
    }

    /** Player is not a member of the island's party — cannot claim a plot. */
    public data class NotAMember(val islandId: IslandId, val playerId: PlayerId) : PlotError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.plot.error.not-a-member")
    }

    /** Player already owns a plot on this island (one plot per member in foundation). */
    public data class AlreadyOwnsPlot(val islandId: IslandId, val playerId: PlayerId, val existing: PlotId) : PlotError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.plot.error.already-owns-plot")
    }

    /** The plot rectangle does not fit inside the island's free space. */
    public data class NoFreeRegion(val islandId: IslandId) : PlotError() {
        override val messageKey: MessageKey =
            MessageKey("astera.social.island.plot.error.no-free-region")
    }
}
