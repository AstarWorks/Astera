package com.astarworks.astera.resourcepack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Source of Mojang's vanilla core shaders for the version the pack targets. They are not committed (Mojang
 * source, public repo): they come from the client jar and are only ever held in the build machine's cache.
 *
 * Resolution order for `core/<file>`:
 *  1. `$ASTERA_VANILLA_CLIENT_JAR` (a local client jar, e.g. from a launcher install);
 *  2. the cache `~/.cache/astera/vanilla-shaders/<version>/` (filled by step 3);
 *  3. download of the client jar named by Mojang's version manifest (SHA-1 verified), shader files extracted
 *     into the cache, jar discarded.
 */
public fun interface VanillaShaders {
    /** Text of `assets/minecraft/shaders/core/<file>` in the vanilla client. */
    public fun coreShader(file: String): String

    public companion object {
        /** `-Dastera.minecraftVersion=<v>` (set by Gradle from the version catalog) selects the client version. */
        public fun fromEnvironment(): VanillaShaders {
            val version = checkNotNull(System.getProperty("astera.minecraftVersion")) {
                "system property astera.minecraftVersion is not set (gradle/libs.versions.toml: minecraft)"
            }
            return ClientJarShaders(version)
        }
    }
}

internal class ClientJarShaders(private val version: String) : VanillaShaders {

    private val cacheDir: Path = Path.of(System.getProperty("user.home"), ".cache", "astera", "vanilla-shaders", version)

    override fun coreShader(file: String): String {
        val cached = cacheDir.resolve(file)
        if (!Files.isRegularFile(cached)) fill()
        check(Files.isRegularFile(cached)) { "vanilla $version client has no $ENTRY_DIR$file" }
        return Files.readString(cached)
    }

    private fun fill() {
        val local = System.getenv("ASTERA_VANILLA_CLIENT_JAR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        if (local != null) {
            extract(local)
            return
        }
        val http = HttpClient.newHttpClient()
        val manifest = getJson(http, MANIFEST_URL)
        val entry = manifest.path("versions").firstOrNull { it.path("id").asText() == version }
            ?: error("Minecraft $version is not in Mojang's version manifest")
        val client = getJson(http, entry.path("url").asText()).path("downloads").path("client")
        val jar = Files.createTempFile("astera-client-", ".jar")
        try {
            val bytes = http.send(
                HttpRequest.newBuilder(URI.create(client.path("url").asText())).build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            ).body()
            val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
            check(sha1 == client.path("sha1").asText()) { "client jar SHA-1 mismatch for $version" }
            Files.write(jar, bytes)
            extract(jar)
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    private fun extract(jar: Path) {
        Files.createDirectories(cacheDir)
        ZipFile(jar.toFile()).use { zip ->
            var found = 0
            for (entry in zip.entries().asSequence()) {
                if (entry.isDirectory || !entry.name.startsWith(ENTRY_DIR)) continue
                val name = entry.name.removePrefix(ENTRY_DIR)
                if ('/' in name) continue
                zip.getInputStream(entry).use { Files.copy(it, cacheDir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                found++
            }
            check(found > 0) { "$jar contains no $ENTRY_DIR (wrong Minecraft client jar?)" }
        }
    }

    private fun getJson(http: HttpClient, url: String): JsonNode {
        val response = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == 200) { "GET $url -> ${response.statusCode()}" }
        return JsonMapper.builder().build().readTree(response.body())
    }

    private companion object {
        const val ENTRY_DIR = "assets/minecraft/shaders/core/"
        const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }
}
