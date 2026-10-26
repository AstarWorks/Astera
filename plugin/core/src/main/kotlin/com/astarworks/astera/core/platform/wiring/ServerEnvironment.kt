package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.ServerRole
import com.astarworks.astera.core.domain.player.PlayerSettings
import java.util.logging.Logger

/**
 * Which backend this JVM is behind the Velocity proxy (ADR-0179).
 *
 * Read once at boot from `ASTERA_SERVER_ROLE` (`lobby` | `game` | `all`,
 * default `all`) and `ASTERA_SERVER_NAME` (the backend's name in the Velocity
 * `[servers]` table; default `astera-local`). Core builds one and every
 * plugin's [WiringContext] carries the same role and name.
 *
 * [defaultLocale] is the server-wide UI language (ADR-0282), read from
 * `ASTERA_DEFAULT_LOCALE` (`en` | `ja`, default `ja`; anything else logs a
 * warning and falls back to `ja`). A player's explicit `/astera lang` choice
 * overrides it; the client's Minecraft language is never consulted.
 */
public data class ServerEnvironment(
    public val role: ServerRole = ServerRole.ALL,
    public val name: ServerName = DEFAULT_NAME,
    public val defaultLocale: LocaleCode = DEFAULT_LOCALE,
) {
    public companion object {
        public val DEFAULT_NAME: ServerName = ServerName("astera-local")
        public val DEFAULT_LOCALE: LocaleCode = LocaleCode.JA

        public fun from(env: Map<String, String?>): ServerEnvironment = ServerEnvironment(
            role = ServerRole.parse(env["ASTERA_SERVER_ROLE"]),
            name = env["ASTERA_SERVER_NAME"]?.takeIf { it.isNotBlank() }?.let(::ServerName) ?: DEFAULT_NAME,
            defaultLocale = parseLocale(env["ASTERA_DEFAULT_LOCALE"]),
        )

        private fun parseLocale(raw: String?): LocaleCode {
            if (raw.isNullOrBlank()) return DEFAULT_LOCALE
            val parsed = LocaleCode.parse(raw)
            if (parsed in PlayerSettings.SUPPORTED_LOCALES) return parsed
            Logger.getLogger(ServerEnvironment::class.java.name)
                .warning("ASTERA_DEFAULT_LOCALE='$raw' is not one of en|ja; using ${DEFAULT_LOCALE.code}.")
            return DEFAULT_LOCALE
        }

        public fun fromSystem(): ServerEnvironment = from(System.getenv())
    }
}
