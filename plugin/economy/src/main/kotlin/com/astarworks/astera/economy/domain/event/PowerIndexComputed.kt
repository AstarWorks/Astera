package com.astarworks.astera.economy.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published whenever [com.astarworks.astera.economy.application.usecase.power.RecalculatePowerIndexUseCase]
 * recomputes and persists a snapshot (Wave 5flex / ADR-0102 surface #4).
 *
 * Cross-plugin subscribers:
 *
 *   - **Stats / nameplate**: refresh the player's nameplate badge so
 *     the displayed Power Index reflects the latest snapshot.
 *   - **Web admin / Wave 8a**: dashboard live update.
 *   - **Wave _ops-4 Prometheus**: gauge update for
 *     `astera_flex_power_index_total{player=...}`.
 *
 * The event carries the full breakdown so subscribers do not need to
 * re-query the repository in the hot path.
 */
public data class PowerIndexComputed(
    public val playerId: PlayerId,
    public val total: Long,
    public val equipmentValue: Long,
    public val vaultValue: Long,
    public val walletValue: Long,
    public val marketplaceListingValue: Long,
    override val occurredAt: Instant,
    override val version: Int = 1,
) : DomainEvent
