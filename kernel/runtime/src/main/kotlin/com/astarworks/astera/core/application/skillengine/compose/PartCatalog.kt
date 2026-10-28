package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.skillengine.part.Machine
import com.astarworks.astera.core.domain.skillengine.part.Part
import java.util.concurrent.ConcurrentHashMap

/** Parts by id (`content/skill-parts`). */
public interface PartCatalog {
    public fun part(id: String): Part?
}

/**
 * [base] with a thread-safe overlay: a reloaded `kind: Part` document replaces (or adds) a part, a removed one hides it.
 * [ids] is what a skill may name in `use:` right now.
 */
public class OverlayPartCatalog(private val base: PartCatalog, private val baseIds: Set<String>) : PartCatalog {
    private val overlay = ConcurrentHashMap<String, Part>()
    private val hidden: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun part(id: String): Part? = if (id in hidden) null else overlay[id] ?: base.part(id)

    public fun ids(): Set<String> = (baseIds + overlay.keys) - hidden

    public fun put(part: Part) {
        hidden.remove(part.id)
        overlay[part.id] = part
    }

    public fun remove(id: String) {
        overlay.remove(id)
        hidden.add(id)
    }
}

/** The ids a machine calls with `use`, found in state calls and in raw op maps at any depth (groups, `on` handlers, transition `do`). */
internal object UseCollector {
    /** Part ids (`use: <part>`) and skill ids (`use: skill:<id>`) written directly in [m]. */
    fun direct(m: Machine, parts: MutableSet<String>, skills: MutableSet<String>) {
        for (s in m.states.values) {
            s.use?.let { add(it.part, parts, skills) }
            (s.enter + s.doOps + s.exit).forEach { walk(it, parts, skills) }
            s.on.forEach { t -> t.effects.forEach { walk(it, parts, skills) } }
            s.parallel.forEach { direct(it, parts, skills) }
        }
    }

    private fun add(target: String, parts: MutableSet<String>, skills: MutableSet<String>) {
        if (target.startsWith(SkillCallInliner.PREFIX)) skills += target.removePrefix(SkillCallInliner.PREFIX) else parts += target
    }

    private fun walk(node: Any?, parts: MutableSet<String>, skills: MutableSet<String>) {
        when (node) {
            is Map<*, *> -> node.forEach { (k, v) ->
                if (k == "use" && v is String) add(v, parts, skills) else walk(v, parts, skills)
            }
            is List<*> -> node.forEach { walk(it, parts, skills) }
        }
    }
}
