package com.astarworks.astera.application.service

import com.astarworks.astera.application.port.outbound.IContentParser
import kotlinx.serialization.DeserializationStrategy
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Generic, format-agnostic loader for `content/` files (ADR-0016).
 *
 * Walks a directory non-recursively, finds an [IContentParser] for each file
 * by extension, optionally pre-processes the text via [ContentRefResolver] to
 * resolve `@ref:` cross-file references, then deserializes into [T] and emits
 * each successfully-loaded value via [loadFrom]'s `onLoaded` callback.
 *
 * # Failure mode
 *
 * A malformed file logs at ERROR and is counted as `failed`; loading continues
 * for the remaining files. The batch is never aborted partway through —
 * content authors should see all of their errors at once, not just the first.
 *
 * # Scope
 *
 * Phase 2 mid: non-recursive. Each content type lives in its own directory
 * (`content/weapons/`, `content/skills/`, …) so a flat walk is enough. Phase 4
 * UGC marketplace may revisit this if shared sub-directories appear.
 *
 * # Wire format
 *
 * Pass any subset of `YamlContentParser`, `TomlContentParser`,
 * `JsonContentParser`. Parser order matters only for files whose extension
 * matches multiple parsers (none, by default); the first match wins.
 */
public class ContentLoader<T : Any>(
    private val parsers: List<IContentParser>,
    private val deserializer: DeserializationStrategy<T>,
    private val refResolver: ContentRefResolver = ContentRefResolver.disabled(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Outcome of a [loadFrom] invocation. */
    public data class LoadReport(val loaded: Int, val failed: Int)

    /**
     * Walks [dir] non-recursively, parses each file whose extension matches a
     * registered parser, and invokes [onLoaded] for each successful parse.
     *
     * Files whose extension matches no parser are silently ignored
     * (`README.md`, `notes.txt`, etc. coexist with content files).
     *
     * A missing or non-directory [dir] logs a warning and returns a zero report.
     */
    public fun loadFrom(dir: Path, onLoaded: (T) -> Unit): LoadReport {
        if (!Files.isDirectory(dir)) {
            log.warn("Content directory not found: {}", dir)
            return LoadReport(loaded = 0, failed = 0)
        }
        var loaded = 0
        var failed = 0
        Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .forEach { file ->
                    val parser = parserFor(file.extension)
                    if (parser == null) {
                        // Not an error — unknown extensions are silently ignored.
                        return@forEach
                    }
                    if (loadFile(file, parser, onLoaded)) loaded++ else failed++
                }
        }
        log.info("Content load complete: {} ok, {} failed (dir={})", loaded, failed, dir)
        return LoadReport(loaded, failed)
    }

    private fun parserFor(extension: String): IContentParser? =
        parsers.firstOrNull { it.supports(extension) }

    private fun loadFile(file: Path, parser: IContentParser, onLoaded: (T) -> Unit): Boolean = try {
        val rawText = Files.readString(file)
        val resolvedText = refResolver.resolve(rawText, file.parent ?: file)
        val value = parser.parse(resolvedText, deserializer)
        onLoaded(value)
        log.info("Loaded content file: {}", file.name)
        true
    } catch (t: Throwable) {
        log.error("Failed to load content from {}: {}", file, t.message, t)
        false
    }
}
