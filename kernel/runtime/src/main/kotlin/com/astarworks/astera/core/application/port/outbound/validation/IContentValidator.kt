package com.astarworks.astera.core.application.port.outbound.validation

import com.astarworks.astera.core.domain.validation.ContentValidationError
import com.astarworks.astera.core.domain.validation.ContentValidationReport

/**
 * Outbound port (Wave 5server-harden / ADR-0123): validate cross-content
 * reference integrity at boot time.
 *
 * # Why a port
 *
 * Every plugin owns one or more `Contents` registries (item / skill / shop /
 * stage / mob / block / npc / loot table / encounter zone). Reference graphs
 * cross plugin boundaries (a shop in `:plugin:economy` cites an item id owned
 * by `:plugin:items`). Centralising the check inside one plugin would force
 * an awkward Core import direction; instead each plugin contributes a
 * [ContentReferenceCheck] and Core's [com.astarworks.astera.core.application.service.validation.ContentValidatorService]
 * folds them.
 *
 * Implementations register on the `ServiceRegistry` and are invoked from a
 * [com.astarworks.astera.core.application.port.outbound.lifecycle.IServerLifecycleListener]
 * during `onBoot()` (after every plugin's `onEnable` has registered its
 * registries, before any scheduler starts — Wave 5w / ADR-0089 hook contract).
 *
 * # Idempotence
 *
 * `validateAll()` is read-only and side-effect free — it must be safe to call
 * from `/astera-debug validate-content` at any time.
 */
public interface IContentValidator {

    /**
     * Run every registered [ContentReferenceCheck] against the currently
     * loaded content and return the aggregated [ContentValidationReport].
     */
    public fun validateAll(): ContentValidationReport

    /**
     * Register a [ContentReferenceCheck]. Plugins call this at `onEnable`
     * after their registries are populated. Returns a [Registration] the
     * caller cancels at `onDisable` to keep the validator clean across
     * plugin reloads (Wave 5server-harden listener cleanup contract).
     */
    public fun register(check: ContentReferenceCheck): Registration

    public interface Registration {
        public fun unregister()
    }
}

/**
 * Pluggable contributor to [IContentValidator.validateAll].
 *
 * Each plugin provides one per content category it owns. The check is given
 * no parameters — it captures its own registry references at construction
 * time — and must return the list of errors found (empty on success).
 *
 * Authors should prefer to return [ContentValidationError.MissingReference]
 * for the common case (an id cited by entry A is absent from the resolved
 * target registry); the other variants are escape hatches.
 */
public fun interface ContentReferenceCheck {
    public fun check(): List<ContentValidationError>
}
