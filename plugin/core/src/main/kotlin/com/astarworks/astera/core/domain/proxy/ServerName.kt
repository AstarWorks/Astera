package com.astarworks.astera.core.domain.proxy

/**
 * Server name as registered on the cross-server proxy (BungeeCord
 * `servers:` config or Velocity `[servers]` config).
 *
 * Server names are operator-controlled identifiers — Astera does not enforce
 * a particular shape. We only require non-blank to surface obvious
 * misconfiguration (`""` cannot be a valid backend).
 */
@JvmInline
public value class ServerName(public val value: String) {

    init {
        require(value.isNotBlank()) { "ServerName must not be blank" }
    }

    override fun toString(): String = value
}
