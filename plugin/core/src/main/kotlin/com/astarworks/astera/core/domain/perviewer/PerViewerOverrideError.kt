package com.astarworks.astera.core.domain.perviewer

/**
 * Outcome failure surface for the three per-viewer state gateways introduced
 * in Wave 5y-ext (ADR-0112):
 *
 *  - [com.astarworks.astera.core.application.port.outbound.perviewer.npc.IPerViewerNpcStateGateway]
 *  - [com.astarworks.astera.core.application.port.outbound.perviewer.block.IPerViewerBlockStateGateway]
 *  - [com.astarworks.astera.core.application.port.outbound.perviewer.gui.IPerViewerGuiStateGateway]
 *
 * Same shape as [com.astarworks.astera.core.domain.visibility.VisibilityError]
 * but distinct sealed family on purpose — per-viewer **state override** has
 * different failure cells than per-viewer **visibility toggle**:
 *
 *  - [UnknownViewer] — viewer not tracked (typically: not online).
 *  - [UnknownTarget] — NPC handle / block position / GUI handle not known.
 *  - [InvalidOverride] — payload-shape violation discovered at the gateway
 *    (e.g. equipment override slot out of range; gui slot index past the
 *    container's size). Distinct from a precondition `require` because the
 *    failing input may come from runtime YAML and shouldn't crash the call
 *    site.
 *  - [BackendError] — packet adapter / paper backend rejected the staged
 *    override at egress time. Wrapped reason is opaque (string) since the
 *    Phase 1 in-memory impls never fail this way; Wave 6 packet adapter
 *    will narrow this if needed.
 *
 * Sealed so callers can exhaust + branch on recovery.
 */
public sealed interface PerViewerOverrideError {

    public data object UnknownViewer : PerViewerOverrideError

    public data object UnknownTarget : PerViewerOverrideError

    public data class InvalidOverride(val reason: String) : PerViewerOverrideError {
        init {
            require(reason.isNotBlank()) {
                "PerViewerOverrideError.InvalidOverride.reason must not be blank"
            }
        }
    }

    public data class BackendError(val reason: String) : PerViewerOverrideError {
        init {
            require(reason.isNotBlank()) {
                "PerViewerOverrideError.BackendError.reason must not be blank"
            }
        }
    }
}
