package com.astarworks.astera.application.port.outbound

import kotlinx.serialization.DeserializationStrategy

/**
 * Outbound port for deserializing a content file (YAML / TOML / JSON / …) into
 * a Kotlin DTO described by a [DeserializationStrategy].
 *
 * Per ADR-0016, the Kotlin `@Serializable` DTO is the single source of truth
 * for every content schema. The wire format (YAML / TOML / JSON) is one more
 * derivation — `ContentLoader` dispatches by file extension to the matching
 * `IContentParser` and never assumes a specific format.
 *
 * Implementations live in `application/config/parser/` so the application
 * layer's serialization-annotation budget stays in one place.
 *
 * @see com.astarworks.astera.application.service.ContentLoader
 */
public interface IContentParser {
    /**
     * Returns `true` if this parser can handle a file whose extension (the
     * substring after the last `.`, without the dot, lower-cased) matches
     * one of this parser's supported wire formats.
     */
    public fun supports(extension: String): Boolean

    /**
     * Deserializes [text] into [T] using [deserializer]. Throws if the text
     * is malformed; callers are responsible for logging and skipping the
     * offending file (see `ContentLoader.loadFrom`).
     */
    public fun <T> parse(text: String, deserializer: DeserializationStrategy<T>): T
}
