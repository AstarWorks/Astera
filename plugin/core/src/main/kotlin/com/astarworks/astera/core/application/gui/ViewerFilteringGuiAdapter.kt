package com.astarworks.astera.core.application.gui

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.GuiVisibility
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Answers `visible_if: permission:<node>` conditions (ADR-0240) for one viewer: the viewer holds
 * the node through the permission service, or is a server operator (same rule as
 * `AdminPanelGuard.canUse`, so "can open the admin panel" and "sees the admin tile" never drift).
 */
public class ViewerVisibility(
    private val permissions: IPermissionService,
    private val isOperator: (PlayerId) -> Boolean,
) {
    public fun hasPermission(viewer: PlayerId, node: String): Boolean =
        isOperator(viewer) || permissions.check(viewer, Permission(node))

    /** [spec] reduced to what [viewer] may see. */
    public fun filter(viewer: PlayerId, spec: GuiSpec): GuiSpec =
        GuiVisibility.filter(spec) { node -> hasPermission(viewer, node) }
}

/**
 * [IGuiAdapter] decorator that strips slots a viewer is not allowed to see before the real
 * adapter renders them, so the delegate's current spec (used to resolve clicks) matches exactly
 * what is on screen. A missing [visibility] fails closed: every conditional slot is hidden.
 */
public class ViewerFilteringGuiAdapter(
    private val delegate: IGuiAdapter,
    private val visibility: () -> ViewerVisibility?,
) : IGuiAdapter {

    override fun open(player: IMcPlayer, spec: GuiSpec): Boolean = delegate.open(player, visible(player, spec))

    override fun update(player: IMcPlayer, next: GuiSpec) = delegate.update(player, visible(player, next))

    override fun close(player: IMcPlayer) = delegate.close(player)

    override fun currentSpec(player: IMcPlayer): GuiSpec? = delegate.currentSpec(player)

    private fun visible(player: IMcPlayer, spec: GuiSpec): GuiSpec {
        val v = visibility()
        return if (v != null) v.filter(player.id, spec) else GuiVisibility.filter(spec) { false }
    }
}
