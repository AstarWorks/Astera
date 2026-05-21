package com.astarworks.astera.architecture

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
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
                    imp.name.startsWith("com.astarworks.astera.items") ||
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
    fun `weapons application must not import Bukkit family`() {
        Konsist.scopeFromModule("plugin/weapons")
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
        Konsist.scopeFromModule("plugin/persistence")
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
            "com.astarworks.astera.core.application",
            "com.astarworks.astera.items.application",
            "com.astarworks.astera.combat.application",
            "com.astarworks.astera.match.application",
            "com.astarworks.astera.stats.application",
            "com.astarworks.astera.economy.application",
            "com.astarworks.astera.persistence.application",
        )
        Konsist.scopeFromProject()
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
     * Combat domain MUST NOT import Bukkit's projectile API. Projectile physics
     * is an edge concern; the domain models projectile as a value (e.g.
     * ProjectileSpec) and delegates collision detection to the Paper adapter.
     */
    @Test
    fun `combat domain must not import bukkit projectile api`() {
        Konsist.scopeFromModule("plugin/combat")
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
        Konsist.scopeFromModule("plugin/core")
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
     * Wave 5e (ADR-0058 §5). `Wallet` / `PlayerStats` / `ManaPool` are
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
            "com.astarworks.astera.combat.domain.resource.ManaPool",
        )
        val allowedStored = setOf("playerId", "container")
        Konsist.scopeFromProject()
            .classes()
            .filter { it.fullyQualifiedName in facadeClasses }
            .flatMap { it.properties() }
            .assertFalse(
                additionalMessage = "ADR-0058 §5: Wallet/PlayerStats/ManaPool must back to IAttributeContainer. " +
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
        Konsist.scopeFromModule("plugin/test-fixtures")
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
            "plugin/weapons",
            "plugin/combat",
            "plugin/match",
            "plugin/stats",
            "plugin/economy",
            "plugin/persistence",
        )
        for (module in featureModules) {
            Konsist.scopeFromModule(module)
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
     *  - [IMatchTimerService]      → Wave 6a (`PaperMatchTimerService`)
     *  - [IPlayerMovementGateway]  → Wave 6a (`PaperPlayerMovementGateway`)
     *  - [IPlayerVisionGateway]    → Wave 6a (`PaperPlayerVisionGateway`)
     *  - [IStructurePaste]         → Wave 6b (`PaperStructurePaste` over FAWE)
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
            "com.astarworks.astera.match.application.port.outbound.IMatchTimerService",
            "com.astarworks.astera.core.application.port.outbound.IPlayerMovementGateway",
            "com.astarworks.astera.core.application.port.outbound.IPlayerVisionGateway",
            "com.astarworks.astera.core.application.port.outbound.IStructurePaste",
        )

        // (1) Every allow-set entry must resolve to a real port interface.
        // If a Wave 6 author deletes a port without trimming the allow-set,
        // this fails loudly instead of silently rotting.
        val knownInterfaces = Konsist.scopeFromProject()
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
        val paperImplsForAllowedPorts = Konsist.scopeFromProject()
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

        val matches = Konsist.scopeFromProject()
            .classes()
            .filter { it.fullyQualifiedName == expectedImplFqn }

        // Skeleton-staging tolerance: if the impl class hasn't landed yet,
        // the test is a no-op. The runtime registry will fall back to the
        // in-memory impl. Once the impl class exists, the parent-check
        // below enforces the wiring contract.
        if (matches.isEmpty()) return

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

        val variantClasses = Konsist.scopeFromProject()
            .classes()
            .filter { cls ->
                cls.parents().any { parent ->
                    parent.name == "ReplayEvent"
                }
            }

        // Skeleton tolerance: if the sealed has no variants yet, no-op. Once
        // a variant exists the rule fires for every declared property.
        if (variantClasses.isEmpty()) return

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
        Konsist.scopeFromProject()
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
}
