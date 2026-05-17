package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.domain.gui.GuiSpec

/**
 * Outbound port: render a declarative [GuiSpec] to whatever the host platform
 * uses for "a window with item-slots in it" (Bukkit `Inventory`, Phase 4 web
 * admin DOM, headless test harness …).
 *
 * Per ADR-0021, the adapter owns the **last spec shown** for every player it
 * currently has a GUI open with. Callers therefore only ever push the *next*
 * spec; the adapter runs `GuiDiffEngine.diff(prev, next)` internally and
 * applies the minimum patches. This keeps callers (use cases, intent
 * handlers, future `GuiStore` collectors) free of rendering state.
 *
 * ### Threading
 *
 * All four methods MUST be called on the server's main thread. The Paper impl
 * mutates `org.bukkit.inventory.Inventory` instances which is main-thread-only
 * per Paper API contract. Callers driven by coroutines must funnel through
 * `IScheduler.mainDispatcher`.
 *
 * ### Cross-plugin classloader rule
 *
 * Lives in `plugin/core` (not in any feature plugin) per the ADR-0020
 * "Cross-plugin KClass identity" rule. Feature plugins resolve via
 * `services.getOrThrow<IGuiAdapter>()` and never instantiate the impl.
 */
public interface IGuiAdapter {

    /**
     * Open [spec] for [player]. If [player] already has a GUI open through
     * this adapter, that one is closed first.
     *
     * @return `true` if the open succeeded (player online, spec valid). `false`
     *   on missed open (player offline, invalid item references etc.) —
     *   diagnostic is logged by the impl.
     */
    public fun open(player: IMcPlayer, spec: GuiSpec): Boolean

    /**
     * Diff [next] against the spec [player] last saw and apply the minimum
     * patches. If [player] has no GUI open through this adapter, the call is
     * a no-op (the impl logs a WARN — intent handlers firing post-close is a
     * known race that should not crash).
     *
     * Title changes force a close-then-reopen on Paper 1.21 since Bukkit
     * does not allow live title mutation; the adapter handles this
     * transparently and replaces its internal session entry.
     */
    public fun update(player: IMcPlayer, next: GuiSpec)

    /**
     * Close whatever GUI [player] currently has open through this adapter.
     * Idempotent — safe to call from intent handlers, including the built-in
     * `"gui.close"` handler.
     */
    public fun close(player: IMcPlayer)

    /**
     * The spec currently rendered for [player], or `null` if this adapter
     * has no open session for them. Used by the inbound listener to resolve
     * which `GuiSlot` corresponds to a clicked inventory index.
     */
    public fun currentSpec(player: IMcPlayer): GuiSpec?
}
