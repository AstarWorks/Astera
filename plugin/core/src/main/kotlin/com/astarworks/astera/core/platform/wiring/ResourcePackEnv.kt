package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.domain.hud.ResourcePackSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Logger

/**
 * Reads the resource pack the server should push from the environment
 * (ADR-0182):
 *
 *  - `ASTERA_RESOURCE_PACK_URL`  - where clients download `astera.zip`. Unset or blank: no pack, no push, no error.
 *  - `ASTERA_RESOURCE_PACK_SHA1` - its SHA-1 (40 hex). Unset: fetched once at boot from
 *    `ASTERA_RESOURCE_PACK_SHA1_URL`, or `<url>.sha1` when that is unset too. The separate
 *    variable is for hosts where the public URL is not reachable from the server itself
 *    (docker: clients use `localhost:8099`, the server sees `http://resource-pack/`).
 *  - `ASTERA_RESOURCE_PACK_REQUIRED` - `true` kicks players that decline. Default `false`.
 *
 * A bad value or an unreachable sha1 file logs a warning and disables the
 * push; the server still boots, the HUD just stays on its text form.
 */
public object ResourcePackEnv {

    public const val URL_VAR: String = "ASTERA_RESOURCE_PACK_URL"
    public const val SHA1_VAR: String = "ASTERA_RESOURCE_PACK_SHA1"
    public const val SHA1_URL_VAR: String = "ASTERA_RESOURCE_PACK_SHA1_URL"
    public const val REQUIRED_VAR: String = "ASTERA_RESOURCE_PACK_REQUIRED"

    public val PROMPT_KEY: MessageKey = MessageKey("astera.hud.resource-pack.prompt")

    private val sha1Line = Regex("^[a-fA-F0-9]{40}")

    public fun load(
        env: Map<String, String> = System.getenv(),
        logger: Logger? = null,
        fetchSha1: (String) -> String? = ::httpGet,
    ): ResourcePackSpec? {
        val url = env[URL_VAR]?.trim().orEmpty()
        if (url.isEmpty()) return null
        val sha1 = env[SHA1_VAR]?.trim().orEmpty().ifEmpty {
            // `<hash>  astera.zip` (sha1sum format) or a bare hash; first 40 hex chars win.
            val sha1Url = env[SHA1_URL_VAR]?.trim().orEmpty().ifEmpty { "$url.sha1" }
            fetchSha1(sha1Url)?.trim()?.let { sha1Line.find(it)?.value }.orEmpty()
        }
        if (sha1.isEmpty()) {
            logger?.warning("$URL_VAR is set but no SHA-1 is available ($SHA1_VAR unset and the sha1 file unreachable); resource pack disabled.")
            return null
        }
        return try {
            ResourcePackSpec(
                url = url,
                sha1Hash = sha1.lowercase(),
                required = env[REQUIRED_VAR]?.trim().equals("true", ignoreCase = true),
                promptKey = PROMPT_KEY,
            )
        } catch (ex: IllegalArgumentException) {
            logger?.warning("Invalid resource pack configuration (${ex.message}); resource pack disabled.")
            null
        }
    }

    private fun httpGet(url: String): String? = try {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) response.body() else null
    } catch (ex: java.io.IOException) {
        null
    } catch (ex: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (ex: IllegalArgumentException) {
        null
    }
}
