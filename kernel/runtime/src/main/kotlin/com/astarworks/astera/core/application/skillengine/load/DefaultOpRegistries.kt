package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.skillengine.op.OpSection

/** Decoder registry keyed by (section, op name). Registering two decoders for the same name is a wiring bug and fails fast. */
public class DefaultOpDecoderRegistry : OpDecoderRegistry {
    private val bySection = HashMap<OpSection, MutableMap<String, OpDecoder>>()

    @Synchronized
    override fun register(decoder: OpDecoder) {
        val names = bySection.getOrPut(decoder.section) { HashMap() }
        for (name in decoder.opNames) {
            val previous = names.put(name, decoder)
            require(previous == null) { "op '$name' is registered twice in section ${decoder.section}" }
        }
    }

    @Synchronized
    override fun find(section: OpSection, op: String): OpDecoder? = bySection[section]?.get(op)

    /** Every registered op name per section (for lint / schema output). */
    @Synchronized
    public fun names(section: OpSection): Set<String> = bySection[section]?.keys?.toSet().orEmpty()
}

/**
 * Entry point of the wiring: each op family exposes `fun <family>Decoders(): List<OpDecoder>`, the manager passes them
 * all here. There is no central list of ops anywhere else.
 */
public object DefaultOpRegistries {
    public fun decoders(vararg families: List<OpDecoder>): DefaultOpDecoderRegistry =
        DefaultOpDecoderRegistry().also { registry -> families.forEach { list -> list.forEach(registry::register) } }
}
