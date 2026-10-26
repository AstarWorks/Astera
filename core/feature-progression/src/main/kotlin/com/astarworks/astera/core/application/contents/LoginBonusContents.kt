package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.loginbonus.LoginBonusYamlConfig
import com.astarworks.astera.core.application.port.outbound.IContentParser
import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusRegistry
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.loginbonus.LoginBonusSpec
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Login-bonus rotation registry (Wave J 5server-content-ops, ADR-0127 — Phase 2 ready skeleton).
 *
 * Unlike most YAML-backed Contents, each `content/login-bonus/X.yaml` file
 * describes a *list* of day entries — so this class can't extend
 * [YamlDynamicContents], whose `toEntry` returns exactly one pair per file.
 * Instead it reuses [ContentLoader] directly, expanding each file's
 * `days: [...]` list into N `(day, spec)` entries.
 *
 * Atomic-swap semantics on [reload] match `YamlDynamicContents`: a single
 * file-level parse failure aborts the swap with the previous state intact.
 * `bootstrap` is best-effort — successful files register, failures are logged
 * and counted but don't roll back.
 *
 * Duplicate `day` across files: last-write-wins (load order is filesystem-
 * defined; authors should keep rotations in a single file to avoid surprises).
 */
public class LoginBonusContents(
    parsers: List<IContentParser> = ContentLoader.DEFAULT_PARSERS,
) : ReloadableContents, ILoginBonusRegistry {

    override val categoryName: String = "login-bonus"

    private val store: AtomicReference<Map<Int, LoginBonusSpec>> = AtomicReference(emptyMap())
    private val loader = ContentLoader(parsers, LoginBonusYamlConfig.serializer())

    override fun find(key: Int): LoginBonusSpec? = store.get()[key]

    override fun all(): Collection<LoginBonusSpec> = store.get().values

    /** Programmatic registration — used by tests and for runtime overrides. */
    public fun register(spec: LoginBonusSpec) {
        while (true) {
            val current = store.get()
            val next = current + (spec.day to spec)
            if (store.compareAndSet(current, next)) return
        }
    }

    /** Drop every registered entry (tests / hot-reload semantics). */
    public fun clear() {
        store.set(emptyMap())
    }

    /** Best-effort initial load at `onEnable`. */
    public fun bootstrap(dir: Path): ContentLoader.LoadReport {
        val incoming = LinkedHashMap<Int, LoginBonusSpec>()
        val report = loader.loadFrom(dir) { cfg ->
            for (dayDto in cfg.days) {
                val spec = dayDto.toDomain()
                incoming[spec.day] = spec
            }
        }
        while (true) {
            val current = store.get()
            val next = current + incoming
            if (store.compareAndSet(current, next)) break
        }
        return report
    }

    /** Atomic, all-or-nothing reload. */
    override fun reload(dir: Path): ContentLoader.LoadReport {
        val next = LinkedHashMap<Int, LoginBonusSpec>()
        val report = loader.loadFrom(dir) { cfg ->
            for (dayDto in cfg.days) {
                val spec = dayDto.toDomain()
                next[spec.day] = spec
            }
        }
        if (report.failed != 0) return report
        EmptyReloadGuard.check(categoryName, dir, next.size, store.get().size)?.let { return it }
        store.set(next.toMap())
        return report
    }
}
