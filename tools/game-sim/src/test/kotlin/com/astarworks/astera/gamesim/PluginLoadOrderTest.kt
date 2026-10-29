package com.astarworks.astera.gamesim

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The simulator boots plugins in [GameSim.WIRING_ORDER]. That order must be a
 * valid enable order for the real `plugin.yml` graph (each plugin after all its
 * depend/softdepend entries), otherwise the sim would hide load-order bugs such
 * as the Items/Combat softdepend cycle that left `CastSkillUseCase` unwired.
 */
class PluginLoadOrderTest {

    private fun list(text: String, key: String): List<String> =
        text.lines().firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.removePrefix("[")?.removeSuffix("]")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

    @Test
    fun `sim wiring order is a valid topological order of the plugin yml graph`() {
        val loader = Thread.currentThread().contextClassLoader
        val ymls = loader.getResources("plugin.yml").toList().map { it.readText() }
            .filter { yml -> yml.lines().any { it.startsWith("name: Astera-") } }
        val deps = ymls.associate { yml ->
            val name = yml.lines().first { it.startsWith("name:") }.substringAfter(':').trim()
            name to (list(yml, "depend") + list(yml, "softdepend"))
        }
        // Chat is exercised by its own scenario tests, Persistence needs a database: the sim wires the rest.
        assertThat(GameSim.WIRING_ORDER).isSubsetOf(deps.keys)
        assertThat(deps.keys - GameSim.WIRING_ORDER.toSet()).containsExactlyInAnyOrder("Astera-Chat")
        GameSim.WIRING_ORDER.forEachIndexed { index, name ->
            val earlier = GameSim.WIRING_ORDER.take(index).toSet()
            deps.getValue(name).filter { it in deps }.forEach { dep ->
                assertThat(dep).`as`("$name must be wired after $dep").isIn(earlier)
            }
        }
    }
}
