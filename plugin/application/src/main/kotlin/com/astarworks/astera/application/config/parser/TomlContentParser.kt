package com.astarworks.astera.application.config.parser

import com.astarworks.astera.application.port.outbound.IContentParser
import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.DeserializationStrategy

/**
 * TOML implementation of [IContentParser], backed by ktoml.
 *
 * Supports the `.toml` extension. TOML is type-strict (no implicit string-to-
 * number coercion) which makes it a good fit for AI-generated content per
 * ADR-0016 §"Part 2".
 *
 * The default [Toml] instance uses ktoml's standard input config, which is
 * sufficient for Astera's flat-ish content schemas; specialized config can be
 * injected if a particular schema needs lenient parsing.
 */
public class TomlContentParser(
    private val toml: Toml = Toml(),
) : IContentParser {
    override fun supports(extension: String): Boolean =
        extension.equals("toml", ignoreCase = true)

    override fun <T> parse(text: String, deserializer: DeserializationStrategy<T>): T =
        toml.decodeFromString(deserializer, text)
}
