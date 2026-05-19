package com.astarworks.astera.contentlint

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.yaml.snakeyaml.Yaml
import java.io.Reader

/**
 * Bridge between SnakeYAML's untyped object tree and Jackson's [JsonNode].
 *
 * `json-schema-validator` (com.networknt) operates on [JsonNode]; the
 * canonical way to feed it YAML is to parse with SnakeYAML and then call
 * `ObjectMapper.valueToTree(...)` on the resulting `Map<String, Any?>`.
 *
 * This is intentionally a tiny helper, not a class: the validator is
 * stateless and we want zero ceremony at call sites.
 */
internal object YamlToJson {

    private val mapper: ObjectMapper = ObjectMapper()

    /**
     * Parses [reader] as YAML and returns the equivalent Jackson tree.
     *
     * For an empty YAML document (or one containing only comments) this
     * returns a JSON `null` node — callers should treat that as a schema
     * violation in their own logic (every content file should produce an
     * object).
     */
    fun parse(reader: Reader): JsonNode {
        val parsed: Any? = Yaml().load<Any?>(reader)
        return mapper.valueToTree<JsonNode>(parsed)
    }
}
