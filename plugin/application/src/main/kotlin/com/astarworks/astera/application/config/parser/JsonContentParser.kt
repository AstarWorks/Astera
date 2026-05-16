package com.astarworks.astera.application.config.parser

import com.astarworks.astera.application.port.outbound.IContentParser
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

/**
 * JSON implementation of [IContentParser], backed by kotlinx-serialization-json.
 *
 * Supports the `.json` extension. JSON is the AI-friendliest wire format per
 * ADR-0016 §"Part 2" — Phase 6 generative agents are expected to emit JSON
 * preferentially because it has the lowest indentation-error rate.
 *
 * The default [Json] instance is configured to be permissive of unknown
 * keys (forward-compatibility when older schemas read newer files) and to
 * skip JSON `null` for missing optional fields. Tighten with a custom
 * instance if a particular schema needs strict validation.
 */
public class JsonContentParser(
    private val json: Json = DEFAULT_JSON,
) : IContentParser {
    override fun supports(extension: String): Boolean =
        extension.equals("json", ignoreCase = true)

    override fun <T> parse(text: String, deserializer: DeserializationStrategy<T>): T =
        json.decodeFromString(deserializer, text)

    private companion object {
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
    }
}
