package com.astarworks.astera.core.domain.proxy

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Sealed error hierarchy for [com.astarworks.astera.core.application.port.outbound.IProxyMessenger].
 *
 * Each variant carries a [messageKey] so adapters can surface a localized
 * message without leaking driver-specific exception text into the application
 * layer (Konsist rule: application MUST NOT import raw JVM throwables).
 */
public sealed class ProxyError {

    public abstract val messageKey: MessageKey

    /**
     * No proxy is currently bridging this server. Either Astera is running on
     * a vanilla Paper server (no BungeeCord / Velocity in front), or the
     * proxy adapter has not finished registering channels.
     */
    public data object NoProxyConnection : ProxyError() {
        override val messageKey: MessageKey = MessageKey("astera.proxy.no_connection")
    }

    /** The named backend is unknown to the proxy. */
    public data class UnknownTargetServer(val server: ServerName) : ProxyError() {
        override val messageKey: MessageKey = MessageKey("astera.proxy.unknown_server")
    }

    /** Encoding the payload failed (e.g. too large for the proxy framing). */
    public data class SerializationFailure(val reason: String) : ProxyError() {
        override val messageKey: MessageKey = MessageKey("astera.proxy.serialization_failure")
    }
}
