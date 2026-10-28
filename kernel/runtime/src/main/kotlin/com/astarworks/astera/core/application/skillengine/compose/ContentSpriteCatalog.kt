package com.astarworks.astera.core.application.skillengine.compose

import java.nio.file.Files
import java.nio.file.Path

/**
 * [SpriteCatalog] over the yaml files of `content/sprites` (`id`, `frames`, `size` = the square frame edge in pixels, written by
 * `:tools:item-art`). The files are flat `key: value` lines, so they are read as such; a file without `id` and `size` is skipped
 * and a missing directory is an empty catalog (an unknown sprite then fails at the op as `UndefinedReference`).
 */
public class ContentSpriteCatalog private constructor(private val sizes: Map<String, Vec2Px>) : SpriteCatalog {
    override fun natural(id: String): Vec2Px? = sizes[id]

    public companion object {
        public fun load(dir: Path): ContentSpriteCatalog {
            if (!Files.isDirectory(dir)) return ContentSpriteCatalog(emptyMap())
            val out = LinkedHashMap<String, Vec2Px>()
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().endsWith(".yaml") }.sorted().forEach { f ->
                    val kv = Files.readAllLines(f).mapNotNull { line ->
                        if (line.startsWith("#") || line.startsWith(" ") || !line.contains(':')) null
                        else line.substringBefore(':').trim() to line.substringAfter(':').trim()
                    }.toMap()
                    val id = kv["id"] ?: return@forEach
                    val size = kv["size"]?.toIntOrNull() ?: return@forEach
                    out[id] = Vec2Px(size, size)
                }
            }
            return ContentSpriteCatalog(out)
        }
    }
}
