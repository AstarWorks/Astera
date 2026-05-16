package com.astarworks.astera.core.application.config.parser

import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import kotlinx.serialization.DeserializationStrategy

/**
 * YAML implementation of [IContentParser], backed by kaml (YAML 1.2 compliant).
 *
 * Supports both `.yaml` and `.yml` extensions, plus standard YAML
 * **anchor / alias** (`&name` / `*name`) per ADR-0016 §"Part 3". Contributors
 * may define an effect once with `&spark` and reuse it inline elsewhere in
 * the same file — Astera explicitly enables kaml's [AnchorsAndAliases.Permitted]
 * mode (kaml defaults to `Forbidden` for billion-laughs protection; Astera
 * trusts its own `content/` and accepts the 100-alias cap as the cycle guard).
 *
 * Cross-file reuse (`@ref:<path>`) is handled by `ContentRefResolver` as a
 * text-level pre-processing step, not by this parser.
 */
public class YamlContentParser(
    private val yaml: Yaml = DEFAULT_YAML,
) : IContentParser {
    override fun supports(extension: String): Boolean =
        extension.equals("yaml", ignoreCase = true) ||
            extension.equals("yml", ignoreCase = true)

    override fun <T> parse(text: String, deserializer: DeserializationStrategy<T>): T =
        yaml.decodeFromString(deserializer, text)

    private companion object {
        /**
         * The default Yaml instance used by Astera: kaml's `Yaml.default`
         * configuration with `AnchorsAndAliases.Permitted` enabled. The 100-
         * alias cap is kaml's default for the Permitted variant and is more
         * than enough for any realistic Astera content file.
         *
         * `polymorphismStyle = Property` + `polymorphismPropertyName = "type"`
         * align kaml with the kotlinx-JSON `@JsonClassDiscriminator("type")`
         * convention used by Astera sealed DTOs (skill / status `params`).
         * Same discriminator across YAML and JSON keeps content authoring
         * uniform.
         */
        val DEFAULT_YAML: Yaml = Yaml(
            configuration = Yaml.default.configuration.copy(
                anchorsAndAliases = AnchorsAndAliases.Permitted(maxAliasCount = 100u),
                polymorphismStyle = PolymorphismStyle.Property,
                polymorphismPropertyName = "type",
            ),
        )
    }
}
