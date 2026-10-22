package com.astarworks.astera.core.domain.proxy

/**
 * Logical channel name on a cross-server proxy (BungeeCord plugin channel or
 * Velocity plugin message channel). Channel names are dot-separated, lowercase
 * and follow the same kebab/dot convention as [com.astarworks.astera.core.domain.i18n.MessageKey].
 *
 * The convention `astera.<topic>` (e.g. `astera.transfer`, `astera.match.found`)
 * keeps Astera-owned channels distinguishable from any third-party plugin
 * channels sharing the same proxy.
 *
 * Channel names are intentionally `String`-keyed at the wire layer (proxy
 * plugin messaging uses raw byte channels) — this value class only enforces
 * shape at construction so typos surface where they originate.
 */
@JvmInline
public value class ProxyChannel(public val name: String) {

    init {
        require(VALID.matches(name)) {
            "ProxyChannel must be lower kebab/dot-case (a-z, 0-9, -, _, .): '$name'"
        }
    }

    override fun toString(): String = name

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)*$")

        /** Reserved channel for cross-server transfer commands. */
        public val TRANSFER: ProxyChannel = ProxyChannel("astera.transfer")

        /** Reserved channel for heartbeat / topology pings. */
        public val HEARTBEAT: ProxyChannel = ProxyChannel("astera.heartbeat")

        /** Reserved channel for match-found notifications. */
        /** Lobby to game: a formed match to host (match assignment frame, ADR-0179). */
        /** Hand-off token: the source server saved the player's state; the target may restore it now (F3-6). */
        public val HANDOFF: ProxyChannel = ProxyChannel("astera.handoff")

        public val MATCH_FOUND: ProxyChannel = ProxyChannel("astera.match.found")

        /** Game to lobby: accepted / rejected / ended answers to an assignment (ADR-0179). */
        public val MATCH_STATUS: ProxyChannel = ProxyChannel("astera.match.status")
    }
}
