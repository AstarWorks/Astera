package com.astarworks.astera.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

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

    private val bukkitFamily = listOf(
        "org.bukkit",
        "io.papermc",
        "org.spigotmc",
        "com.destroystokyo.paper",
        "com.mojang",
    )

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
        Konsist.scopeFromModule("plugin/core")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.core.domain") == true }
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("org.joml") ||
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
        Konsist.scopeFromModule("plugin/core")
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
        Konsist.scopeFromModule("plugin/core")
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
        Konsist.scopeFromModule("plugin/core")
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
        Konsist.scopeFromModule("plugin/core")
            .files
            .assertFalse(
                additionalMessage = "ADR-0022 violation: Core cannot depend on feature plugins (creates dependency cycle).",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.startsWith("com.astarworks.astera.weapons") ||
                        imp.name.startsWith("com.astarworks.astera.combat") ||
                        imp.name.startsWith("com.astarworks.astera.match") ||
                        imp.name.startsWith("com.astarworks.astera.stats") ||
                        imp.name.startsWith("com.astarworks.astera.economy") ||
                        imp.name.startsWith("com.astarworks.astera.persistence")
                }
            }
    }

    // ─── Weapons plugin ─────────────────────────────────────────────────────

    /**
     * Weapons domain is pure. Same rules as Core's domain.
     */
    @Test
    fun `weapons domain must be pure`() {
        Konsist.scopeFromModule("plugin/weapons")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.weapons.domain") == true }
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
    fun `weapons application must not import Bukkit family`() {
        Konsist.scopeFromModule("plugin/weapons")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.weapons.application") == true }
            .assertFalse { file -> hasBukkitFamilyImport(file) }
    }

    /**
     * Weapons MUST NOT depend on any sibling feature plugin (ADR-0022).
     * Weapons is the minimum the rest of the tree composes on top of, so it
     * cannot reach sideways. The rest of Astera composes on top of Weapons,
     * not the other way around.
     */
    @Test
    fun `weapons must not import combat or match or stats or economy or persistence`() {
        Konsist.scopeFromModule("plugin/weapons")
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
        Konsist.scopeFromModule("plugin/combat")
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
        Konsist.scopeFromModule("plugin/combat")
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
        Konsist.scopeFromModule("plugin/combat")
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
        Konsist.scopeFromModule("plugin/match")
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
        Konsist.scopeFromModule("plugin/match")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.match.application") == true }
            .assertFalse { file -> hasBukkitFamilyImport(file) }
    }

    /**
     * Match may soft-depend on Combat (e.g. MatchEnded subscribers in Combat).
     * It MUST NOT reach into Weapons / Stats / Economy / Persistence.
     */
    @Test
    fun `match must not import weapons or stats or economy or persistence`() {
        Konsist.scopeFromModule("plugin/match")
            .files
            .assertFalse(
                additionalMessage = "ADR-0022 violation: Match may use Core + (soft-dep) Combat only.",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.startsWith("com.astarworks.astera.weapons") ||
                        imp.name.startsWith("com.astarworks.astera.stats") ||
                        imp.name.startsWith("com.astarworks.astera.economy") ||
                        imp.name.startsWith("com.astarworks.astera.persistence")
                }
            }
    }

    // ─── Stats plugin ───────────────────────────────────────────────────────

    @Test
    fun `stats domain must be pure`() {
        Konsist.scopeFromModule("plugin/stats")
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
        Konsist.scopeFromModule("plugin/stats")
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
        Konsist.scopeFromModule("plugin/stats")
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
        Konsist.scopeFromModule("plugin/economy")
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
        Konsist.scopeFromModule("plugin/economy")
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
        Konsist.scopeFromModule("plugin/economy")
            .files
            .assertFalse(
                additionalMessage = "ADR-0022 violation: Economy may use Core + (soft-dep) Stats/Match only.",
            ) { file ->
                file.imports.any { imp ->
                    imp.name.startsWith("com.astarworks.astera.weapons") ||
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
        Konsist.scopeFromModule("plugin/persistence")
            .files
            .filter { it.packagee?.name?.startsWith("com.astarworks.astera.persistence.platform") != true }
            .assertFalse(additionalMessage = "Persistence is a DB-side plugin; Minecraft access (other than the JavaPlugin entrypoint) belongs in the platform layer or stays off-limits.") { file ->
                hasBukkitFamilyImport(file)
            }
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
        Konsist.scopeFromModule("plugin/test-fixtures")
            .files
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("com.astarworks.astera.weapons") ||
                            imp.name.startsWith("com.astarworks.astera.combat") ||
                            imp.name.startsWith("com.astarworks.astera.match") ||
                            imp.name.startsWith("com.astarworks.astera.stats") ||
                            imp.name.startsWith("com.astarworks.astera.economy") ||
                            imp.name.startsWith("com.astarworks.astera.persistence")
                    }
            }
    }
}
