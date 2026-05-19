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

// Multi-plugin portable architecture (ADR-0022).
// Each module produces one independent Paper plugin jar via Shadow.
// Plugin-to-plugin coordination happens through Core's ServiceRegistry +
// the central IDomainEventBus. See docs/adr/0022-* for the rationale.
include(
    // Mandatory base plugin: shared domain, ServiceRegistry, IDomainEventBus,
    // Paper adapter scaffolding.
    ":plugin:core",

    // Feature plugins (each optional, soft-depended where coupled):
    ":plugin:weapons",     // depend [Core]; minimum + Core for the
                           //                 server-admin "test a weapon" flow.
    ":plugin:combat",      // depend [Core], softdepend [Weapons]
    ":plugin:match",       // depend [Core], softdepend [Combat]
    ":plugin:stats",       // depend [Core], softdepend [Match, Combat, Weapons]
    ":plugin:economy",     // depend [Core], softdepend [Stats, Match]
    ":plugin:persistence", // depend [Core], softdepend [...]; Postgres impls
                           //                 override in-memory defaults.

    // Test helpers (in-memory adapters, fakes) — used across plugins
    ":plugin:test-fixtures",
    // Architecture enforcement (Konsist)
    ":tools:architecture-test",
    // JSON Schema generation from @Serializable DTOs (ADR-0016 part 4)
    ":tools:schema-gen",
    // content/ YAML × content/schemas/*.schema.json validator (ADR-0066)
    ":tools:content-lint",
)
