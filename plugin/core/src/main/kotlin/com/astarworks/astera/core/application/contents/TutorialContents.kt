package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.TutorialYamlConfig
import com.astarworks.astera.core.application.port.outbound.ITutorialRegistry
import com.astarworks.astera.core.domain.tutorial.TutorialScript
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId

/**
 * Tutorial-side `Contents<TutorialScriptId, TutorialScript>` (Wave 5e, RTM
 * issue #17, ADR-0060).
 *
 * Bootstrap directory: `content/tutorials/`. Inherits the substrate's atomic
 * map + reload semantics:
 *
 *   - `find(id) / all()` from [Contents] (the `ITutorialRegistry` port surface
 *     that `RunTutorialUseCase` consumes)
 *   - `bootstrap(dir)` for `onEnable` initial load (best-effort)
 *   - `reload(dir)` for the future `/astera reload tutorials` command
 *     (all-or-nothing, atomic swap — lands when the reload command surface
 *     reaches Tutorials in Wave 6a)
 *   - `register(id, script) / clear()` for tests and any future programmatic
 *     authoring (e.g., dynamically synthesized onboarding flows)
 *
 * No state of its own — the substrate's `AtomicReference<Map<TutorialScriptId,
 * TutorialScript>>` is the single source of truth.
 */
public class TutorialContents : YamlDynamicContents<TutorialScriptId, TutorialScript, TutorialYamlConfig>(
    categoryName = "tutorials",
    deserializer = TutorialYamlConfig.serializer(),
),
    ITutorialRegistry {

    /**
     * Convert each YAML DTO to its `(TutorialScriptId, TutorialScript)` entry.
     * Delegates to [TutorialYamlConfig.toDomain] so any future schema
     * evolution stays single-sourced in the DTO.
     */
    override fun toEntry(config: TutorialYamlConfig): Pair<TutorialScriptId, TutorialScript> {
        val script = config.toDomain()
        return script.id to script
    }

    /**
     * Convenience overload: register by script alone (the script owns its id).
     * Used by tests + any future admin command that builds a [TutorialScript]
     * in memory.
     */
    public fun register(script: TutorialScript) {
        register(script.id, script)
    }
}
