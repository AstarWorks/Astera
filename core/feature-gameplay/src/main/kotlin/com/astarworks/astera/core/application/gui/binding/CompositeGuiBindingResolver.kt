package com.astarworks.astera.core.application.gui.binding

import com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver
import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.ResolvedReactiveSlot
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave M 5polish-quickwin (ADR-0130 §A) — first-non-null delegate
 * [IGuiBindingResolver] composite.
 *
 * # Why a composite
 *
 * The renderer ([com.astarworks.astera.core.adapter.paper.gui.PaperItemTemplateRenderer])
 * holds **one** [IGuiBindingResolver] reference. Adding a new binding
 * namespace (`player.friends.list.*`) without rewriting
 * [com.astarworks.astera.core.application.service.DefaultGuiBindingResolver]
 * means stacking a fresh resolver in front of it via this composite. Each
 * delegate handles its own prefix; the first to return a non-null
 * [ResolvedReactiveSlot] wins.
 *
 * # Ordering
 *
 * Iteration follows the constructor-time order. Resolvers should be ordered
 * narrowest-prefix first (e.g. friends list before the catch-all default),
 * though in practice each resolver short-circuits via its own prefix check
 * so the order is rarely load-bearing.
 *
 * Vendor-neutral; no Bukkit / persistence imports — pure delegation.
 */
public class CompositeGuiBindingResolver(
    initialDelegates: List<IGuiBindingResolver>,
) : IGuiBindingResolver {

    public constructor(vararg delegates: IGuiBindingResolver) : this(delegates.toList())

    private val delegates: java.util.concurrent.CopyOnWriteArrayList<IGuiBindingResolver> =
        java.util.concurrent.CopyOnWriteArrayList(initialDelegates)

    /**
     * Append a new [delegate] to the chain. Feature plugins call this from
     * their `onEnable` (after Core has registered the composite under
     * [com.astarworks.astera.core.application.port.outbound.IGuiBindingResolver])
     * to plug in their own binding namespace without rewriting the renderer.
     *
     * Idempotent if the same delegate instance is added twice — duplicates
     * are filtered out so a Stats double-enable does not double-route.
     */
    public fun addDelegate(delegate: IGuiBindingResolver) {
        if (delegates.none { it === delegate }) {
            delegates += delegate
        }
    }

    /**
     * Remove a previously-added [delegate] (Phase 1 PoC: only the feature
     * plugin's `onDisable` should call this — Core's permanent delegates
     * stay for the server's lifetime).
     */
    public fun removeDelegate(delegate: IGuiBindingResolver) {
        delegates.removeIf { it === delegate }
    }

    /** Snapshot of the current chain — diagnostic / admin only. */
    public fun delegateSnapshot(): List<IGuiBindingResolver> = delegates.toList()

    override suspend fun resolve(binding: BindingKey, viewer: PlayerId?): ResolvedReactiveSlot? {
        for (delegate in delegates) {
            val resolved = delegate.resolve(binding, viewer)
            if (resolved != null) return resolved
        }
        return null
    }
}
