package com.astarworks.astera.core.application.skillengine.load

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver

/**
 * The one YAML reader every skill-engine file goes through (skills, parts, body / item frame tables, effect headers): YAML 1.2 scalars.
 * `on` / `off` / `yes` / `no` are plain strings (only `true` / `false` are booleans), timestamps stay strings, duplicate keys are an error.
 */
internal object StrictYaml {
    private class StrictResolver : Resolver() {
        override fun addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, Regex("^(?:true|false)$").toPattern(), "tf")
            addImplicitResolver(Tag.INT, INT, "-+0123456789")
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.")
            addImplicitResolver(Tag.MERGE, MERGE, "<")
            addImplicitResolver(Tag.NULL, NULL, "~nN\u0000")
            addImplicitResolver(Tag.NULL, EMPTY, null)
        }
    }

    fun newYaml(): Yaml {
        val opts = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            setAllowRecursiveKeys(false)
            maxAliasesForCollections = 200
        }
        val dumper = DumperOptions()
        return Yaml(SafeConstructor(opts), Representer(dumper), dumper, opts, StrictResolver())
    }
}
