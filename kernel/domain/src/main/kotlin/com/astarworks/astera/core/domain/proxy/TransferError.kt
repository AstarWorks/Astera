package com.astarworks.astera.core.domain.proxy

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Sealed error hierarchy for
 * [com.astarworks.astera.core.application.port.outbound.IServerTransferGateway].
 *
 * Mirrors [ProxyError] but carries transfer-flow-specific failure modes the
 * messenger doesn't know about (permission denial, queue saturation, target
 * server reported as offline by the proxy).
 *
 * Every variant carries a [messageKey] so the calling use case can localize
 * without leaking driver-specific exception text.
 */
public sealed class TransferError {

    public abstract val messageKey: MessageKey

    /** The underlying [IProxyMessenger] could not reach the proxy. */
    public data class ProxyUnavailable(val cause: ProxyError) : TransferError() {
        override val messageKey: MessageKey = cause.messageKey
    }

    /** The role check denied the transfer (Wave 5t `IPermissionService`). */
    public data class PermissionDenied(val permissionNode: String) : TransferError() {
        override val messageKey: MessageKey = MessageKey("astera.transfer.permission_denied")
    }

    /** The proxy knows the server but reports it as currently unreachable. */
    public data class TargetServerOffline(val server: ServerName) : TransferError() {
        override val messageKey: MessageKey = MessageKey("astera.transfer.target_offline")
    }

    /** The deferred queue is at capacity (in-memory cap; Velocity has its own). */
    public data object QueueFull : TransferError() {
        override val messageKey: MessageKey = MessageKey("astera.transfer.queue_full")
    }
}
