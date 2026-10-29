package com.astarworks.astera.core.domain.presentation

import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.ProxyError

public sealed interface AnnounceTarget {
    public data object All : AnnounceTarget
    public data class Server(public val name: ServerName) : AnnounceTarget
}

public sealed interface AnnounceError {
    public data class UnknownMoment(public val moment: String) : AnnounceError
    /** Local delivery has already happened when the local server is targeted. */
    public data class Transport(public val cause: ProxyError) : AnnounceError
    public data object Closed : AnnounceError
}
