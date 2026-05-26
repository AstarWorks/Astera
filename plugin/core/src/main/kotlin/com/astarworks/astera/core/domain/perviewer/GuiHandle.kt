package com.astarworks.astera.core.domain.perviewer

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Stable handle to an opened-by-a-specific-viewer GUI instance (Wave 5y-ext,
 * ADR-0112).
 *
 * Distinct from [GuiId] (which identifies a *template*) and from
 * `IGuiAdapter.OpenedGui` (which is the platform-side container reference).
 * `GuiHandle` is the **domain-pure** addressing token used by
 * [com.astarworks.astera.core.application.port.outbound.perviewer.gui.IPerViewerGuiStateGateway]
 * so override calls can target one opened instance without dragging Bukkit
 * `Inventory` references into application code.
 *
 * The Paper-side `IGuiAdapter` impl is responsible for minting one handle per
 * `openFor(player, GuiSpec)` and storing the GuiId + PlayerId pair so it can
 * later resolve back to the live `Inventory` when an override is applied.
 *
 * Equality is by [guiId] + [viewer]; the same player re-opening the same GUI
 * yields a logically equivalent handle (the Paper adapter may treat the
 * Inventory reference as transient and re-resolve on render).
 */
public data class GuiHandle(
    public val guiId: GuiId,
    public val viewer: PlayerId,
)
