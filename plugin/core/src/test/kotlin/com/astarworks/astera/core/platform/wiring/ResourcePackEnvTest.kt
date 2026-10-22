package com.astarworks.astera.core.platform.wiring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResourcePackEnvTest {

    private val sha = "0123456789abcdef0123456789abcdef01234567"
    private val noFetch: (String) -> String? = { error("must not fetch: $it") }

    @Test
    fun `no URL means no pack and no error`() {
        assertThat(ResourcePackEnv.load(emptyMap(), fetchSha1 = noFetch)).isNull()
        assertThat(ResourcePackEnv.load(mapOf("ASTERA_RESOURCE_PACK_URL" to "  "), fetchSha1 = noFetch)).isNull()
    }

    @Test
    fun `URL and SHA-1 from the environment`() {
        val spec = checkNotNull(
            ResourcePackEnv.load(
                mapOf("ASTERA_RESOURCE_PACK_URL" to "http://host:8099/astera.zip", "ASTERA_RESOURCE_PACK_SHA1" to sha.uppercase()),
                fetchSha1 = noFetch,
            ),
        )
        assertThat(spec.url).isEqualTo("http://host:8099/astera.zip")
        assertThat(spec.sha1Hash).isEqualTo(sha)
        assertThat(spec.required).isFalse()
        assertThat(spec.promptKey.value).isEqualTo("astera.hud.resource-pack.prompt")
    }

    @Test
    fun `without a SHA-1 variable the sha1 file next to the zip is fetched, sha1sum format accepted`() {
        var asked: String? = null
        val spec = checkNotNull(
            ResourcePackEnv.load(mapOf("ASTERA_RESOURCE_PACK_URL" to "http://host/astera.zip")) { url ->
                asked = url
                "$sha  astera.zip\n"
            },
        )
        assertThat(asked).isEqualTo("http://host/astera.zip.sha1")
        assertThat(spec.sha1Hash).isEqualTo(sha)
    }

    @Test
    fun `the sha1 file can live at a different URL than the public one`() {
        var asked: String? = null
        val env = mapOf(
            "ASTERA_RESOURCE_PACK_URL" to "http://localhost:8099/astera.zip",
            "ASTERA_RESOURCE_PACK_SHA1_URL" to "http://resource-pack/astera.zip.sha1",
        )
        val spec = checkNotNull(ResourcePackEnv.load(env) { url -> asked = url; sha })
        assertThat(asked).isEqualTo("http://resource-pack/astera.zip.sha1")
        assertThat(spec.url).isEqualTo("http://localhost:8099/astera.zip")
    }

    @Test
    fun `an unreachable or garbage sha1 file disables the pack instead of failing the boot`() {
        val env = mapOf("ASTERA_RESOURCE_PACK_URL" to "http://host/astera.zip")
        assertThat(ResourcePackEnv.load(env) { null }).isNull()
        assertThat(ResourcePackEnv.load(env) { "<html>404</html>" }).isNull()
    }

    @Test
    fun `a malformed URL disables the pack, and required can be switched on`() {
        assertThat(
            ResourcePackEnv.load(mapOf("ASTERA_RESOURCE_PACK_URL" to "ftp://x/y.zip", "ASTERA_RESOURCE_PACK_SHA1" to sha), fetchSha1 = noFetch),
        ).isNull()
        val required = checkNotNull(
            ResourcePackEnv.load(
                mapOf(
                    "ASTERA_RESOURCE_PACK_URL" to "https://x/y.zip",
                    "ASTERA_RESOURCE_PACK_SHA1" to sha,
                    "ASTERA_RESOURCE_PACK_REQUIRED" to "TRUE",
                ),
                fetchSha1 = noFetch,
            ),
        )
        assertThat(required.required).isTrue()
    }
}
