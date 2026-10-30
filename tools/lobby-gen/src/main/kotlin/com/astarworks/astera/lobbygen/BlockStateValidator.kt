package com.astarworks.astera.lobbygen

import com.astarworks.astera.core.application.skillengine.load.VanillaRegistryKeys

/** One placed block of a generated plan, for error reporting. */
data class BlockUse(val x: Int, val y: Int, val z: Int, val state: String)

/**
 * Server-free check of generator block-data strings against the real block registry of our Paper/MC version
 * (`block-registry.txt` in `:kernel:runtime`, produced from the server jar's data report by `scripts/gen-block-registry.py`), via
 * [VanillaRegistryKeys].
 */
object BlockStateValidator {
    /** `null` when [state] is a valid block-data string, otherwise the reason. */
    fun problem(state: String): String? = VanillaRegistryKeys.blockProblem(state)

    /** One line per distinct invalid string, naming [label], the string, the reason, the first position and the count. */
    fun check(label: String, blocks: Iterable<BlockUse>): List<String> {
        val first = LinkedHashMap<String, BlockUse>()
        val count = HashMap<String, Int>()
        val reasons = HashMap<String, String>()
        for (use in blocks) {
            val reason = reasons.getOrPut(use.state) { problem(use.state) ?: "" }
            if (reason.isEmpty()) continue
            first.putIfAbsent(use.state, use)
            count.merge(use.state, 1, Int::plus)
        }
        return first.map { (state, use) ->
            "$label: invalid block '$state' at (${use.x}, ${use.y}, ${use.z}) x${count[state]}: ${reasons[state]}"
        }
    }
}
