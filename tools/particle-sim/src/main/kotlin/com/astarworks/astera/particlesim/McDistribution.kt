package com.astarworks.astera.particlesim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Where the unobfuscated client jar and its libraries live. Nothing from it is copied into the repo: the jar is read in
 * place. Lookup order: `-Dastera.particlesim.mcdir` / `ASTERA_MC_DIR`, the vfx-shot launcher cache
 * (`~/.cache/astera-vfx-shot/mcdir`), then our own cache (`~/.cache/astera-particle-sim/mcdir`, filled from the official
 * launcher manifest when missing).
 */
class McDistribution(val version: String, val clientJar: Path, val libraries: List<Path>) {

    companion object {
        const val DEFAULT_VERSION: String = "26.3"
        private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

        fun locate(version: String = DEFAULT_VERSION): McDistribution {
            val home = Paths.get(System.getProperty("user.home"))
            val candidates = listOfNotNull(
                System.getProperty("astera.particlesim.mcdir") ?: System.getenv("ASTERA_MC_DIR"),
            ).map { Paths.get(it) } + listOf(
                home.resolve(".cache/astera-vfx-shot/mcdir"),
                home.resolve(".cache/astera-particle-sim/mcdir"),
            )
            for (dir in candidates) {
                val jar = dir.resolve("versions/$version/$version.jar")
                if (Files.isRegularFile(jar) && Files.isDirectory(dir.resolve("libraries"))) return open(dir, version)
            }
            val dir = home.resolve(".cache/astera-particle-sim/mcdir")
            System.err.println("particle-sim: no local $version client found, downloading it from the launcher manifest into $dir")
            download(dir, version)
            return open(dir, version)
        }

        private fun open(dir: Path, version: String): McDistribution {
            val jar = dir.resolve("versions/$version/$version.jar")
            val libs = Files.walk(dir.resolve("libraries")).use { s ->
                s.filter { it.toString().endsWith(".jar") }.sorted().toList()
            }
            return McDistribution(version, jar, libs.filterNot { "natives" in it.fileName.toString() })
        }

        // ---- download ----------------------------------------------------------------------------------------------

        private val http: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

        private fun getJson(url: String): JsonObject =
            Json.parseToJsonElement(String(fetch(url))).jsonObject

        private fun fetch(url: String): ByteArray {
            val rsp = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            check(rsp.statusCode() == 200) { "GET $url -> ${rsp.statusCode()}" }
            return rsp.body()
        }

        private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun download(dir: Path, version: String) {
            val manifest = getJson(MANIFEST)
            val entry = manifest["versions"]!!.jsonArray.map { it.jsonObject }
                .firstOrNull { it["id"]!!.jsonPrimitive.content == version }
                ?: error("version $version is not in the launcher manifest")
            val vjson = getJson(entry["url"]!!.jsonPrimitive.content)
            fun save(file: Path, url: String, sha: String?) {
                if (Files.isRegularFile(file)) return
                val bytes = fetch(url)
                if (sha != null) check(sha1(bytes) == sha) { "sha1 mismatch for $url" }
                Files.createDirectories(file.parent)
                Files.write(file, bytes)
            }
            val client = vjson["downloads"]!!.jsonObject["client"]!!.jsonObject
            save(dir.resolve("versions/$version/$version.jar"), client["url"]!!.jsonPrimitive.content, client["sha1"]?.jsonPrimitive?.content)
            for (lib in vjson["libraries"]!!.jsonArray.map { it.jsonObject }) {
                val name = lib["name"]!!.jsonPrimitive.content
                if ("natives" in name || lib["rules"] != null) continue // natives are never loaded by the simulator
                val art = lib["downloads"]!!.jsonObject["artifact"]?.jsonObject ?: continue
                save(dir.resolve("libraries/" + art["path"]!!.jsonPrimitive.content), art["url"]!!.jsonPrimitive.content, art["sha1"]?.jsonPrimitive?.content)
            }
        }

    }
}
