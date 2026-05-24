package com.astarworks.astera.match.application.contents

import com.astarworks.astera.core.application.contents.YamlDynamicContents
import com.astarworks.astera.match.application.config.gamemode.GameModeSpec
import com.astarworks.astera.match.application.config.gamemode.GameModeYamlConfig
import com.astarworks.astera.match.application.port.outbound.IGameModeFrameworkRegistry
import com.astarworks.astera.match.domain.gamemode.IGameMode
import com.astarworks.astera.match.domain.mode.GameModeId

/**
 * `Contents<GameModeId, GameModeSpec>` for `content/game-modes/<id>.yaml`
 * (ADR-0106, Wave 5gamemode).
 *
 * Mirrors the established [com.astarworks.astera.core.application.contents.ParticleContents]
 * + [GeneratorContents] + [AnomalyContents] pattern: subclass of
 * [YamlDynamicContents] over the YAML DTO; participates in the
 * `/astera reload game-modes` dispatcher; bootstraps once at `onEnable`.
 *
 * ## Two-stage registration
 *
 * The spec carries an `implClass` FQN. The Phase 1 PoC resolves the impl by
 * reflection (no-arg constructor) and registers the resulting [IGameMode]
 * singleton into the supplied [IGameModeFrameworkRegistry]. This is invoked
 * after [bootstrap] / [reload] succeeds via [registerInto]; we keep the
 * resolution out of [toEntry] so the `Contents` substrate stays a pure
 * map-of-spec and the registry-side concern (which depends on the
 * `IGameModeFrameworkRegistry` port) stays in `application.service`.
 *
 * The orchestrator calls [registerInto] after bootstrap; tests may
 * instantiate this class without a registry and inspect [all] directly.
 *
 * ### Reflection caveat (Phase 1)
 *
 * `Class.forName(spec.implClass).getDeclaredConstructor().newInstance()` is
 * intentionally simple. A misconfigured YAML (typo'd FQN) logs a warning and
 * skips that mode; the rest of the registry still loads. Phase 3 (Koin DI)
 * replaces this with a proper DI lookup; until then the reflection path is
 * the smallest-change route to get YAML-driven mode discovery working.
 */
public class GameModeContents : YamlDynamicContents<GameModeId, GameModeSpec, GameModeYamlConfig>(
    categoryName = "game-modes",
    deserializer = GameModeYamlConfig.serializer(),
) {

    override fun toEntry(config: GameModeYamlConfig): Pair<GameModeId, GameModeSpec> {
        val spec = config.toSpec()
        return spec.id to spec
    }

    /**
     * Resolve each spec's `implClass` and register the resulting [IGameMode]
     * instance into [registry]. Returns the list of mode-ids successfully
     * registered (failures are logged via [warn] and skipped, mirroring the
     * `bootstrap` failure semantics).
     *
     * @param warn callback for "this spec failed to resolve" diagnostics
     *   (orchestrator wires `logger.warning`).
     */
    public fun registerInto(
        registry: IGameModeFrameworkRegistry,
        warn: (String) -> Unit = {},
    ): List<GameModeId> {
        val registered = mutableListOf<GameModeId>()
        for (spec in all()) {
            val mode = resolveImpl(spec, warn) ?: continue
            if (mode.id() != spec.id) {
                warn(
                    "game-modes: spec id '${spec.id.value}' does not match " +
                        "impl-reported id '${mode.id().value}' (impl=${spec.implClass}); skipping.",
                )
                continue
            }
            registry.register(mode)
            registered += spec.id
        }
        return registered
    }

    private fun resolveImpl(spec: GameModeSpec, warn: (String) -> Unit): IGameMode? {
        return try {
            val cls = Class.forName(spec.implClass, true, this.javaClass.classLoader)
            val ctor = cls.getDeclaredConstructor()
            ctor.isAccessible = true
            val instance = ctor.newInstance()
            instance as? IGameMode ?: run {
                warn(
                    "game-modes: impl class '${spec.implClass}' does not implement IGameMode; skipping mode '${spec.id.value}'.",
                )
                null
            }
        } catch (e: ClassNotFoundException) {
            warn("game-modes: impl class '${spec.implClass}' not found on classpath; skipping mode '${spec.id.value}'.")
            null
        } catch (e: NoSuchMethodException) {
            warn("game-modes: impl class '${spec.implClass}' missing no-arg constructor; skipping mode '${spec.id.value}'.")
            null
        } catch (e: Throwable) {
            warn("game-modes: impl class '${spec.implClass}' failed to instantiate (${e.javaClass.simpleName}: ${e.message}); skipping mode '${spec.id.value}'.")
            null
        }
    }
}
