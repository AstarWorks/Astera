package com.astarworks.astera.architecture

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * "Silent sound" guard (bots-qa). A cue that names an `astera:*` sound event that the resource pack does not ship is
 * dropped without any error and no state assertion notices; the founder used to find these by ear. This rule fails the
 * build instead when:
 *  - a content YAML (`sound_id:` / `cue:` / `audience_cue:`) or a production Kotlin `SoundId("astera:...")` literal names
 *    an event that is not shipped, or
 *  - a `MusicTrackId("...")` literal names a BGM track with no `content/music/<id>.ogg`, or
 *  - a `content/sounds/**` registry YAML has no OGG next to it (the pack builder then emits no event for it), or
 *  - a `content/music/*.yaml` track has no OGG next to it.
 *
 * Event resolution mirrors `ResourcePackBuilder.addSounds`: a registry YAML with an OGG names its event by `id`
 * (namespace stripped); a bare OGG is its own event; `content/music/<n>.ogg` is the event `music/<n>`.
 */
class SoundCueCoverageTest {

    private val root: Path = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }

    private val soundsDir = root.resolve("content/sounds")
    private val musicDir = root.resolve("content/music")

    private fun files(dir: Path, ext: String): List<Path> =
        if (!Files.isDirectory(dir)) emptyList() else Files.walk(dir).use { s -> s.filter { it.extension == ext }.toList() }

    private fun key(dir: Path, file: Path): String = dir.relativize(file).toString().replace('\\', '/').substringBeforeLast('.')

    private fun yamlId(file: Path): String? =
        Regex("(?m)^id:\\s*[\"']?([^\"'\\s#]+)").find(file.readText())?.groupValues?.get(1)

    private fun shippedEvents(): Set<String> {
        val oggKeys = files(soundsDir, "ogg").map { key(soundsDir, it) }.toSet()
        val events = mutableSetOf<String>()
        events += oggKeys
        for (y in files(soundsDir, "yaml")) {
            if (key(soundsDir, y) !in oggKeys) continue
            yamlId(y)?.substringAfter(':')?.let { events += it }
        }
        events += files(musicDir, "ogg").map { "music/" + key(musicDir, it) }
        return events
    }

    private fun productionKotlin(): List<Path> =
        java.util.stream.Stream.of("plugin", "kernel", "core").flatMap { Files.walk(root.resolve(it)) }.use { s ->
            s.filter { it.extension == "kt" && it.toString().contains("/src/main/") }.toList()
        }

    @Test
    fun `every astera sound event named by content or code is shipped in the pack`() {
        val events = shippedEvents()
        val yamlRef = Regex("(?m)^\\s*(?:-\\s*)?(?:sound_id|cue|audience_cue):\\s*[\"']?(astera:[a-z0-9_/.-]+)")
        val codeRef = Regex("SoundId\\(\"(astera:[a-z0-9_/.-]+)\"\\)")
        val missing = sortedSetOf<String>()
        fun check(id: String, where: Path) {
            if (id.substringAfter(':') !in events) missing += "$id (${root.relativize(where)})"
        }
        val contentYaml = files(root.resolve("content"), "yaml").filter { !it.startsWith(soundsDir) && !it.startsWith(musicDir) }
        for (f in contentYaml) yamlRef.findAll(f.readText()).forEach { check(it.groupValues[1], f) }
        for (f in productionKotlin()) codeRef.findAll(f.readText()).forEach { check(it.groupValues[1], f) }
        assertThat(missing).`as`("astera:* sound events referenced but not shipped (no OGG): a silent cue").isEmpty()
    }

    @Test
    fun `every BGM track id named in production code has an ogg`() {
        val tracks = files(musicDir, "ogg").map { key(musicDir, it) }.toSet()
        val ref = Regex("MusicTrackId\\(\"([a-z0-9_-]+)\"\\)")
        val missing = productionKotlin().flatMap { f -> ref.findAll(f.readText()).map { "${it.groupValues[1]} (${root.relativize(f)})" }.toList() }
            .filter { it.substringBefore(' ') !in tracks }.distinct().sorted()
        assertThat(missing).`as`("BGM tracks referenced in code but missing content/music/<id>.ogg: a missing BGM").isEmpty()
    }

    @Test
    fun `every sound and music registry yaml has its ogg`() {
        val oggSounds = files(soundsDir, "ogg").map { key(soundsDir, it) }.toSet()
        val oggMusic = files(musicDir, "ogg").map { key(musicDir, it) }.toSet()
        val soundsWithout = files(soundsDir, "yaml").map { key(soundsDir, it) }.filter { it !in oggSounds }.sorted()
        val musicWithout = files(musicDir, "yaml").map { key(musicDir, it) }.filter { it !in oggMusic }.sorted()
        assertThat(soundsWithout).`as`("content/sounds registry yaml without an OGG (the pack ships no event for it)").isEmpty()
        assertThat(musicWithout).`as`("content/music yaml without an OGG").isEmpty()
    }
}
