package com.astarworks.astera.architecture

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Astera architecture enforcement (multi-plugin layout, ADR-0022).
 *
 * Encodes the dependency direction rules from ADR-0001 (Clean Architecture with
 * Minecraft as an edge), ADR-0002 (vendor-neutral MC adapter), ADR-0022 (multi-
 * plugin portable architecture).
 *
 * If you find yourself wanting to weaken one of these rules: stop, and instead
 * raise a new ADR explaining why the current architecture cannot accommodate
 * your use case. The whole point of these tests is to make architectural drift
 * a deliberate decision rather than an accident.
 */
class ArchitectureTest {

    /** ADR-0262: every runtime module uses Folia-family schedulers; no compatibility allowlist. */
    @Test
    fun `main sources must not use the legacy Bukkit scheduler or primary thread flag`() {
        val forbidden = Regex(
            """\b(?:getScheduler\s*\(|BukkitRunnable\b|BukkitScheduler\b|isPrimaryThread\b|runTask(?:Later|Timer|Asynchronously|LaterAsynchronously|TimerAsynchronously)?\s*\()|(?:\bserver|\.server|getServer\(\))\s*\.\s*scheduler\b""",
        )
        projectScope().files.filter { it.path.contains("/src/main/") }.assertFalse { file ->
            file.imports.any { it.name.startsWith("org.bukkit.scheduler.") } ||
                forbidden.containsMatchIn(sourceWithoutComments(file.text))
        }
    }

    @Test
    fun `Paper adapters must use asynchronous entity teleportation`() {
        projectScope().files.filter {
            it.path.contains("/src/main/") && (it.path.contains("/adapter/paper/") || it.path.contains("/platform/"))
        }.assertFalse { file ->
            Regex("""\.\s*teleport\s*\(""").containsMatchIn(sourceWithoutComments(file.text))
        }
    }

    @Test
    fun `action bar is sent only by the HUD compositor (ADR-0279)`() {
        // The action bar is shared with the HP status line; any other sender erases the HP line until the
        // next refresh. Everything posts through IActionBarChannel; PaperHudGateway composes and sends.
        val forbidden = Regex("""\bsendActionBar\s*\(|ClientboundSetActionBar\w*Packet|ChatMessageType\.ACTION_BAR|ChatType\.GAME_INFO""")
        projectScope().files
            .filter { it.path.contains("/src/main/") && !it.path.contains("/tools/") }
            .filterNot { it.path.endsWith("/adapter/paper/hud/PaperHudGateway.kt") }
            .assertFalse { file -> forbidden.containsMatchIn(sourceWithoutComments(file.text)) }
    }

    @Test
    fun `main sources must not create or unload worlds`() {
        val forbidden = Regex("""\b(?:WorldCreator|createWorld|unloadWorld)\b""")
        projectScope().files.filter { it.path.contains("/src/main/") }.assertFalse { file ->
            forbidden.containsMatchIn(sourceWithoutComments(file.text))
        }
    }

    private fun sourceWithoutComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/|//[^\n]*""", RegexOption.DOT_MATCHES_ALL), " ")

    private val bukkitFamily = listOf(
        "org.bukkit",
        "io.papermc",
        "org.spigotmc",
        "com.destroystokyo.paper",
        "com.mojang",
    )

    /**
     * Files whose body is plugin enable-time wiring (ADR-0123 / ADR-0131 scope):
     * the Paper entrypoint `platform/Astera*Plugin.kt` AND, since ADR-0174 moved
     * the object-graph construction out of it, the host-neutral
     * `platform/wiring/` functions the headless simulator also runs.
     */
    private fun isPluginWiringFile(file: KoFileDeclaration): Boolean {
        val path = file.path
        if (path.contains("/test/")) return false
        val isPluginEntrypoint = path.contains("/platform/") &&
            file.name.startsWith("Astera") &&
            file.name.endsWith("Plugin")
        val isHostNeutralWiring = path.contains("/platform/wiring/")
        return isPluginEntrypoint || isHostNeutralWiring
    }

    private fun hasBukkitFamilyImport(file: KoFileDeclaration): Boolean =
        file.imports.any { imp ->
            bukkitFamily.any { prefix -> imp.name.startsWith(prefix) }
        }

    // ─── Core plugin (mandatory base) ───────────────────────────────────────

    /**
     * Core's `domain/` is pure: only Kotlin stdlib + kotlinx.coroutines +
     * kotlinx.collections.immutable. No frameworks, no I/O, no Bukkit, no
     * persistence driver, no logger.
     */
    @Test
    fun `core domain must not depend on any platform or framework`() {
        coreScope()
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.core.domain") == true }
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        // org.joml.* deliberately NOT forbidden — Wave 5h-fix:
                        // JOML is a pure-math library (Vector3f / Quaternionf)
                        // with no Minecraft coupling; treat like java.util.UUID.
                        imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.slf4j") ||
                            imp.name.startsWith("org.koin") ||
                            imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("java.io") ||
                            imp.name.startsWith("java.nio") ||
                            imp.name.startsWith("java.net")
                    }
            }
    }

    /**
     * Core's `application/` defines ports + use cases in domain language. May
     * use SLF4J + serialization parsers but no Bukkit and no persistence
     * driver.
     */
    @Test
    fun `core application must not import Bukkit family or persistence drivers`() {
        coreScope()
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.core.application") == true }
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.joml") ||
                            imp.name.startsWith("java.net.http")
                    }
            }
    }

    /**
     * Core's `adapter/api/` is the vendor-neutral Minecraft abstraction
     * (IMcServer / IMcPlayer / IMcEvent etc.). Per ADR-0002 it MUST NOT
     * leak Bukkit imports.
     */
    @Test
    fun `core adapter-api MUST stay vendor-neutral (no Bukkit imports)`() {
        coreScope()
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.core.adapter.api") == true }
            .assertFalse(additionalMessage = "ADR-0002 violation: vendor-neutral layer leaked Bukkit imports.") { file ->
                hasBukkitFamilyImport(file)
            }
    }

    /**
     * silly-knuth §5: `core/domain/event/` carries pure sealed event records.
     * Publishing happens in `application/`; subscribing in `application/eventbus/`.
     * The event package itself must not know about IBroadcaster or IDomainEventBus.
     */
    @Test
    fun `core domain event package must not import IBroadcaster or IDomainEventBus`() {
        coreScope()
            .files
            .filter { it.packagee?.name == "com.astarworks.astera.core.domain.event" }
            .assertFalse(
                additionalMessage = "silly-knuth §5: domain/event must contain only sealed events; subscription belongs in application/eventbus/.",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.endsWith(".IBroadcaster") || imp.name.endsWith(".IDomainEventBus")
                }
            }
    }

    /**
     * Core MUST NOT depend on any sibling feature plugin (ADR-0022). Core is
     * the base everyone depends on; reverse deps would create cycles.
     */
    @Test
    fun `core must not import any feature plugin`() {
        coreScope()
            .files
            .assertFalse(
                additionalMessage = "ADR-0022 violation: Core cannot depend on feature plugins (creates dependency cycle).",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.startsWith("com.astarworks.astera.items") ||
                        imp.name.startsWith("com.astarworks.astera.combat") ||
                        imp.name.startsWith("com.astarworks.astera.match") ||
                        imp.name.startsWith("com.astarworks.astera.stats") ||
                        imp.name.startsWith("com.astarworks.astera.economy") ||
                        imp.name.startsWith("com.astarworks.astera.persistence")
                }
            }
    }

    @Test
    fun `host neutral kernel modules must not import Bukkit family`() {
        for (module in listOf("kernel/domain", "kernel/runtime", "kernel/mc-api")) {
            moduleScope(module).files.assertFalse { hasBukkitFamilyImport(it) }
        }
    }

    @Test
    fun `chat domain and application preserve clean boundaries`() {
        moduleScope("plugin/chat").files.filter { it.packagee?.name?.contains(".domain") == true }
            .assertFalse { file ->
                hasBukkitFamilyImport(file) || file.imports.any { imp ->
                    listOf("java.io", "java.nio", "java.net", "java.sql", "javax.sql", "org.slf4j", "org.koin",
                        "kotlinx.serialization", "org.jetbrains.exposed", "io.lettuce").any { imp.name.startsWith(it) }
                }
            }
        moduleScope("plugin/chat").files.filter { it.packagee?.name?.contains(".application") == true }
            .assertFalse { file ->
                hasBukkitFamilyImport(file) || file.imports.any { imp ->
                    listOf("java.sql", "javax.sql", "java.net.http", "org.postgresql", "org.jetbrains.exposed", "io.lettuce")
                        .any { imp.name.startsWith(it) }
                }
            }
    }

    @Test
    fun `chat references Core only and existing siblings do not reference Chat`() {
        val siblings = listOf("items", "combat", "match", "stats", "economy", "persistence")
        moduleScope("plugin/chat").files.assertFalse { file ->
            file.imports.any { imp -> siblings.any { imp.name.startsWith("com.astarworks.astera.$it.") } }
        }
        (coreScope().files + siblings.filterNot { it == "persistence" }.flatMap { moduleScope("plugin/$it").files })
            .assertFalse { file -> file.imports.any { it.name.startsWith("com.astarworks.astera.chat.") } }
    }

    // ─── Items plugin (formerly Weapons) ─────────────────────────────────────────────────────

    /**
     * Weapons domain is pure. Same rules as Core's domain.
     */
    @Test
    fun `items domain must be pure`() {
        moduleScope("plugin/items")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.items.domain") == true }
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("org.joml") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.koin") ||
                            imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("java.io") ||
                            imp.name.startsWith("java.nio") ||
                            imp.name.startsWith("java.net")
                    }
            }
    }

    /**
     * Weapons application must not import Bukkit family directly. Adapters do
     * the Bukkit translation.
     */
    @Test
    fun `items application must not import Bukkit family`() {
        moduleScope("plugin/items")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.items.application") == true }
            .assertFalse { file -> hasBukkitFamilyImport(file) }
    }

    /**
     * Weapons MUST NOT depend on any sibling feature plugin (ADR-0022).
     * Weapons is the minimum the rest of the tree composes on top of, so it
     * cannot reach sideways. The rest of Astera composes on top of Weapons,
     * not the other way around.
     */
    @Test
    fun `items must not import combat or match or stats or economy or persistence`() {
        moduleScope("plugin/items")
            .files
            .assertFalse(
                additionalMessage = "ADR-0022 violation: Weapons cannot reach sibling features. Core is the only allowed cross-plugin reference.",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.startsWith("com.astarworks.astera.combat") ||
                        imp.name.startsWith("com.astarworks.astera.match") ||
                        imp.name.startsWith("com.astarworks.astera.stats") ||
                        imp.name.startsWith("com.astarworks.astera.economy") ||
                        imp.name.startsWith("com.astarworks.astera.persistence")
                }
            }
    }

    // ─── Combat plugin ──────────────────────────────────────────────────────

    @Test
    fun `combat domain must be pure (Weapons types allowed for DamageSource Weapon)`() {
        moduleScope("plugin/combat")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.combat.domain") == true }
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("org.joml") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.koin") ||
                            imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("java.io") ||
                            imp.name.startsWith("java.nio") ||
                            imp.name.startsWith("java.net")
                    }
            }
    }

    @Test
    fun `combat application must not import Bukkit family`() {
        moduleScope("plugin/combat")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.combat.application") == true }
            .assertFalse { file -> hasBukkitFamilyImport(file) }
    }

    /**
     * Combat may compile-time-reference Weapons (DamageSource.Weapon's
     * WeaponId field, per the `compileOnly(project(":plugin:weapons"))` dep
     * + plugin.yml `softdepend: [Astera-Weapons]`). It MUST NOT reach into
     * Match / Stats / Economy / Persistence.
     */
    @Test
    fun `combat must not import match or stats or economy or persistence`() {
        moduleScope("plugin/combat")
            .files
            .assertFalse(
                additionalMessage = "ADR-0022 violation: Combat may use Core + (soft-dep) Weapons only.",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.startsWith("com.astarworks.astera.match") ||
                        imp.name.startsWith("com.astarworks.astera.stats") ||
                        imp.name.startsWith("com.astarworks.astera.economy") ||
                        imp.name.startsWith("com.astarworks.astera.persistence")
                }
            }
    }

    // ─── Match plugin ───────────────────────────────────────────────────────

    @Test
    fun `match domain must be pure`() {
        moduleScope("plugin/match")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.match.domain") == true }
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("org.joml") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.koin") ||
                            imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("java.io") ||
                            imp.name.startsWith("java.nio") ||
                            imp.name.startsWith("java.net")
                    }
            }
    }

    @Test
    fun `match application must not import Bukkit family`() {
        moduleScope("plugin/match")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.match.application") == true }
            .assertFalse { file -> hasBukkitFamilyImport(file) }
    }

    /**
     * Wave 5gamemode (ADR-0106): the `match.domain.gamemode` package is the
     * framework-side IGameMode contract. It must stay pure (no Bukkit, no
     * persistence, no I/O) **and** must not import the legacy mode-config
     * package `match.domain.mode.*` *except* for `GameModeId` (the one type
     * the framework reuses across both registries — see IGameMode kdoc).
     *
     * The legacy direction (`match.application.usecase.mode.*` reading from
     * the framework) is allowed and expected once Phase 3 lands.
     */
    @Test
    fun `match domain gamemode must be pure and avoid legacy mode coupling`() {
        moduleScope("plugin/match")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.match.domain.gamemode") == true }
            .assertFalse(
                additionalMessage = "ADR-0106 violation: match.domain.gamemode must stay pure and " +
                    "must not import legacy match.domain.mode.* (except GameModeId).",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("org.joml") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.koin") ||
                            imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("java.io") ||
                            imp.name.startsWith("java.nio") ||
                            imp.name.startsWith("java.net") ||
                            (
                                imp.name.startsWith("com.astarworks.astera.match.domain.mode") &&
                                    !imp.name.startsWith("com.astarworks.astera.match.domain.mode.GameModeId")
                                )
                    }
            }
    }

    /**
     * Match may soft-depend on Combat (e.g. MatchEnded subscribers in Combat).
     * It MUST NOT reach into Weapons / Stats / Economy / Persistence.
     */
    @Test
    fun `match must not import weapons or stats or economy or persistence`() {
        moduleScope("plugin/match")
            .files
            .assertFalse(
                additionalMessage = "ADR-0022 violation: Match may use Core + (soft-dep) Combat only.",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.startsWith("com.astarworks.astera.items") ||
                        imp.name.startsWith("com.astarworks.astera.stats") ||
                        imp.name.startsWith("com.astarworks.astera.economy") ||
                        imp.name.startsWith("com.astarworks.astera.persistence")
                }
            }
    }

    // ─── Stats plugin ───────────────────────────────────────────────────────

    @Test
    fun `stats domain must be pure`() {
        moduleScope("plugin/stats")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.stats.domain") == true }
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("org.joml") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.koin") ||
                            imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("java.io") ||
                            imp.name.startsWith("java.nio") ||
                            imp.name.startsWith("java.net")
                    }
            }
    }

    @Test
    fun `stats application must not import Bukkit family`() {
        moduleScope("plugin/stats")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.stats.application") == true }
            .assertFalse { file -> hasBukkitFamilyImport(file) }
    }

    /**
     * Stats subscribes to Weapons / Combat / Match events via the central
     * IDomainEventBus, so soft-depends on all three. MUST NOT reach Economy /
     * Persistence.
     */
    @Test
    fun `stats must not import economy or persistence`() {
        moduleScope("plugin/stats")
            .files
            .assertFalse(
                additionalMessage = "ADR-0022 violation: Stats may use Core + (soft-dep) Weapons/Combat/Match only.",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.startsWith("com.astarworks.astera.economy") ||
                        imp.name.startsWith("com.astarworks.astera.persistence")
                }
            }
    }

    // ─── Economy plugin ─────────────────────────────────────────────────────

    @Test
    fun `economy domain must be pure`() {
        moduleScope("plugin/economy")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.economy.domain") == true }
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("org.joml") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.koin") ||
                            imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("java.io") ||
                            imp.name.startsWith("java.nio") ||
                            imp.name.startsWith("java.net")
                    }
            }
    }

    @Test
    fun `economy application must not import Bukkit family`() {
        moduleScope("plugin/economy")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.economy.application") == true }
            .assertFalse { file -> hasBukkitFamilyImport(file) }
    }

    /**
     * Economy soft-depends on Stats (mission rewards) and Match (match-end
     * payouts). MUST NOT reach Weapons / Combat / Persistence directly.
     */
    @Test
    fun `economy must not import weapons or combat or persistence`() {
        moduleScope("plugin/economy")
            .files
            .assertFalse(
                additionalMessage = "ADR-0022 violation: Economy may use Core + (soft-dep) Stats/Match only.",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.startsWith("com.astarworks.astera.items") ||
                        imp.name.startsWith("com.astarworks.astera.combat") ||
                        imp.name.startsWith("com.astarworks.astera.persistence")
                }
            }
    }

    // ─── Persistence plugin ─────────────────────────────────────────────────

    /**
     * Persistence is the lone plugin allowed to soft-depend on every other
     * feature: it provides Postgres impls of any plugin's repository port,
     * priority-registered to override in-memory defaults. So no "must not
     * import sibling" rule here. It still must not import Minecraft directly
     * (DB-only).
     */
    @Test
    fun `persistence must not import Minecraft family`() {
        moduleScope("plugin/persistence")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.persistence.platform") != true }
            .assertFalse(additionalMessage = "Persistence is a DB-side plugin; Minecraft access (other than the JavaPlugin entrypoint) belongs in the platform layer or stays off-limits.") { file ->
                hasBukkitFamilyImport(file)
            }
    }

    // ─── R0.d — Additional preventive rules ─────────────────────────────────

    /**
     * Application layers across all plugins MUST NOT import raw JVM exception
     * types (Throwable / Exception / Error / RuntimeException) as part of
     * their public API surface. Use a sealed error hierarchy and return
     * Result<T, E> instead (ADR principles §12.2).
     *
     * This is an import-level check: if Throwable is imported inside an
     * application package it leaks into the contract. Catching inside a private
     * helper body is fine — the helper should not be in application/ if it
     * needs raw exceptions.
     */
    @Test
    fun `application layers must not import raw JVM throwable types`() {
        val throwableImports = setOf(
            "kotlin.Throwable",
            "java.lang.Throwable",
            "java.lang.Exception",
            "java.lang.Error",
            "java.lang.RuntimeException",
        )
        val applicationPackagePrefixes = listOf(
            "com.astarworks.astera.chat.application",
            "com.astarworks.astera.core.application",
            "com.astarworks.astera.items.application",
            "com.astarworks.astera.combat.application",
            "com.astarworks.astera.match.application",
            "com.astarworks.astera.stats.application",
            "com.astarworks.astera.economy.application",
            "com.astarworks.astera.persistence.application",
        )
        projectScope()
            .files
            .filter { file ->
                applicationPackagePrefixes.any { prefix ->
                    file.packagee?.name?.startsWith(prefix) == true
                }
            }
            .assertFalse(
                additionalMessage = "ADR principles §12.2: application/ must not expose raw JVM exceptions. " +
                    "Use a sealed error hierarchy and return Result<T, E> instead of Throwable.",
            ) { file ->
                file.imports.any { imp -> imp.name in throwableImports }
            }
    }

    /**
     * Kotlin's `TODO("...")` throws `NotImplementedError` at runtime. A stubbed
     * port method (`PaperPlayer.sendTitle`) aborted the lobby -> game transfer
     * on the real stack. Production code must not call it; comments such as
     * `// TODO(wave-x)` are fine.
     */
    @Test
    fun `production code must not call the throwing TODO stub`() {
        val call = Regex("""(?<![\w.])TODO\s*\(""")
        val offenders = projectScope()
            .files
            .filter { file ->
                val path = file.path.replace('\\', '/')
                (path.contains("/plugin/") || path.contains("/kernel/") || path.contains("/core/")) &&
                    path.contains("/src/main/")
            }
            .flatMap { file ->
                file.text.lines().withIndex()
                    .filter { (_, line) ->
                        val t = line.trim()
                        !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") &&
                            call.containsMatchIn(line.substringBefore("//"))
                    }
                    .map { (i, _) -> "${file.path}:${i + 1}" }
            }
        assertThat(offenders).`as`("TODO() calls throw NotImplementedError at runtime").isEmpty()
    }

    /**
     * Combat domain MUST NOT import Bukkit's projectile API. Projectile physics
     * is an edge concern; the domain models projectile as a value (e.g.
     * ProjectileSpec) and delegates collision detection to the Paper adapter.
     */
    @Test
    fun `combat domain must not import bukkit projectile api`() {
        moduleScope("plugin/combat")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.combat.domain") == true }
            .assertFalse(
                additionalMessage = "Combat domain must stay vendor-neutral. " +
                    "Bukkit projectile types belong in the Paper adapter layer, not in domain/.",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.startsWith("org.bukkit.entity.Projectile") ||
                        imp.name == "org.bukkit.projectiles.ProjectileSource" ||
                        imp.name.startsWith("org.bukkit.entity.AbstractArrow") ||
                        imp.name.startsWith("org.bukkit.entity.Arrow") ||
                        imp.name.startsWith("org.bukkit.entity.Fireball")
                }
            }
    }

    /**
     * Wave 5combat-depth (ADR-0105): the three combat-depth domain packages
     * (`combo`, `focus`, `targetlock`) carry pure state — combo timeline,
     * focus-fire window, target-lock acquisition. They MUST stay free of
     * Bukkit, scheduling, IO, and persistence. Any of those concerns belong
     * in the application-side `InMemory*Tracker` / `InMemory*Controller`
     * services or in a Wave 6 Paper adapter.
     *
     * The general "combat domain must be pure" rule above catches Bukkit
     * + serialization leaks. This dedicated rule additionally forbids
     * `IDomainEventBus` / `IBroadcaster` imports so the domain types stay
     * un-orchestrated value records.
     */
    @Test
    fun `combat depth domain must not import event bus or broadcaster`() {
        moduleScope("plugin/combat")
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                pkg.startsWith("com.astarworks.astera.combat.domain.combo") ||
                    pkg.startsWith("com.astarworks.astera.combat.domain.focus") ||
                    pkg.startsWith("com.astarworks.astera.combat.domain.targetlock")
            }
            .assertFalse(
                additionalMessage = "ADR-0105: combat-depth domain packages (combo/, focus/, targetlock/) " +
                    "must stay pure. Publishing belongs in application-side trackers " +
                    "(InMemoryComboTracker / InMemoryFocusFireTracker / InMemoryTargetLockController). " +
                    "Subscribing belongs in application/eventbus/listeners/.",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.endsWith(".IDomainEventBus") ||
                        imp.name.endsWith(".IBroadcaster") ||
                        imp.name.startsWith("org.slf4j") ||
                        imp.name.startsWith("kotlinx.serialization")
                }
            }
    }

    /**
     * GuiSlot sealed variants must not contain lambda-typed fields (silly-knuth §5).
     *
     * GUI slot variants are pure data: they describe *what* to show, never
     * *how* to handle the click. Click behaviour is expressed via [GuiIntent]
     * value objects. A lambda field would bind a mutable closure into what
     * should be a serialisable, equality-comparable value object.
     *
     * Heuristic: any property whose declared type text contains "->" or begins
     * with "Function" is a lambda / SAM.
     */
    @Test
    fun `gui slot variants must not contain lambda fields`() {
        coreScope()
            .files
            .filter { it.packagee?.name == "com.astarworks.astera.core.domain.gui" }
            .flatMap { it.classes() }
            .flatMap { it.properties() }
            .assertFalse(
                additionalMessage = "silly-knuth §5: GuiSlot variants are pure value objects. " +
                    "Click behaviour belongs in GuiIntent, not in a lambda field on the slot. " +
                    "See GuiSlot.kt for the design rationale.",
            ) { prop ->
                val typeName = prop.type?.name ?: ""
                typeName.contains("->") ||
                    typeName.startsWith("Function") ||
                    typeName.startsWith("kotlin.Function") ||
                    typeName.startsWith("(")
            }
    }

    // ─── Wave 5e — ADR-0058 §5 attribute container facade rule ──────────────

    /**
     * Wave 5e (ADR-0058 §5). `Wallet` / `PlayerStats` are
     * facades over [com.astarworks.astera.core.domain.attribute.IAttributeContainer].
     * They MUST NOT own parallel state — every numeric field must read
     * through the container. The only allowed stored properties are the
     * two identity-bearing fields `playerId` and `container`.
     *
     * Heuristic: any property declared on these classes that is a
     * stored `val` (no custom getter) and whose name is not in the
     * allow-list is parallel state.
     */
    @Test
    fun `ADR-0058 player resource facades must not own parallel state`() {
        val facadeClasses = setOf(
            "com.astarworks.astera.core.domain.economy.Wallet",
            "com.astarworks.astera.core.domain.stats.PlayerStats",
        )
        val allowedStored = setOf("playerId", "container")
        projectScope()
            .classes()
            .filter { it.fullyQualifiedName in facadeClasses }
            .flatMap { it.properties() }
            .assertFalse(
                additionalMessage = "ADR-0058 §5: Wallet/PlayerStats must back to IAttributeContainer. " +
                    "Stored fields other than playerId / container are parallel state — convert to " +
                    "`val foo: Long get() = container.effectiveNumber(Attributes.FOO).toLong()`.",
            ) { prop ->
                // Stored val (no custom getter) + not in allow-list = parallel state.
                prop.hasValModifier &&
                    prop.name !in allowedStored &&
                    !prop.hasGetter
            }
    }

    // ─── R0.e — i18n key parity ──────────────────────────────────────────────

    /**
     * All keys present in `content/languages/en.yaml` must also be present in
     * `content/languages/ja.yaml`, and vice-versa.
     *
     * This catches translation gaps the moment a new MessageKey is added: CI
     * refuses to go green until both language files are updated.
     */
    @Test
    fun `i18n keys must match across en and ja language files`() {
        // tools/architecture-test/ → repo root is 2 levels up.
        val repoRoot = File(".").canonicalFile.let { cwd ->
            // When run from the module dir the cwd is tools/architecture-test.
            // When run from repo root the file resolves directly.
            if (File(cwd, "content/languages").exists()) cwd
            else cwd.parentFile?.parentFile ?: cwd
        }
        val enFile = File(repoRoot, "content/languages/en.yaml")
        val jaFile = File(repoRoot, "content/languages/ja.yaml")
        require(enFile.exists()) { "en.yaml not found at ${enFile.canonicalPath}" }
        require(jaFile.exists()) { "ja.yaml not found at ${jaFile.canonicalPath}" }

        val enKeys = collectDotKeys(Yaml.default.parseToYamlNode(enFile.readText()))
        val jaKeys = collectDotKeys(Yaml.default.parseToYamlNode(jaFile.readText()))

        val enOnly = enKeys - jaKeys
        val jaOnly = jaKeys - enKeys

        assertThat(enOnly)
            .withFailMessage { "Keys present in en.yaml but missing from ja.yaml: $enOnly" }
            .isEmpty()
        assertThat(jaOnly)
            .withFailMessage { "Keys present in ja.yaml but missing from en.yaml: $jaOnly" }
            .isEmpty()
    }

    // ─── Shared test fixtures ───────────────────────────────────────────────

    /**
     * test-fixtures is a Core-only fake-adapter module. It depends on Core
     * (port definitions for shared services) but MUST NOT import Minecraft or
     * any feature plugin — feature-specific fakes live inside each feature
     * plugin's own test source set.
     */
    @Test
    fun `test-fixtures must not import Minecraft or any feature plugin`() {
        moduleScope("plugin/test-fixtures")
            .files
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("com.astarworks.astera.items") ||
                            imp.name.startsWith("com.astarworks.astera.combat") ||
                            imp.name.startsWith("com.astarworks.astera.match") ||
                            imp.name.startsWith("com.astarworks.astera.stats") ||
                            imp.name.startsWith("com.astarworks.astera.economy") ||
                            imp.name.startsWith("com.astarworks.astera.persistence")
                    }
            }
    }

    // ─── Wave 4 R3: Declarative GUI adapter boundary (ADR-0021) ────────────

    /**
     * Feature plugins MUST go through the [IGuiAdapter] / [IGuiIntentRouter]
     * / [IGuiRegistry] ports rather than importing Core's Paper-side GUI
     * implementation directly. Per ADR-0021 the Paper adapter is an
     * implementation detail that a future web-admin / headless renderer
     * needs to be able to swap.
     *
     * Paired with the existing `core adapter-api MUST stay vendor-neutral`
     * rule (which already covers `core.adapter.api.gui` via its package
     * prefix filter), this completes the boundary: feature plugins see
     * vendor-neutral ports + adapter-api types; nothing else.
     */
    @Test
    fun `feature plugins must not import core adapter paper gui`() {
        val featureModules = listOf(
            "plugin/chat",
            "plugin/items",
            "plugin/combat",
            "plugin/match",
            "plugin/stats",
            "plugin/economy",
            "plugin/persistence",
        )
        for (module in featureModules) {
            moduleScope(module)
                .files
                .assertFalse(
                    additionalMessage = "$module must not import com.astarworks.astera.core.adapter.paper.gui; " +
                        "depend on IGuiAdapter / IGuiIntentRouter / IGuiRegistry ports instead (ADR-0021).",
                ) { file ->
                    file.imports.any { imp ->
                        imp.name.startsWith("com.astarworks.astera.core.adapter.paper.gui")
                    }
                }
        }
    }

    // ─── Wave 5g — Pre-Paper-sweep port commitments (ADR-0072) ──────────────

    // Wave 5g (ADR-0072): these 4 outbound ports land their signature commitment now;
    // Paper impls land in Wave 6a (PaperStatusEffectAdapter merge, etc.) and Wave 6b
    // (PaperStructurePaste over FAWE). Removing an entry from allowedMissingPaperImpl
    // is a positive event — it means the Paper impl shipped.
    /**
     * Wave 6 Paper-impl allow-list (ADR-0072).
     *
     * Documents the four outbound ports whose Paper implementations are
     * intentionally deferred to Wave 6a / 6b. The test pins the allow-set
     * shape so future audits surface "this port still has no Paper impl"
     * mechanically:
     *
     *  (allow-set is empty after Wave 6a-N3 — every Wave-6 deferred port
     *  has a production impl, see history note below.)
     *
     * (Wave 6a-7 / ADR-0152 §B shipped `PaperPlayerMovementGateway` +
     * `PaperPlayerVisionGateway`; Wave 6a-N1 / ADR-0154 shipped
     * `MatchTimerService` in `application/service/` — the contract is pure
     * Kotlin so the impl doesn't live in `adapter/paper/`. Wave 6a-N3 /
     * ADR-0159 shipped `InMemoryStructurePaste` in `application/service/`
     * as Phase 1 stub; the Wave 6b+ FAWE adapter `PaperStructurePaste`
     * will overlay at priority 100 when shipped.)
     *
     * The test asserts:
     *  1. Each FQN in the allow-set actually exists in the codebase (the
     *     port skeleton landed in Wave 5g; deleting a port without removing
     *     it from the allow-set is a drift bug).
     *  2. No class under `*.adapter.paper.*` already implements one of these
     *     ports — if a Paper impl ships, the corresponding allow-set entry
     *     must be removed in the same commit. (Discoverability rule:
     *     surface "we promised a Paper impl, here it is" as a deliberate
     *     allow-list shrink.)
     */
    @Test
    fun `Wave 6 Paper impl allow-list (ADR-0072)`() {
        val allowedMissingPaperImpl: Set<String> = setOf(
            // Wave 6a-7 (ADR-0152 §B) closed IPlayerMovementGateway +
            // IPlayerVisionGateway.
            // Wave 6a-N1 (ADR-0154) closed IMatchTimerService — impl is in
            // plugin/match/.../application/service/MatchTimerService.kt.
            // IStructurePaste (ADR-0159) was removed in ADR-0246: nothing consumed it.
            // Allow-set intentionally empty; restore an entry only when
            // a NEW Wave-6 deferred port is introduced.
        )

        // (1) Every allow-set entry must resolve to a real port interface.
        // If a Wave 6 author deletes a port without trimming the allow-set,
        // this fails loudly instead of silently rotting.
        val knownInterfaces = projectScope()
            .interfaces()
            .map { it.fullyQualifiedName }
            .toSet()
        val missingFromCodebase = allowedMissingPaperImpl - knownInterfaces
        assertThat(missingFromCodebase)
            .withFailMessage {
                "ADR-0072 drift: allow-set names ports that no longer exist " +
                    "in the codebase: $missingFromCodebase. Either the port was " +
                    "renamed (update the allow-set) or removed (drop the entry)."
            }
            .isEmpty()

        // (2) No class under any *.adapter.paper.* package may already
        // implement one of these ports — if a Paper impl ships, the
        // corresponding allow-set entry MUST be removed in the same commit
        // (positive shrink). The simple-name match here is intentional: it
        // avoids needing a full type-resolution graph and matches the
        // codebase's `IShopStockRepository` / `PaperXxxAdapter` convention.
        val allowedSimpleNames = allowedMissingPaperImpl.map { it.substringAfterLast('.') }.toSet()
        val paperImplsForAllowedPorts = projectScope()
            .classes()
            .filter { cls ->
                val pkg = cls.packagee?.name ?: return@filter false
                pkg.contains(".adapter.paper")
            }
            .filter { cls ->
                cls.parents().any { parent -> parent.name in allowedSimpleNames }
            }
            .map { it.fullyQualifiedName }
            .toList()

        assertThat(paperImplsForAllowedPorts)
            .withFailMessage {
                "ADR-0072 positive-shrink rule: $paperImplsForAllowedPorts " +
                    "implements a port that is still in the Wave 6 deferred " +
                    "allow-set. Remove the corresponding entry from " +
                    "allowedMissingPaperImpl in this test."
            }
            .isEmpty()
    }

    // ─── Wave 5g — IShopStockRepository priority-100 Postgres overlay ───────

    /**
     * Wave 5g (ADR-0072 sibling commitment): the Postgres impl of the new
     * Wave 5g `IShopStockRepository` port MUST live at the agreed FQN
     * `com.astarworks.astera.persistence.repository.PostgresShopStockRepository`
     * and MUST implement [IShopStockRepository]. Runtime priority-100
     * registration is wired in `AsteraPersistencePlugin.kt` (verified by
     * reviewers + the 19-entry → 20-entry `registrations` list count) — this
     * test guarantees the impl class is **named and located** correctly so
     * priority drift is caught at the structural level, not at a flaky
     * registration test.
     *
     * Reads as: "iff `PostgresShopStockRepository` exists, it must implement
     * `IShopStockRepository`." If the impl class is absent entirely the test
     * still passes (Wave 5g may stage the port skeleton commit before the
     * Postgres impl arrives in the same wave).
     */
    @Test
    fun `IShopStockRepository requires priority 100 Postgres overlay (Wave 5g)`() {
        val expectedImplFqn =
            "com.astarworks.astera.persistence.repository.PostgresShopStockRepository"
        val expectedPortSimpleName = "IShopStockRepository"

        val matches = projectScope()
            .classes()
            .filter { it.fullyQualifiedName == expectedImplFqn }

        // Skeleton-staging tolerance: if the impl class hasn't landed yet,
        // the test is a no-op. The runtime registry will fall back to the
        // in-memory impl. Once the impl class exists, the parent-check
        // below enforces the wiring contract.
        requireSubjects(matches, "PostgresShopStockRepository class")

        val nonImplementors = matches.filter { cls ->
            cls.parents().none { parent -> parent.name == expectedPortSimpleName }
        }

        assertThat(nonImplementors.map { it.fullyQualifiedName })
            .withFailMessage {
                "Wave 5g wiring contract: $expectedImplFqn must implement " +
                    "$expectedPortSimpleName so the priority-100 overlay " +
                    "registered by AsteraPersistencePlugin actually beats " +
                    "InMemoryShopStockRepository (priority 0). If you " +
                    "renamed the impl, update both this test and " +
                    "AsteraPersistencePlugin.kt in the same commit."
            }
            .isEmpty()
    }

    // ─── Wave 5v — Match recording capture (ADR-0088) ───────────────────────

    /**
     * Wave 5v: every [ReplayEvent] variant is a flat `data class` over
     * primitives + value classes. Nesting another sealed (e.g. inlining
     * combat's `DamageSource`) here would make the JSONB encoder schema
     * non-deterministic and bloat the 4×1h≈5MB capture budget.
     *
     * Heuristic: walk every property declared on every direct subtype of
     * `ReplayEvent`; the property's declared type-text must reduce to one
     * of an allow-list of primitive / value-class / enum / `MessageKey` /
     * `MatchPhase` simple names. Maps, lists, sealed nested types fail.
     */
    @Test
    fun `Wave 5v ReplayEvent variants must contain only primitive or value-class types`() {
        val allowed = setOf(
            // Primitives
            "Long", "Int", "Short", "Byte", "Float", "Double", "Boolean", "String",
            // Value classes + identity types used by replay
            "PlayerId", "MatchId", "ItemId", "SkillId", "TeamId", "MessageKey",
            // Domain enums materialised inside ReplayEvent
            "MatchPhase", "DamageSourceKind",
        )

        val variantClasses = projectScope()
            .classes()
            .filter { cls ->
                cls.parents().any { parent ->
                    parent.name == "ReplayEvent"
                }
            }

        // Skeleton tolerance: if the sealed has no variants yet, no-op. Once
        // a variant exists the rule fires for every declared property.
        requireSubjects(variantClasses, "ReplayEvent variants")

        val offenders = variantClasses.flatMap { cls ->
            cls.properties().filter { prop ->
                val typeName = prop.type?.name ?: return@filter false
                // Strip nullable marker; drop generic arms entirely (they
                // indicate a List / Map / Set wrapper, which is forbidden).
                if (typeName.contains("<") || typeName.contains("[")) return@filter true
                val bare = typeName.removeSuffix("?")
                bare !in allowed
            }.map { prop -> "${cls.fullyQualifiedName}.${prop.name}: ${prop.type?.name}" }
        }

        assertThat(offenders)
            .withFailMessage {
                "Wave 5v / ADR-0088: ReplayEvent variants must contain only primitive + " +
                    "value-class fields (so the JSONB encoder shape stays deterministic and " +
                    "the 4×1h≈5MB capture budget holds). Offending properties: ${offenders.joinToString()}"
            }
            .isEmpty()
    }

    /**
     * Wave 5v: [IMatchRecorder] is a domain-language inbound port; per
     * ADR-0001 / ADR-0002 it MUST NOT import Bukkit/Paper. The Paper
     * adapter (Wave 6a `PaperRecorderStateProvider`) reads `Bukkit.getOnline
     * Players()` and friends, but the **port** stays vendor-neutral so the
     * Fake-MC tests and the future Folia impl can both wire through it.
     */
    @Test
    fun `Wave 5v IMatchRecorder must not import Bukkit Paper`() {
        projectScope()
            .files
            .filter { file ->
                file.name.removeSuffix(".kt") == "IMatchRecorder" &&
                    file.packagee?.name == "com.astarworks.astera.match.application.port.inbound"
            }
            .assertFalse(
                additionalMessage = "Wave 5v / ADR-0088: IMatchRecorder is the vendor-neutral " +
                    "inbound port for replay capture; Paper imports belong in the Wave 6a " +
                    "PaperRecorderStateProvider adapter, not on the port itself.",
            ) { file ->
                hasBukkitFamilyImport(file)
            }
    }

    /**
     * Wave 5m / ADR-0088: Combat AOE port implementations (Explosion /
     * AreaEffect / Lightning / FireSpread) MUST NOT import the Bukkit
     * family in their **application-layer** location.
     *
     * The port interfaces live under
     * `com.astarworks.astera.combat.application.port.outbound.aoe.*` and
     * their default in-memory impls live under
     * `com.astarworks.astera.combat.application.service.aoe.*`. Both
     * packages are part of the application layer (per ADR-0001 §4) and
     * therefore stay vendor-neutral: Wave 6a Paper impls live in
     * `combat.adapter.paper.*` instead and that location can freely
     * import Bukkit.
     *
     * This rule duplicates "combat application must not import Bukkit
     * family" intentionally — keeping a dedicated AOE-scoped assertion
     * makes the Wave 5m boundary easy to find in CI failures and gives
     * future ports added under `.aoe.*` an explicit hook.
     */
    @Test
    fun `IExplosionGateway and IAreaEffectGateway implementations must not import Bukkit Paper (ADR-0088)`() {
        moduleScope("plugin/combat")
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                pkg.startsWith("com.astarworks.astera.combat.application.port.outbound.aoe") ||
                    pkg.startsWith("com.astarworks.astera.combat.application.service.aoe") ||
                    pkg.startsWith("com.astarworks.astera.combat.domain.aoe") ||
                    pkg.startsWith("com.astarworks.astera.combat.domain.event.aoe")
            }
            .assertFalse(
                additionalMessage = "ADR-0088 violation: Wave 5m Combat AOE port impls + " +
                    "their domain types must stay vendor-neutral. Move any Bukkit-touching " +
                    "code to combat.adapter.paper.*.",
            ) { file -> hasBukkitFamilyImport(file) }
    }

    // ─── Wave 5pve — IMobAdapter uniqueness (ADR-0085) ───

    /**
     * ADR-0085 (PvE mob foundation, two-tier `IMobAdapter`): every concrete
     * `IMobAdapter` implementation MUST return a unique [backendId] value.
     * The runtime `MobSpawnerRouter` selects adapters by registration order
     * and routes despawn calls back via `EntityHandle.backendId` — colliding
     * backend ids would silently route handles to the wrong adapter.
     *
     * Same convention as ADR-0082 (`INpcAdapter`): every implementation must
     * declare a `public val BACKEND_ID: MobBackendId = MobBackendId.X` in a
     * companion object. The test scans the source for those declarations
     * and asserts no two adapters reference the same constant.
     */
    @Test
    fun `IMobAdapter implementations must declare backendId() uniquely (ADR-0085)`() {
        val portSimpleName = "IMobAdapter"

        val adapters = projectScope()
            .classes()
            .filter { cls ->
                cls.parents().any { parent -> parent.name == portSimpleName }
            }

        assertThat(adapters.map { it.fullyQualifiedName })
            .withFailMessage {
                "ADR-0085 expects at least one IMobAdapter implementation. " +
                    "Found: ${adapters.map { it.fullyQualifiedName }}"
            }
            .isNotEmpty

        val backendIdTokens: Map<String, String> = adapters.associate { cls ->
            val file = cls.containingFile
            val backendIdLine: String? = file.text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("public val BACKEND_ID") || it.startsWith("val BACKEND_ID") }
            requireNotNull(backendIdLine) {
                "ADR-0085: ${cls.fullyQualifiedName} implements IMobAdapter but " +
                    "declares no `BACKEND_ID` constant. Add one referencing " +
                    "MobBackendId.MINIMAL / a new constant."
            }
            cls.fullyQualifiedName!! to backendIdLine
        }

        val tokensByValue: Map<String, List<String>> = backendIdTokens
            .entries
            .groupBy(
                keySelector = { entry -> entry.value.substringAfter("=").trim() },
                valueTransform = { entry -> entry.key },
            )

        val collisions = tokensByValue.filter { (_, owners) -> owners.size > 1 }
        assertThat(collisions)
            .withFailMessage {
                "ADR-0085 backend-id collision: " +
                    "${collisions.map { (id, owners) -> "$id is claimed by $owners" }}. " +
                    "Each IMobAdapter implementation must own a distinct MobBackendId."
            }
            .isEmpty()
    }

    // ─── Wave 5pve-boss — Boss + Encounter + Loot subdomain purity (ADR-0098) ───

    /**
     * ADR-0098: the boss / encounter / loot / raid / runtime subpackages of
     * `core.domain.mob.*` carry zero adapter coupling. They define
     * encounter-level vocabulary (`BossSpec`, `BossPhase`, `EncounterZone`,
     * `LootDrop`, `RaidLockPolicy`, `MobId`) that both the in-core
     * lifecycle service and the Wave 6b Paper subclass consume.
     *
     * The general "core domain must be platform-free" rule already covers
     * Bukkit; this rule adds two ADR-0098-specific guards:
     *
     *  1. No JDBC / Exposed imports (the V30 `boss_kills` audit table is
     *     written through `IBossKillAuditWriter`, never inline in domain).
     *  2. No `java.util.concurrent.*` (boss state is single-threaded on
     *     Paper main thread; concurrent constructs in domain would invite
     *     consistency bugs and confuse the threading contract).
     *
     * Failure surfaces this test, not at server boot.
     */
    @Test
    fun `core domain mob boss encounter loot raid runtime must stay platform-free (ADR-0098)`() {
        val adrSubpackages = setOf(
            "com.astarworks.astera.core.domain.mob.boss",
            "com.astarworks.astera.core.domain.mob.encounter",
            "com.astarworks.astera.core.domain.mob.loot",
            "com.astarworks.astera.core.domain.mob.raid",
            "com.astarworks.astera.core.domain.mob.runtime",
        )
        coreScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                adrSubpackages.any { pkg == it }
            }
            .assertFalse(
                additionalMessage = "ADR-0098: ${'$'}{adrSubpackages.joinToString(\" / \")} " +
                    "must not import JDBC / Exposed / java.util.concurrent — the V30 boss_kills " +
                    "audit table is written through IBossKillAuditWriter, never inline in " +
                    "domain. Boss lifecycle state is single-threaded on Paper main thread.",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("java.util.concurrent")
                    }
            }
    }

    // ─── Wave 5pve-content — PvE content YAML DTO isolation (ADR-0099) ─

    /**
     * ADR-0099: the PvE content YAML DTOs (`core.application.config.mob.pve`)
     * are the boundary between operator-authored
     * `content/pve/{encounters,drop-tables,bosses}/<id>.yaml` and the domain
     * `MobSpec` / `EncounterZone` / `BossSpec` / `LootDrop` types. These DTOs
     * carry `kotlinx.serialization` annotations (per ADR-0022 "no serialization
     * in domain") and convert to domain types via `toEntry()` / `toDomain()`.
     *
     * Two guards beyond the general "config DTOs stay light":
     *
     *  1. **No Bukkit family imports.** The DTOs are pure data — every
     *     Minecraft-side concern (entity spawn, world position) is the
     *     responsibility of the lifecycle service / Paper adapter, not the
     *     YAML layer.
     *  2. **No JDBC / Exposed / `java.util.concurrent.*` imports.** Same
     *     rationale as the ADR-0098 domain rule — the DTOs are loaded once
     *     at boot from disk; persistence + threading concerns belong in
     *     adapter / service layers.
     *
     * Without this rule, an "easy" mistake would be reaching into a Paper
     * `Location` from `EncounterZoneYamlConfig.toEntry()` to validate a
     * world boundary — which would couple the YAML layer to Bukkit and
     * break the headless test contract.
     */
    @Test
    fun `pve content yaml DTOs must stay platform-free (ADR-0099)`() {
        coreScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                pkg == "com.astarworks.astera.core.application.config.mob.pve"
            }
            .assertFalse(
                additionalMessage = "ADR-0099: core.application.config.mob.pve.* DTOs " +
                    "must not import Bukkit family / JDBC / Exposed / java.util.concurrent. " +
                    "The YAML layer is pure data + conversion to domain; Minecraft-side, " +
                    "persistence, and threading concerns belong in service / adapter layers.",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("java.util.concurrent")
                    }
            }
    }

    // ─── Wave 5q — Anti-cheat + Anti-AFK isolation (ADR-0083) ─────────

    /**
     * ADR-0090: the type-erasure boundary. `core.domain.*` MUST NOT see
     * `com.github.retrooper.packetevents.*` — PacketEvents' wrapper surface
     * reshuffles on every Minecraft version bump, and a domain coupling
     * would either decay on every adapter update or force an Astera-internal
     * mirror that dedupes nothing.
     *
     * The Wave 6b PacketEvents adapter lives in `core.adapter.paper.packet.*`
     * (or, when the Paper packet surface bleeds into other plugins, that
     * plugin's `adapter.paper.*`). Interception is mediated entirely through
     * `IPacketInterceptor` + `PacketEnvelope(payloadView: Map<String, Any>)`.
     */
    @Test
    fun `core domain must not import PacketEvents (ADR-0090)`() {
        coreScope()
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.core.domain") == true }
            .assertFalse(
                additionalMessage = "ADR-0090: core.domain.* must stay vendor-neutral. " +
                    "Move PacketEvents wrapper handling to *.adapter.paper.packet.* and " +
                    "marshal into PacketEnvelope.payloadView before crossing into domain.",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.startsWith("com.github.retrooper.packetevents") ||
                        imp.name.startsWith("io.github.retrooper.packetevents")
                }
            }
    }

    /**
     * ADR-0090: every concrete [com.astarworks.astera.core.application.port.outbound.packet.IPacketInterceptor]
     * or [com.astarworks.astera.core.application.port.outbound.visibility.IPlayerVisibilityGateway]
     * implementation MUST live in either `application/service/packet/` /
     * `application/service/visibility/` (vendor-neutral / in-memory default)
     * or in a `<plugin>/adapter/paper/...` package (Wave 6b PacketEvents
     * adapter). Bukkit imports in the application-layer impl would force
     * packet handling onto the Paper main thread, defeating the Netty-side
     * dispatch the production adapter is supposed to use.
     *
     * Mirrors the Wave 5q `IAfkDetector` rule (ADR-0083) — same vendor
     * isolation, different ports. Together they keep the entire packet +
     * visibility surface either neutral or explicitly Paper-scoped.
     */
    @Test
    fun `IPacketInterceptor and IPlayerVisibilityGateway application impls must not import Bukkit Paper (ADR-0090)`() {
        val portSimpleNames = setOf("IPacketInterceptor", "IPlayerVisibilityGateway")
        projectScope()
            .classes()
            .filter { cls ->
                cls.parents().any { parent -> parent.name in portSimpleNames } &&
                    cls.packagee?.name?.contains(".application.") == true
            }
            .assertFalse(
                additionalMessage = "ADR-0090: IPacketInterceptor / IPlayerVisibilityGateway " +
                    "application-layer impls must stay Bukkit-free. Move Paper wiring (the " +
                    "PacketEvents.getAPI().isLoaded() check, the `Player.hidePlayer` fallback) " +
                    "into *.adapter.paper.*.",
            ) { cls ->
                cls.containingFile.imports.any { imp ->
                    bukkitFamily.any { prefix -> imp.name.startsWith(prefix) }
                }
            }
    }

    // ─── Wave 5refine — Item refinement layer isolation (ADR-0100) ─────

    /**
     * ADR-0100 §"新規 domain": `items.domain.refinement.*` is pure-Kotlin
     * domain that composes via the existing
     * [com.astarworks.astera.core.domain.attribute.IAttributeContainer]
     * primitive. It MUST NOT import Bukkit (per ADR-0022 domain isolation),
     * persistence drivers, or `kotlinx.serialization` (the YAML DTO lives in
     * `items.application.config.refinement.*`).
     *
     * The companion `items.application.usecase.refinement.*` MUST stay
     * Bukkit-free for the same reason; persistence + RNG are injected as
     * outbound ports per the universal application-layer rule.
     */
    @Test
    fun `items refinement domain stays pure (ADR-0100)`() {
        projectScope()
            .files
            .filter {
                it.packagee?.name?.startsWith("com.astarworks.astera.items.domain.refinement") == true
            }
            .assertFalse(
                additionalMessage = "ADR-0100: items.domain.refinement.* must stay pure Kotlin " +
                    "domain. Move Bukkit / persistence / kotlinx.serialization couplings into " +
                    "items.application.config.refinement.* (DTOs) or items.adapter.paper.* (Paper sinks).",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.koin")
                    }
            }
    }

    // ─── Wave 5flex — Multi-surface flex layer isolation (ADR-0102) ────

    /**
     * ADR-0102 §"Reuse first (新 plugin 不要)": Wave 5flex introduces four
     * new flex-layer domain packages, each scoped to the plugin that owns
     * the underlying signal:
     *
     *  - `items.domain.glow.*` (surface #1, Item rarity glow)
     *  - `economy.domain.power.*` (surface #4, Power Index / Networth)
     *  - `core.domain.profile.*` (surface #3, Public profile card)
     *  - `stats.domain.nameplate.*` (surface #2 + #5, Nameplate badge)
     *
     * Every one of them MUST stay pure Kotlin per ADR-0022 domain
     * isolation — no Bukkit, no persistence drivers, no
     * `kotlinx.serialization`. YAML DTOs live in
     * `*.application.config.*`; Paper sinks live in `*.adapter.paper.*`.
     */
    @Test
    fun `flex multi-surface domains stay pure (ADR-0102)`() {
        val flexDomainPrefixes = listOf(
            "com.astarworks.astera.items.domain.glow",
            "com.astarworks.astera.economy.domain.power",
            "com.astarworks.astera.core.domain.profile",
            "com.astarworks.astera.stats.domain.nameplate",
        )
        projectScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                flexDomainPrefixes.any { pkg.startsWith(it) }
            }
            .assertFalse(
                additionalMessage = "ADR-0102: Wave 5flex multi-surface domains " +
                    "(items.domain.glow / economy.domain.power / core.domain.profile / " +
                    "stats.domain.nameplate) must stay pure Kotlin. Move Bukkit / " +
                    "persistence / kotlinx.serialization couplings into the matching " +
                    "*.application.config.* DTOs or *.adapter.paper.* sinks.",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.koin")
                    }
            }
    }

    // ─── Wave 5bet — 1v1 Betting domain isolation (ADR-0103) ───────────

    /**
     * ADR-0103 §"Decision": Wave 5bet introduces the
     * `economy.domain.betting.*` subpackage carrying [BettingMatch] /
     * [BettingPolicy] / [BettingEscrow] / [LossStreakState] / [BetStake]
     * / [BettingError]. The audit-grade nature of betting (it touches
     * wallets via the escrow service, and the audit log is the SSOT for
     * JP regulatory compliance per ADR-0035) makes platform purity
     * particularly important: ANY drift into JDBC / Bukkit / serialization
     * inside the domain layer would put the wrong invariants in the wrong
     * layer.
     *
     * Forbidden imports inside `economy.domain.betting.*`:
     *
     *   - Bukkit family (`hasBukkitFamilyImport`)
     *   - kotlinx.serialization annotations (YAML DTOs live in
     *     `economy.application.config.betting.*`)
     *   - JDBC / Exposed / postgres / lettuce (persistence belongs in
     *     `:plugin:persistence`)
     *   - Koin (Phase 1 manual DI)
     *
     * Same scope-from-project pattern as ADR-0100 / ADR-0102 — picks up the
     * package as it migrates between plugin modules without rewriting the rule.
     */
    @Test
    fun `betting domain stays pure (ADR-0103)`() {
        projectScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                pkg.startsWith("com.astarworks.astera.economy.domain.betting")
            }
            .assertFalse(
                additionalMessage = "ADR-0103: economy.domain.betting.* must stay pure Kotlin. " +
                    "Move Bukkit / kotlinx.serialization / persistence / koin couplings into " +
                    "economy.application.config.betting.* DTOs, economy.application.service.betting.* " +
                    "InMemory impls, or persistence-side Postgres overlays.",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.koin")
                    }
            }
    }

    // ─── Wave 5pve-survival — Skyblock party-shared instance (ADR-0107) ─

    /**
     * ADR-0107 §"Decision": Wave 5pve-survival introduces the
     * `core.domain.survival.*` subpackage carrying the party-island
     * lifecycle vocabulary ([PartyIsland] / [IslandPlot] / [GatheringSpot]
     * / [GatheringSkillTrigger] / [GameModeContext] / [PartyInvitation] /
     * [GatheringProgress]).
     *
     * The same purity rules as ADR-0100 / ADR-0102 / ADR-0103 apply: the
     * survival domain is exercised by the future Paper-side gathering
     * listener (Wave 6) which lives in `core.adapter.paper.*` — letting
     * Bukkit / Exposed / kotlinx.serialization leak into the domain would
     * couple persistence + RNG + platform decisions to the rules, exactly
     * what `IGatheringYieldRoller` + `IGatheringProgressRepository` +
     * `IIslandPersistence` ports were created to avoid.
     *
     * Forbidden imports inside `core.domain.survival.*`:
     *   - Bukkit family (`hasBukkitFamilyImport`)
     *   - kotlinx.serialization annotations (YAML DTOs live in
     *     `core.application.config.survival.*`)
     *   - JDBC / Exposed / postgres / lettuce (persistence belongs in
     *     `:plugin:persistence`)
     *   - Koin (Phase 1 manual DI)
     */
    @Test
    fun `effect domain stays pure (ADR-0109)`() {
        projectScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                pkg.startsWith("com.astarworks.astera.core.domain.effect")
            }
            .assertFalse(
                additionalMessage = "ADR-0109: core.domain.effect.* (incl. scheduler/, chain/, " +
                    "sound/, timer/, ratelimit/ subpackages) must stay pure Kotlin. The " +
                    "tick-based ParticleScheduler / SoundScheduler / EffectOrchestrator / " +
                    "CountdownTimer / RateLimiter sealed specs all live here, and any " +
                    "Bukkit / kotlinx.serialization / persistence / koin coupling would " +
                    "force every plugin that constructs an EffectChain to drag the same " +
                    "edge dependencies into its domain layer. YAML DTOs belong in " +
                    "core.application.config.effect.*; InMemory impls belong in " +
                    "core.application.service.effect.*.",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.koin")
                    }
            }
    }

    @Test
    fun `survival domain stays pure (ADR-0107)`() {
        projectScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                pkg.startsWith("com.astarworks.astera.core.domain.survival")
            }
            .assertFalse(
                additionalMessage = "ADR-0107: core.domain.survival.* must stay pure Kotlin. " +
                    "Move Bukkit / kotlinx.serialization / persistence / koin couplings into " +
                    "core.application.config.survival.* DTOs, core.application.service.survival.* " +
                    "InMemory impls, or persistence-side Postgres overlays.",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.koin")
                    }
            }
    }

    // ─── Wave 5world-events — World event + Inventory mutation isolation (ADR-0111) ─

    /**
     * ADR-0111 §"Decision": Wave 5world-events introduces two new domain
     * subpackages that MUST stay pure Kotlin:
     *
     *  - `core.domain.inventory.*` — `ClearScope`, `SlotTypeLockRule`,
     *    `InventoryError`, `InventoryTransferTarget`, `KitId`, `KitError`,
     *    `LootApplied`, `LootError`.
     *  - `core.domain.world.*` additions — `TeleportReason`,
     *    `BlockInteractAction`, `ChunkUnloadReason`, `TimeChangeReason`
     *    (alongside the already-existing world value types).
     *
     * Plus the new world event variants in `core.domain.event.*`
     * (`PlayerMoved`, `PlayerTeleported`, `PlayerInteractedBlock`,
     * `ChunkLoaded`, `ChunkUnloaded`, `WeatherChanged`, `TimeChanged`,
     * `InventoryTransferred`).
     *
     * Same purity rules as ADR-0100 / ADR-0102 / ADR-0107 — these are the
     * vocabulary the Wave 6 Paper adapter will exercise; letting Bukkit /
     * Exposed / kotlinx.serialization leak in would couple platform decisions
     * to the rules, exactly what the new `IInventoryMutator` /
     * `IBulkInventoryOps` / `IRegionEventFilter` ports were created to avoid.
     *
     * Forbidden imports inside `core.domain.inventory.*`:
     *   - Bukkit family (`hasBukkitFamilyImport`)
     *   - kotlinx.serialization annotations
     *   - JDBC / Exposed / postgres / lettuce
     *   - Koin (Phase 1 manual DI)
     */
    @Test
    fun `inventory domain stays pure (ADR-0111)`() {
        projectScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                pkg.startsWith("com.astarworks.astera.core.domain.inventory")
            }
            .assertFalse(
                additionalMessage = "ADR-0111: core.domain.inventory.* must stay pure Kotlin. " +
                    "ClearScope / SlotTypeLockRule / InventoryError / InventoryTransferTarget / " +
                    "KitId / KitError / LootApplied / LootError are the inventory mutation " +
                    "vocabulary. Move Bukkit / kotlinx.serialization / persistence / koin " +
                    "couplings into core.application.config.inventory.* DTOs, " +
                    "core.application.service.inventory.* InMemory impls, or persistence-side " +
                    "Postgres overlays.",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.koin")
                    }
            }
    }

    /**
     * ADR-0111 §"Decision": the Wave 5world-events outbound ports
     * (`IInventoryMutator`, `IBulkInventoryOps`, `IRegionEventFilter`) live
     * in `core.application.port.outbound.inventory.*` and
     * `core.application.port.outbound.world.*`. They are vendor-neutral
     * interfaces — letting Bukkit leak in would defeat the entire point of
     * having ports separate from adapter impls.
     *
     * Forbidden imports inside both port packages:
     *   - Bukkit family — would force the port itself to depend on Paper.
     *   - Persistence drivers — same vendor-neutrality argument.
     *   - kotlinx.serialization — config DTOs live in `application/config/`.
     *
     * `kotlin.reflect.KClass` IS allowed (used by `IRegionEventFilter` for
     * type-dispatched subscription, mirroring `IDomainEventBus`).
     */
    @Test
    fun `inventory and world ports stay vendor-neutral (ADR-0111)`() {
        val portPackages = setOf(
            "com.astarworks.astera.core.application.port.outbound.inventory",
            "com.astarworks.astera.core.application.port.outbound.world",
        )
        projectScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                portPackages.any { pkg == it || pkg.startsWith("$it.") }
            }
            .assertFalse(
                additionalMessage = "ADR-0111: IInventoryMutator / IBulkInventoryOps / " +
                    "IRegionEventFilter must stay vendor-neutral. Move any Bukkit / " +
                    "persistence / kotlinx.serialization coupling into " +
                    "core.application.service.inventory.* or core.application.service.world.* " +
                    "impl files (or, eventually, a *.adapter.paper.* Wave 6 sink).",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.koin")
                    }
            }
    }

    // ─── Wave 5gui-depth — GUI animation + Modal + Tooltip isolation (ADR-0114) ─

    /**
     * ADR-0114 §"Decision": Wave 5gui-depth introduces `core.domain.guidepth.*`
     * carrying the GUI animation / modal / tooltip vocabulary
     * (`AnimationHandle`, `SlotAnimation` sealed, `HoverAnimation` sealed,
     * `ModalHandle`, `GuiModalSpec` sealed, `AnchorPosition` sealed,
     * `TooltipLine` sealed, `TooltipContent`, `TooltipScope` sealed,
     * `ItemCategory`).
     *
     * Plus the new 5 cross-plugin events in `core.domain.event.*`
     * (`SlotAnimationStarted` / `SlotAnimationCompleted` /
     * `SlotAnimationCancelled` / `ModalShown` / `ModalDismissed`).
     *
     * The same purity rules as ADR-0109 / ADR-0110 / ADR-0111 apply:
     * gui-depth is the vocabulary the future Paper-side animation +
     * anvil/boss-bar/overlay-packet adapters (Wave 6) will exercise;
     * letting Bukkit / Exposed / kotlinx.serialization leak in would
     * couple platform decisions to the rules, exactly what
     * `IGuiAnimationController` / `IGuiModalGateway` / `ITooltipRegistry` +
     * `ITooltipExtensionProvider` ports were created to avoid.
     *
     * The Wave 5h `Easing` enum (32 standard curves) is reused — `SlotAnimation`
     * / `HoverAnimation` / `IGuiAnimationController.animateScroll` all import
     * `core.domain.animation.Easing`, which is itself a pure-Kotlin enum.
     * The Wave 5j `HudGlyphRef` + `HudColor` types are reused for
     * `TooltipLine.Image` / `TooltipLine.Text` / `TooltipLine.ProgressBar` —
     * also pure-Kotlin domain primitives.
     *
     * Forbidden imports inside `core.domain.guidepth.*`:
     *   - Bukkit family (`hasBukkitFamilyImport`)
     *   - kotlinx.serialization annotations (DTOs would live in
     *     `core.application.config.guidepth.*` if Phase 4 ever needs YAML for
     *     modals; the in-memory wiring of Phase 1 keeps modal specs in code)
     *   - JDBC / Exposed / postgres / lettuce (gui-depth is session-scoped)
     *   - Koin (Phase 1 manual DI)
     *
     * JOML deliberately allowed: future hover / scroll math might want
     * `Vector2f` if a Phase 4 web admin renderer materialises (Wave 5h-fix
     * precedent — JOML is pure-math, no Minecraft coupling).
     */
    @Test
    fun `gui-depth domain stays pure (ADR-0114)`() {
        projectScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                pkg.startsWith("com.astarworks.astera.core.domain.guidepth")
            }
            .assertFalse(
                additionalMessage = "ADR-0114: core.domain.guidepth.* must stay pure Kotlin. " +
                    "AnimationHandle / SlotAnimation / HoverAnimation / ModalHandle / " +
                    "GuiModalSpec / AnchorPosition / TooltipLine / TooltipContent / " +
                    "TooltipScope / ItemCategory are the gui-depth vocabulary. Move " +
                    "Bukkit / kotlinx.serialization / persistence / koin couplings into " +
                    "core.application.config.guidepth.* DTOs (if ever needed), " +
                    "core.application.service.guidepth.* InMemory impls, or " +
                    "*.adapter.paper.* Wave 6 sinks.",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.koin")
                    }
            }
    }

    /**
     * Wave 5transitions, ADR-0115 §H: `core.domain.hologram.transition.*`
     * stays pure-Kotlin (plus JOML for the rotation interpolator). The
     * package holds the `HologramTransition` sealed hierarchy + `HologramTimeline`
     * + `LoopMode` + interpolator helpers + the transition handle value
     * class — none of which may know about Bukkit, persistence, an event
     * bus, a scheduler, or the gateway port they animate against.
     *
     * The same JOML carve-out as `HologramTransform` applies (Wave 5h-fix
     * precedent — JOML is pure-math, no Minecraft coupling). The transition
     * interpolators wrap `Quaternionf.slerp(other, factor)` to keep the
     * controller free of JOML direct calls — that wrapping belongs in
     * `core.domain.hologram.transition.HologramRotationInterpolator` and
     * deliberately stays in domain (per `feedback_joml_in_domain` memory).
     *
     * Explicitly forbidden:
     *  - Bukkit family (org.bukkit / io.papermc / org.spigotmc /
     *    com.destroystokyo.paper / com.mojang) — adapter sinks live in
     *    `*.adapter.paper.*`
     *  - kotlinx.serialization — YAML / JSON shaping belongs in
     *    `core.application.config.hologram.*` DTOs (if introduced)
     *  - Persistence drivers (Exposed / Postgres / SQL / Lettuce) —
     *    transitions are ephemeral; no Wave 5transitions migration
     *  - `IDomainEventBus` / `IBroadcaster` — these are application-layer
     *    ports; the controller publishes events, not the transition value
     *  - `IScheduler` — tick stepping is the controller's responsibility,
     *    not the transition value's
     *  - `IHologramGateway` — the controller (in application/) holds the
     *    gateway reference, not the transition value
     *  - Koin — Phase 1 manual DI; the controller is wired in
     *    `AsteraCorePlugin.onEnable`
     */
    @Test
    fun `hologram transition domain stays pure (ADR-0115)`() {
        projectScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                pkg.startsWith("com.astarworks.astera.core.domain.hologram.transition")
            }
            .assertFalse(
                additionalMessage = "ADR-0115 violation: core.domain.hologram.transition.* must " +
                    "stay pure Kotlin (+ JOML for rotation interpolation). " +
                    "HologramTransition / HologramTimeline / LoopMode / HologramTransitionHandle " +
                    "/ HologramRotationInterpolator / HologramColorInterpolator are the transition " +
                    "vocabulary. Move Bukkit / kotlinx.serialization / persistence / koin / " +
                    "IDomainEventBus / IBroadcaster / IScheduler / IHologramGateway couplings into " +
                    "core.application.service.hologram.* InMemory impl, or *.adapter.paper.* " +
                    "Wave 6 sinks.",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.koin") ||
                            imp.name.endsWith(".IDomainEventBus") ||
                            imp.name.endsWith(".IBroadcaster") ||
                            imp.name.endsWith(".IScheduler") ||
                            imp.name.endsWith(".IHologramGateway")
                    }
            }
    }

    /**
     * Wave 5complete (ADR-0117 §Axis 6) — `core.domain.item.*` (i.e.
     * the `ItemId` + `ItemEntityHandle` value classes that anchor the
     * item-entity gateway port) must stay free of:
     *
     *   - Bukkit / Paper / Adventure (vendor-neutral; adapter layer maps
     *     [ItemEntityHandle] → Bukkit `Item` entity uuid)
     *   - Persistence drivers (no JDBC / Exposed / Postgres)
     *   - kotlinx.serialization (DTOs live in application/config/)
     *   - Koin (Phase 1 manual DI)
     *   - IDomainEventBus / IBroadcaster / IScheduler / IItemEntityGateway
     *     (application-layer ports; the handle is a passive value class)
     *
     * This guards the Phase 1 PoC posture that the item-entity *handle*
     * value class is symbolic — the adapter holds the (handle → entity)
     * mapping and the gateway holds the lifetime scheduler; the domain
     * type itself stays a pure UUID wrapper.
     */
    @Test
    fun `item entity domain stays pure (ADR-0117)`() {
        projectScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                pkg.startsWith("com.astarworks.astera.core.domain.item")
            }
            .assertFalse(
                additionalMessage = "ADR-0117 violation: core.domain.item.* must stay pure Kotlin. " +
                    "ItemId / ItemEntityHandle are vocabulary value classes; move Bukkit / " +
                    "kotlinx.serialization / persistence / koin / IDomainEventBus / " +
                    "IBroadcaster / IScheduler / IItemEntityGateway couplings into " +
                    "core.application.service.item.* (Phase 1 in-memory) or " +
                    "*.adapter.paper.* (Wave 6b sinks).",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.koin") ||
                            imp.name.endsWith(".IDomainEventBus") ||
                            imp.name.endsWith(".IBroadcaster") ||
                            imp.name.endsWith(".IScheduler") ||
                            imp.name.endsWith(".IItemEntityGateway")
                    }
            }
    }

    /**
     * Wave 5block-embed (ADR-0120) — `core.domain.{block,portal,sign}.*` must
     * stay pure value vocabulary. The block interaction router + sign action
     * service + portal gateway live in `core.application.*`; the **domain**
     * (CustomBlockSpec / Portal / SignAction / PortalActivationRule / …)
     * carries only value types and references to other pure domain types.
     *
     * Forbidden imports per CLAUDE.md §"Non-negotiable rules" + the
     * block-embed scope:
     *
     *  - Bukkit / Paper / Adventure family (vendor-neutral)
     *  - kotlinx.serialization (DTOs live in application/config/)
     *  - Persistence drivers (JDBC / Exposed / Postgres / Lettuce)
     *  - Koin (Phase 1 manual DI)
     *  - IDomainEventBus / IBroadcaster (cross-cutting ports — domain is
     *    side-effect-free; publishing happens in application/eventbus/)
     *
     * Note: `core.domain.gui.GuiIntent` is allowed (it's another pure
     * domain value class; CustomBlockSpec.onRightClick references it).
     * `core.application.command.CommandSpec` is allowed because the
     * sign domain references it as a port-style declarative value
     * (no behaviour leak).
     */
    @Test
    fun `block interaction and portal domain stays pure (ADR-0120)`() {
        projectScope()
            .files
            .filter { file ->
                val pkg = file.packagee?.name ?: return@filter false
                pkg.startsWith("com.astarworks.astera.core.domain.block") ||
                    pkg.startsWith("com.astarworks.astera.core.domain.portal") ||
                    pkg.startsWith("com.astarworks.astera.core.domain.sign")
            }
            .assertFalse(
                additionalMessage = "ADR-0120 violation: core.domain.{block,portal,sign}.* must stay pure Kotlin. " +
                    "Routing tables and dispatch listeners live in core.application.{service,eventbus}.*; " +
                    "domain types are value-only. Move Bukkit / kotlinx.serialization / persistence / " +
                    "koin / IDomainEventBus / IBroadcaster couplings into the application layer.",
            ) { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("kotlinx.serialization") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.koin") ||
                            imp.name.endsWith(".IDomainEventBus") ||
                            imp.name.endsWith(".IBroadcaster")
                    }
            }
    }

    /**
     * Wave 5server-harden / ADR-0123: every `eventBus.subscribe(...)` call
     * inside a `platform/Astera*Plugin.kt` or `platform/wiring/` file (the
     * ADR-0174 host-neutral wiring) MUST be assigned into a
     * subscription holder list (so `onDisable` can cancel it).
     *
     * Accepted prefixes (case-insensitive, leading whitespace ignored):
     *
     *  - `subscriptions += eventBus.subscribe(...)` — canonical Phase-1 holder
     *  - `<name>Subs += eventBus.subscribe(...)` — per-wave bucket (e.g.
     *    `wave5completeSubs`); useful when a plugin wants to cancel a subset
     *    on demand.
     *
     * A bare `eventBus.subscribe(...)` (no `+=` assignment) is a leak: when
     * the plugin re-enables (e.g. `/reload` or hot-swap), the handler stays
     * subscribed and fires twice. The rule is enforced only over
     * `platform/Astera*Plugin.kt` because in-application listeners typically
     * receive an `IDomainEventBus` and store the subscription internally
     * (e.g. `block.application.service.LocationTracker.start`) — that path
     * is checked by the listener's own test, not Konsist.
     *
     * # Hard fail message
     *
     * The failure points at the file + line and reminds the author of the
     * `subscriptions += eventBus.subscribe(...)` + `subscriptions.forEach { it.cancel() }`
     * pattern documented in `.claude/rules/wave-execution.md` and ADR-0123.
     */
    @Test
    fun `every plugin onEnable subscribe must be assigned into a subscription holder (ADR-0123)`() {
        val violators = projectScope()
            .files
            // KoFile.name is the basename WITHOUT extension (e.g. "AsteraCorePlugin",
            // not "AsteraCorePlugin.kt"); see isPluginWiringFile for the scope.
            .filter { file -> isPluginWiringFile(file) }
            .also { requireSubjects(it, "plugin wiring files") }
            .flatMap { file ->
                file.text.lineSequence().withIndex().mapNotNull { (idx, raw) ->
                    val line = raw.trim()
                    val mentionsSubscribe = line.contains("eventBus.subscribe(")
                    if (!mentionsSubscribe) return@mapNotNull null
                    // Comments / KDoc lines are not subscriptions.
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                        return@mapNotNull null
                    }
                    // Accepted assignment: `xxx += eventBus.subscribe(`
                    val assigns = line.contains("+= eventBus.subscribe(") ||
                        line.contains(":= eventBus.subscribe(")
                    if (assigns) null else "${file.path}:${idx + 1}: $line"
                }
            }

        assertThat(violators)
            .withFailMessage {
                "ADR-0123 violation: ${violators.size} unassigned eventBus.subscribe(...) call(s) " +
                    "inside plugin platform/Astera*Plugin.kt or platform/wiring/:\n" +
                    violators.joinToString("\n") { "  $it" } +
                    "\n\nAll subscribes inside *Plugin.onEnable must be assigned to a subscription " +
                    "holder list (e.g. `subscriptions += eventBus.subscribe(...)`) so onDisable can " +
                    "cancel them. See .claude/rules/wave-execution.md §Listener cleanup contract."
            }
            .isEmpty()
    }

    // ─── ADR-0131 — Listener wiring leak prevention (Wave I) ───────────────

    /**
     * ADR-0131 — Every `val xxxListener = ...` declaration inside an
     * `Astera*Plugin.kt` or `platform/wiring/` file must reference
     * `xxxListener` at least once more later in the same file. The wiring may take any of the forms used
     * across Astera plugins:
     *
     *  - `xxxListener.subscribe(...)` — self-subscribe helper
     *  - `eventBus.subscribe(EvtType::class) { xxxListener.handle(it) }`
     *  - `eventBus.subscribe(EvtType::class) { xxxListener.onXxx(it) }`
     *    (event-specific handler convention used by HUD/sidebar listeners)
     *  - `xxxListener.bind(eventBus)` / `xxxListener.start()` / similar
     *    lifecycle invocation
     *  - `server.pluginManager.registerEvents(xxxListener, this)` — Bukkit
     *    Listener registered via Paper API
     *  - `someField = xxxListener` — assigned to a class field for later
     *    `onDisable` access (cancellation pattern)
     *  - `someRegistry.register(xxxListener)` — handed to a registry that
     *    owns wiring (e.g. `bootstrapper.register(...)`)
     *
     * The invariant the rule actually enforces is simpler than the list
     * above: a `val xxxListener = ...` that is **never referenced again**
     * is dead code, and Wave I 5match-wireup-fix (ADR-0126 §A) proved that
     * the dead-code form is indistinguishable from a wiring bug at unit-test
     * time — `AnnihilationWinConditionListener` was constructed but the
     * `subscriptions += annihilationWinConditionListener.subscribe()` line
     * was never added, and every `Annihilation` match silently no-op'd for
     * 50+ commits. By demanding at least one subsequent reference, the
     * rule rejects that class of wiring leak without committing to any
     * single wiring convention.
     *
     * Out-of-scope (acknowledged limitation): listeners declared as
     * `private var xxxListener: T? = null` and assigned later in `onEnable`
     * are not checked — they are rare (currently 2 cases in Match plugin)
     * and follow a separate cancel-via-`stop()` lifecycle.
     */
    @Test
    fun `every plugin val xxxListener must be referenced after declaration (ADR-0131)`() {
        val declRegex = Regex("\\bval\\s+([a-z][A-Za-z0-9]*Listener)\\b")
        val violators = projectScope()
            .files
            // KoFile.name is the basename WITHOUT extension; see isPluginWiringFile for the scope.
            .filter { file -> isPluginWiringFile(file) }
            .also { requireSubjects(it, "plugin wiring files") }
            .flatMap { file ->
                val text = file.text
                declRegex.findAll(text).mapNotNull { match ->
                    val varName = match.groupValues[1]
                    val tailStart = match.range.last + 1
                    val tail = text.substring(tailStart)
                    // Case-sensitive word boundary match: `triggerListener`
                    // (varName) won't collide with `TriggerListener` (class
                    // name) because Kotlin identifiers are case-sensitive.
                    val referenced = Regex("\\b${Regex.escape(varName)}\\b")
                    if (referenced.containsMatchIn(tail)) {
                        null
                    } else {
                        val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                        "${file.path}:$line: val $varName = ... (never referenced after declaration)"
                    }
                }
            }

        assertThat(violators)
            .withFailMessage {
                "ADR-0131 violation: ${violators.size} unwired listener variable(s) " +
                    "in Astera*Plugin.kt / platform/wiring/:\n" +
                    violators.joinToString("\n") { "  $it" } +
                    "\n\nEvery `val xxxListener = ...` inside *Plugin.kt must be " +
                    "referenced at least once more later in the same file (subscribe, " +
                    "handle, on*, bind, start, register, field assignment — any of " +
                    "these counts). A declaration with no subsequent reference is dead " +
                    "code, and Wave I 5match-wireup-fix (ADR-0126) proved that the " +
                    "dead-code form is indistinguishable from a wiring bug at " +
                    "unit-test time — AnnihilationWinConditionListener was constructed " +
                    "but the `subscriptions += xxx.subscribe()` line was never added, " +
                    "and every Annihilation match silently no-op'd for 50+ commits. " +
                    "See ADR-0131."
            }
            .isEmpty()
    }

    // ─── ADR-0174 — host-neutral wiring seam ───────────────────────────────

    /**
     * ADR-0174 — `platform/wiring/` is loaded by the headless simulator
     * (`:tools:game-sim`) without Paper on the classpath, so it must neither
     * import the Bukkit family nor reach into a Paper adapter. Paper impls are
     * injected through each plugin's `*PlatformPorts`; Bukkit listener
     * registration lives in `platform/paper/`.
     *
     * The only `adapter.paper` types wiring may name are the two Bukkit-free
     * Items types that live there for historical reasons (the held-item
     * resolver port and the vendor-neutral click sink).
     */
    @Test
    fun `platform wiring must stay free of Bukkit and Paper adapters (ADR-0174)`() {
        val pureAdapterPaperTypes = setOf(
            "com.astarworks.astera.items.adapter.paper.IPaperItemIdResolver",
            "com.astarworks.astera.items.adapter.paper.ItemSkillTriggerListener",
        )
        val violators = projectScope()
            .files
            .filter { file -> file.path.contains("/platform/wiring/") && !file.path.contains("/test/") }
            .also { requireSubjects(it, "platform/wiring files") }
            .flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter { name ->
                        bukkitFamily.any { prefix -> name.startsWith(prefix) } ||
                            (name.contains(".adapter.paper.") && name !in pureAdapterPaperTypes)
                    }
                    .map { "${file.path}: import $it" }
            }

        assertThat(violators)
            .withFailMessage {
                "ADR-0174 violation: ${violators.size} Bukkit / Paper-adapter import(s) in platform/wiring/:\n" +
                    violators.joinToString("\n") { "  $it" } +
                    "\n\nHost-neutral wiring must not depend on Paper. Inject the adapter through the " +
                    "plugin's *PlatformPorts bundle (built in platform/paper/) instead."
            }
            .isEmpty()
    }

    // ─── ADR-0134 — DomainEvent publisher-exists check (Wave K prevention) ──

    /**
     * ADR-0134 — Every concrete class implementing [DomainEvent] must have at
     * least one publisher: a constructor call `XxxEvent(` in main source
     * outside the event's own defining file.
     *
     * Subscriber side is intentionally NOT checked: Astera's
     * [com.astarworks.astera.core.application.service.admin.InMemoryScheduledAnnouncementSystem]
     * holds a wildcard `eventBus.subscribe(DomainEvent::class)` (Wave 5b
     * analytics consumer) that polymorphically catches every DomainEvent
     * subtype, so a per-type subscriber check would either be vacuously
     * true (current state) or require a 149-entry baseline allowlist
     * (Round 15 Wave N trial — too brittle for Phase 1 PoC closing).
     * See ADR-0131 §Alternatives §Alt A for the scope-shrink rationale.
     *
     * Motivating incident: Wave K 5integration-wire (ADR-0128) had to
     * backfill 7 cross-cutting events whose subscribers were missing —
     * but the symmetric class of bug, **an event whose publisher is
     * missing**, is exactly what this rule catches. Such an event is dead
     * code waiting for someone to remember to add the publisher; the
     * subscriber waits forever. Same silent-drop shape as Wave I (listener
     * waiting for an `eventBus.subscribe` line that was forgotten).
     *
     * Allowlist:
     * `tools/architecture-test/src/test/resources/konsist-allowlist/deferred-events.txt`
     * (one FQN per line, `#` starts a comment, blank lines ignored). Use
     * for events whose publisher is intentionally deferred to a later wave
     * (Wave 6 Paper adapter, Phase 2+ feature) or for events confirmed
     * dead and pending removal.
     */
    @Test
    fun `every DomainEvent must have a publisher (ADR-0134)`() {
        val allowlist = readAdr0134Allowlist()
        val events = collectDomainEventImpls(allowlist)
        val corpus = collectMainSourceCorpus()
        requireSubjects(events, "DomainEvent implementations")
        requireSubjects(corpus, "main source corpus")

        val violators = events.mapNotNull { (fqn, simple, ownPath) ->
            val ctor = Regex("\\b${Regex.escape(simple)}\\(")
            var publisherFound = false
            for ((path, text) in corpus) {
                if (path != ownPath && ctor.containsMatchIn(text)) {
                    publisherFound = true
                    break
                }
            }
            if (publisherFound) null else fqn
        }

        assertThat(violators)
            .withFailMessage {
                "ADR-0134 violation: ${violators.size} DomainEvent class(es) lack " +
                    "any publisher (constructor call site outside their own file):\n" +
                    violators.joinToString("\n") { "  $it" } +
                    "\n\nEvery DomainEvent must be published from at least one " +
                    "non-defining file. To intentionally defer an event (Wave 6 " +
                    "Paper adapter / Phase 2+ feature), add its FQN to " +
                    "tools/architecture-test/src/test/resources/konsist-allowlist/" +
                    "deferred-events.txt. See ADR-0134."
            }
            .isEmpty()
    }

    // ─── ADR-0136 — PaperKnockbackGateway resistance-consultation guard ────

    /**
     * ADR-0136 §Konsist guard (deferred to Wave 6a) — when
     * `PaperKnockbackGateway` exists in `*.adapter.paper.*`, its source MUST
     * contain the token `KNOCKBACK_RESISTANCE` so the resistance-consultation
     * contract on [IKnockbackGateway.applyImpulse] cannot silently regress.
     *
     * Motivating risk: a future contributor refactoring
     * `PaperKnockbackGateway.applyImpulse` could accidentally remove the
     * `player.getAttribute(Attribute.KNOCKBACK_RESISTANCE)` read while
     * keeping the `Player.setVelocity` call — silently dropping
     * `ItemAttributes.knockbackResistance` enforcement on the Astera
     * custom-knockback path (the exact silent feature drop Wave S /
     * ADR-0136 closed). This rule forces the token to remain in source.
     *
     * Born already-green: ships alongside the Wave 6a-1 PaperKnockbackGateway
     * impl (ADR-0144); the file now contains the required token by design.
     */
    @Test
    fun `PaperKnockbackGateway source must reference KNOCKBACK_RESISTANCE token (ADR-0136)`() {
        projectScope()
            .files
            .filter { file ->
                file.path.contains("/adapter/paper/") &&
                    file.name == "PaperKnockbackGateway" &&
                    file.path.contains("/src/main/kotlin/")
            }
            .also { matchedFiles ->
                // Rule is born already-green: when the Paper adapter exists,
                // the token check applies. When it doesn't yet exist (pre-
                // Wave 6a-1 baseline), the rule trivially passes — but we
                // log so future contributors notice the file removal.
                requireSubjects(matchedFiles, "PaperKnockbackGateway.kt")
            }
            .forEach { file ->
                assertThat(file.text)
                    .withFailMessage {
                        "ADR-0136 violation: PaperKnockbackGateway source " +
                            "${file.path} does not contain KNOCKBACK_RESISTANCE " +
                            "token. The Wave S 5knockback-resistance contract " +
                            "(ADR-0136 §A — IKnockbackGateway.applyImpulse KDoc) " +
                            "requires implementations to consult Bukkit " +
                            "Attribute.KNOCKBACK_RESISTANCE before scaling impulse " +
                            "+ calling Player.setVelocity. Removing this read " +
                            "silently drops ItemAttributes.knockbackResistance " +
                            "enforcement on the Astera custom-knockback path."
                    }
                    .contains("KNOCKBACK_RESISTANCE")
            }
    }

    // ─── ADR-0137 + ADR-0149 — PaperDamageGateway death-cycle + container guard ─

    /**
     * ADR-0137 + ADR-0149 / ADR-0151 strengthened guard — when
     * `PaperDamageGateway` exists in `*.adapter.paper.*`, its source MUST
     * contain all tokens proving Astera owns the damage math, AND
     * MUST NOT contain vanilla-coupling escape hatches.
     *
     * Required tokens:
     *  - `appliedThisTick` (ADR-0137 §PaperDamageGateway) — Wave 6a-6
     *    Path 1 vs Path 2 coordination marker.
     *  - `isDead` (ADR-0137 §Alt C) — dead-target short-circuit.
     *  - `Attributes.CURRENT_HP` (ADR-0149 §A / ADR-0151 §E) — container
     *    reads are authoritative HP source.
     *
     * Forbidden patterns (ADR-0149 §G strengthening, landed in Step 3):
     *  - `player.damage(` — invokes vanilla Damageable.damage math,
     *    double-counts Astera's ArmorReductionModifier.
     *  - `player.health = 0` (any whitespace pattern) — fires vanilla
     *    PlayerDeathEvent, bypassing Astera's PlayerKilled lifecycle.
     *
     * Born already-green (Step 3 rewrite). Future refactor that
     * re-introduces either forbidden pattern fails CI with fail message
     * citing ADR-0149 §C/§E.
     */
    @Test
    fun `PaperDamageGateway source must use container + forbid vanilla damage and zero health (ADR-0137 + ADR-0149)`() {
        projectScope()
            .files
            .filter { file ->
                file.path.contains("/adapter/paper/") &&
                    file.name == "PaperDamageGateway" &&
                    file.path.contains("/src/main/kotlin/")
            }
            .also { requireSubjects(it, "PaperDamageGateway.kt") }
            .forEach { file ->
                listOf(
                    "appliedThisTick" to "ADR-0137 §PaperDamageGateway — Wave 6a-6 Path 1 vs Path 2 marker",
                    "isDead" to "ADR-0137 §Alt C — dead-target short-circuit",
                    "Attributes.CURRENT_HP" to "ADR-0149 §A — container reads are authoritative HP",
                ).forEach { (token, rationale) ->
                    assertThat(file.text)
                        .withFailMessage {
                            "ADR-0137 + ADR-0149 violation: PaperDamageGateway " +
                                "source ${file.path} does not contain required " +
                                "token `$token` ($rationale)."
                        }
                        .contains(token)
                }
                // Forbidden vanilla-math escape: invokes Damageable.damage
                // which double-counts vanilla armor / Protection / Resistance.
                assertThat(file.text)
                    .withFailMessage {
                        "ADR-0149 §E violation: PaperDamageGateway source " +
                            "${file.path} contains forbidden `player.damage(` " +
                            "call. Per ADR-0149 §E, the Paper adapter MUST NOT " +
                            "invoke Bukkit's Damageable.damage() — it would " +
                            "double-count vanilla armor / Protection / Resistance " +
                            "/ Absorption against Astera's already-resolved " +
                            "finalAmount. Write container CURRENT_HP directly + " +
                            "mirror to player.health (clamped ≥ MIRROR_MIN_HP=1.0)."
                    }
                    .doesNotContain("player.damage(")
                // Invulnerability is Astera-owned (InvulnerabilityFrames); reading vanilla
                // noDamageTicks made unrelated vanilla i-frames swallow Astera hits.
                assertThat(file.text)
                    .withFailMessage {
                        "ADR-0149 violation: PaperDamageGateway source ${file.path} touches " +
                            "`player.noDamageTicks`. Use InvulnerabilityFrames (Astera-owned window)."
                    }
                    .doesNotContain("player.noDamageTicks")
                // Forbidden zero-HP write: fires vanilla PlayerDeathEvent
                // synchronously, bypassing PlayerKilled domain event flow.
                listOf(
                    "player.health = 0",
                    "player.health=0",
                ).forEach { forbidden ->
                    assertThat(file.text)
                        .withFailMessage {
                            "ADR-0149 §C violation: PaperDamageGateway source " +
                                "${file.path} contains forbidden zero-HP write " +
                                "`$forbidden`. Vanilla PlayerDeathEvent fires at " +
                                "player.health ≤ 0; ADR-0149 §C requires the " +
                                "MIRROR_MIN_HP=1.0 floor on every mirror write. " +
                                "Astera death is via PlayerKilled domain event " +
                                "(ApplyDamageUseCase publishes on " +
                                "DamageOutcome.Killed)."
                        }
                        .doesNotContain(forbidden)
                }
            }
    }

    // ─── ADR-0150 — InMemoryPlayerHealthGateway must use IAttributeContainer ─

    /**
     * ADR-0150 §B / ADR-0149 §A — `InMemoryPlayerHealthGateway` MUST route
     * HP reads / writes through `IAttributeContainer` (via the
     * `containerStore` constructor parameter); it MUST NOT regress to a
     * private `MutableMap<PlayerId, Double>` storing HP / max-health.
     *
     * Motivating risk: Wave 5e ADR-0058 established the universal
     * attribute container substrate, but `InMemoryPlayerHealthGateway`
     * predated the migration and held an internal HP map until ADR-0150
     * (Wave 6a-redesign Step 2). A future refactor that re-introduces an
     * internal map would diverge from `Attributes.CURRENT_HP` / `MAX_HP`
     * (the canonical Astera HP location) — the same stalled-migration
     * drift Wave 5e was designed to prevent.
     *
     * Born already-green: ships alongside the Step 2 refactor; the file
     * now contains the required `containerStore` token and lacks the
     * forbidden `MutableMap<PlayerId, Double>` patterns by design.
     */
    @Test
    fun `InMemoryPlayerHealthGateway must use IAttributeContainer (ADR-0150)`() {
        projectScope()
            .files
            .filter { file ->
                file.path.contains("/src/main/kotlin/") &&
                    file.name == "InMemoryPlayerHealthGateway"
            }
            .also { requireSubjects(it, "InMemoryPlayerHealthGateway.kt") }
            .forEach { file ->
                assertThat(file.text)
                    .withFailMessage {
                        "ADR-0150 violation: InMemoryPlayerHealthGateway source " +
                            "${file.path} does not reference `containerStore`. " +
                            "Per ADR-0149 §A + ADR-0150 §B, HP reads/writes MUST " +
                            "route through `IAttributeContainer.CURRENT_HP / MAX_HP` " +
                            "via the IPlayerAttributeContainerStore constructor " +
                            "parameter. The internal MutableMap<PlayerId, Double> " +
                            "that pre-dated Wave 5e ADR-0058 is permanently retired."
                    }
                    .contains("containerStore")
                listOf(
                    "MutableMap<PlayerId, Double>",
                    "mutableMapOf<PlayerId, Double>",
                ).forEach { forbidden ->
                    assertThat(file.text)
                        .withFailMessage {
                            "ADR-0150 violation: InMemoryPlayerHealthGateway source " +
                                "${file.path} contains forbidden HP-storage pattern " +
                                "`$forbidden`. Per ADR-0149 §A, HP source of truth is " +
                                "`IAttributeContainer.CURRENT_HP / MAX_HP` (Wave 5e " +
                                "ADR-0058 universal container). Internal HP maps " +
                                "create a parallel source of truth that diverges from " +
                                "the container's modifier-aware reads."
                        }
                        .doesNotContain(forbidden)
                }
            }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Recursively walk a [YamlNode] tree and collect all leaf paths as
     * dot-separated strings. Maps contribute their keys; scalars and sequences
     * are leaves (sequence elements are not individually keyed).
     */
    private fun collectDotKeys(node: YamlNode, prefix: String = ""): Set<String> {
        return when (node) {
            is YamlMap -> {
                node.entries.flatMap { (keyNode, valueNode) ->
                    val key = if (prefix.isEmpty()) keyNode.content else "$prefix.${keyNode.content}"
                    collectDotKeys(valueNode, key)
                }.toSet()
            }
            else -> setOf(prefix).filter { it.isNotEmpty() }.toSet()
        }
    }

    // ─── ADR-0134 helpers ─────────────────────────────────────────────────────

    private data class DomainEventImpl(
        val fqn: String,
        val simpleName: String,
        val definingFilePath: String,
    )

    private fun collectDomainEventImpls(allowlist: Set<String>): List<DomainEventImpl> =
        projectScope()
            .files
            .filter { file ->
                file.path.contains("/src/main/kotlin/") &&
                    !file.path.contains("/test/") &&
                    !file.path.contains("/tools/architecture-test/")
            }
            .flatMap { file ->
                file.classes(includeNested = false)
                    .filter { cls ->
                        !cls.hasAbstractModifier &&
                            !cls.hasSealedModifier &&
                            cls.parents().any { it.name == "DomainEvent" }
                    }
                    .mapNotNull { cls ->
                        val fqn = cls.fullyQualifiedName ?: return@mapNotNull null
                        if (fqn in allowlist) null
                        else DomainEventImpl(fqn, cls.name, file.path)
                    }
            }

    // ─── ADR-0184: one /astera command tree ─────────────────────────────────

    /**
     * Commands are contributed to the single `/astera` Brigadier root through
     * `IAsteraCommandRegistry`; a `commands:` block in any plugin.yml would
     * re-introduce a bare legacy root that shadows the tree and has no executor.
     */
    @Test
    fun `no plugin yml declares legacy commands`() {
        val repoRoot = File(".").canonicalFile.let { cwd ->
            if (File(cwd, "content/languages").exists()) cwd else cwd.parentFile?.parentFile ?: cwd
        }
        val offenders = File(repoRoot, "plugin").walkTopDown()
            .filter { it.name == "plugin.yml" && it.path.contains("/src/main/resources/") }
            .filter { f -> f.readLines().any { it.startsWith("commands:") } }
            .map { it.path }
            .toList()
        assertThat(offenders)
            .withFailMessage { "plugin.yml must not declare commands (ADR-0184): $offenders" }
            .isEmpty()
    }

    /** No command root other than `astera`: nothing may mount a hyphenated `astera-<verb>` literal. */
    @Test
    fun `no command literal is a hyphenated astera root`() {
        val literalName = Regex("""CommandSpec\.Literal\(\s*name\s*=\s*"astera-""")
        val offenders = collectMainSourceCorpus()
            .filter { (_, text) -> literalName.containsMatchIn(text) }
            .map { it.first }
        assertThat(offenders)
            .withFailMessage { "Hyphenated astera-<verb> command roots are gone (ADR-0184); mount under /astera instead: $offenders" }
            .isEmpty()
    }

    /**
     * Every executable command node declares a permission (successor of the ADR-0122 rule that
     * admin commands carry `requirePermission`): in the command spec files the number of
     * `executor =` leaves must not exceed the permission declarations. The runtime counterpart is
     * `CommandTreeTest` in :tools:game-sim, which walks the real merged tree.
     */
    @Test
    fun `every executable command node declares a permission`() {
        val offenders = collectMainSourceCorpus()
            .filter { (path, _) ->
                (path.contains("/command/") && (path.endsWith("CommandSpec.kt") || path.endsWith("Commands.kt"))) ||
                    path.endsWith("MatchCommandSpecs.kt")
            }
            .filterNot { (path, _) -> path.endsWith("/CommandSpec.kt") }
            .mapNotNull { (path, text) ->
                val leaves = Regex("""\bexecutor\s*=""").findAll(text).count()
                val guards = Regex("""\.requirePermission\(|\.forEveryone\(|\bpermission\s*=\s*Permission\(""").findAll(text).count()
                if (guards < leaves) "$path: $leaves executors, $guards permission declarations" else null
            }
        assertThat(offenders)
            .withFailMessage { "Executable command nodes without a permission (ADR-0184): $offenders" }
            .isEmpty()
    }

    private fun collectMainSourceCorpus(): List<Pair<String, String>> =
        projectScope()
            .files
            .filter { file ->
                file.path.contains("/src/main/kotlin/") &&
                    !file.path.contains("/tools/architecture-test/")
            }
            .map { it.path to it.text }

    private fun readAdr0134Allowlist(): Set<String> {
        val repoRoot = File(".").canonicalFile.let { cwd ->
            if (File(cwd, "content/languages").exists()) cwd
            else cwd.parentFile?.parentFile ?: cwd
        }
        val file = File(
            repoRoot,
            "tools/architecture-test/src/test/resources/konsist-allowlist/deferred-events.txt",
        )
        if (!file.exists()) return emptySet()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }
}
