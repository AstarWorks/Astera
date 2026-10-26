package com.astarworks.astera.core.domain.visibility

/**
 * Outcome failure surface for [com.astarworks.astera.core.application.port.outbound.visibility.IPlayerVisibilityGateway]
 * (Wave 5y, ADR-0095).
 *
 * Per-viewer entity / player hide / show is a packet-level operation; failure
 * paths fall into:
 *
 * - viewer not online / not tracked ([UnknownViewer])
 * - target entity unknown to the gateway's index ([UnknownTarget])
 * - viewer == target self-hide attempted ([SameSelfHide]) — vanishing yourself
 *   from your own client desyncs Paper's `PlayerJoinEvent` plumbing, so the
 *   gateway rejects rather than silently no-op
 * - the underlying packet backend (PacketEvents) failed ([BackendError])
 *
 * Sealed so callers can `when`-exhaust + branch on recovery. Wave 6b's Paper
 * `IPlayerVisibilityGateway` impl adds no new variants — packet adapters that
 * need more granular failure must extend [PacketBackendError] inside
 * `BackendError`.
 */
public sealed interface VisibilityError {

    public data object UnknownViewer : VisibilityError

    public data object UnknownTarget : VisibilityError

    public data object SameSelfHide : VisibilityError

    public data class BackendError(val cause: PacketBackendError) : VisibilityError
}
