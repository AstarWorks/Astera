package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.ServerRole

/**
 * Which backend this JVM is behind the Velocity proxy (ADR-0179).
 *
 * Read once at boot from `ASTERA_SERVER_ROLE` (`lobby` | `game` | `all`,
 * default `all`) and `ASTERA_SERVER_NAME` (the backend's name in the Velocity
 * `[servers]` table; default `astera-local`). Core builds one and every
 * plugin's [WiringContext] carries the same role and name.
 */
public data class ServerEnvironment(
    public val role: ServerRole = ServerRole.ALL,
    public val name: ServerName = DEFAULT_NAME,
) {
    public companion object {
        public val DEFAULT_NAME: ServerName = ServerName("astera-local")

        public fun from(env: Map<String, String?>): ServerEnvironment = ServerEnvironment(
            role = ServerRole.parse(env["ASTERA_SERVER_ROLE"]),
            name = env["ASTERA_SERVER_NAME"]?.takeIf { it.isNotBlank() }?.let(::ServerName) ?: DEFAULT_NAME,
        )

        public fun fromSystem(): ServerEnvironment = from(System.getenv())
    }
}
