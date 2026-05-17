package com.astarworks.astera.core.application.port.inbound

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.domain.gui.GuiIntent

/**
 * Inbound port: route [GuiIntent]s emitted by GUI interactions to the use
 * cases that own them.
 *
 * "Inbound" because the platform's GUI listener calls _into_ the application
 * via [dispatch] whenever a player clicks a slot whose [GuiIntent] is not
 * null. Each plugin subscribes its own intent keys via [register]; ownership
 * is by **longest-prefix match** so plugins can claim a whole namespace
 * (`weapons.loadout.`, `shop.`, `match.vote.`) with a single registration.
 *
 * ### Built-in handlers
 *
 * The core impl ships with a built-in handler for `"gui.close"` that
 * closes the player's currently-open GUI through `IGuiAdapter`. Plugins
 * MUST NOT shadow this prefix.
 *
 * ### Cross-plugin classloader rule (ADR-0020 §"Cross-plugin KClass identity")
 *
 * Handler lambdas are `Function2<GuiIntent, IMcPlayer, Unit>` instances
 * created in feature plugin classloaders. They route through this port
 * (defined in `plugin/core`) without classloader trouble because
 * `kotlin.jvm.functions.FunctionN` lives in `kotlin-stdlib`, shared across
 * all plugins. **However**, plugin disable does not invalidate registered
 * handlers, so callers MUST call `Registration.unregister()` in their
 * `onDisable` or the router will retain a strong reference to the disabled
 * plugin's classloader.
 */
public interface IGuiIntentRouter {

    /**
     * Subscribe [handler] to every intent whose [GuiIntent.key] starts with
     * [prefix]. Resolution is **longest-prefix-wins**; if two handlers share
     * the same prefix the first-registered wins.
     *
     * Returns a [ServiceRegistry.Registration] handle whose
     * [ServiceRegistry.Registration.unregister] removes the subscription.
     * Callers MUST invoke `unregister()` in their plugin's `onDisable` to
     * release classloader retention.
     */
    public fun register(
        prefix: String,
        handler: (GuiIntent, IMcPlayer) -> Unit,
    ): ServiceRegistry.Registration

    /**
     * Dispatch [intent] originating from [player] to its matching handler.
     * Usually invoked by the platform's GUI click listener, not by use cases
     * directly. Intents with no matching prefix are logged at WARN and
     * dropped.
     */
    public fun dispatch(player: IMcPlayer, intent: GuiIntent)
}
