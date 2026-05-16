package com.astarworks.astera.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

/**
 * Astera architecture enforcement.
 *
 * Encodes the dependency direction rules from docs/architecture/dependency-rules.md
 * and the ADRs that motivate them (notably ADR-0001, ADR-0002).
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

    private fun hasBukkitFamilyImport(file: com.lemonappdev.konsist.api.declaration.KoFileDeclaration): Boolean =
        file.imports.any { imp ->
            bukkitFamily.any { prefix -> imp.name.startsWith(prefix) }
        }

    /**
     * Domain is pure: only Kotlin stdlib + kotlinx.coroutines.
     * Coordinates are domain-defined value types (Vec3 etc.); JOML stays in adapter layers.
     */
    @Test
    fun `domain layer must not depend on any platform or framework`() {
        Konsist.scopeFromModule("plugin/domain")
            .files
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("org.joml") ||
                            imp.name.startsWith("javax.sql") ||
                            imp.name.startsWith("java.sql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.slf4j") ||
                            imp.name.startsWith("org.koin")
                    }
            }
    }

    /**
     * Application defines ports (interfaces) in domain language. It depends on
     * domain only. Logging is fine via SLF4J API.
     */
    @Test
    fun `application layer must not import Bukkit family or persistence drivers`() {
        Konsist.scopeFromModule("plugin/application")
            .files
            .assertFalse { file ->
                hasBukkitFamilyImport(file) ||
                    file.imports.any { imp ->
                        imp.name.startsWith("org.postgresql") ||
                            imp.name.startsWith("io.lettuce") ||
                            imp.name.startsWith("org.jetbrains.exposed") ||
                            imp.name.startsWith("org.joml")
                    }
            }
    }

    /**
     * The vendor-neutral Minecraft abstraction. MUST NOT import Bukkit/Paper.
     * This is the linchpin of ADR-0002 — server-core swappability rests on this.
     */
    @Test
    fun `adapter-minecraft-api MUST stay vendor-neutral (no Bukkit imports)`() {
        Konsist.scopeFromModule("plugin/adapter/minecraft-api")
            .files
            .assertFalse(additionalMessage = "ADR-0002 violation: vendor-neutral layer leaked Bukkit imports.") { file ->
                hasBukkitFamilyImport(file)
            }
    }

    @Test
    fun `adapter-persistence-postgres must not import Minecraft`() {
        Konsist.scopeFromModule("plugin/adapter/persistence-postgres")
            .files
            .assertFalse { file -> hasBukkitFamilyImport(file) }
    }

    @Test
    fun `adapter-messaging-redis must not import Minecraft`() {
        Konsist.scopeFromModule("plugin/adapter/messaging-redis")
            .files
            .assertFalse { file -> hasBukkitFamilyImport(file) }
    }

    /**
     * test-fixtures is a fake-adapter module. It depends on domain + application,
     * not Minecraft. (Real Minecraft fakes can be added under a separate module
     * if needed; this one stays vendor-neutral so it can be used to test
     * adapter-minecraft-api binding code.)
     */
    @Test
    fun `test-fixtures must not import Minecraft`() {
        Konsist.scopeFromModule("plugin/test-fixtures")
            .files
            .assertFalse { file -> hasBukkitFamilyImport(file) }
    }
}
