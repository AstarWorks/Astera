pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // Paper / Minecraft ecosystem
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://oss.sonatype.org/content/repositories/snapshots/") { name = "sonatype-snapshots" }
        maven("https://jitpack.io/") { name = "jitpack" }
        maven("https://repo.codemc.io/repository/maven-public/") { name = "codemc" }
        maven("https://repo.codemc.io/repository/maven-releases/") { name = "codemc-releases" }
        // External Paper plugins
        maven("https://repo.dmulloy2.net/repository/public/") { name = "protocollib" }
        maven("https://repo.oraxen.com/releases/") { name = "oraxen" }
        maven("https://mvn.lumine.io/repository/maven-public/") { name = "mythic-lumine" }
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/") { name = "placeholderapi" }
    }
}

rootProject.name = "astera"

// Production modules — strict Clean Architecture / Hexagonal layering.
// Dependency direction MUST flow strictly upward (see docs/architecture/dependency-rules.md).
include(
    // Lv0: pure domain
    ":plugin:domain",
    // Lv1: use cases + ports
    ":plugin:application",
    // Lv2: adapters (port implementations)
    ":plugin:adapter:minecraft-api",
    ":plugin:adapter:minecraft-impl-paper",
    ":plugin:adapter:persistence-postgres",
    ":plugin:adapter:messaging-redis",
    ":plugin:adapter:providers:weaponmechanics",
    ":plugin:adapter:providers:oraxen",
    ":plugin:adapter:providers:mythicmobs",
    // Lv3: platform entrypoint
    ":plugin:platform-paper-plugin",
    // Test helpers (in-memory adapters, fakes)
    ":plugin:test-fixtures",
    // Architecture enforcement (Konsist)
    ":tools:architecture-test",
)
