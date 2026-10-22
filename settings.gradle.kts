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
    // PREFER_SETTINGS (not FAIL_ON_PROJECT_REPOS): dev boxes may inject a
    // Maven Central mirror into every project via a Gradle init script;
    // project-level repos are ignored either way.
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        mavenCentral()
        // Paper / Minecraft ecosystem
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://oss.sonatype.org/content/repositories/snapshots/") { name = "sonatype-snapshots" }
        maven("https://jitpack.io/") { name = "jitpack" }
        maven("https://repo.codemc.io/repository/maven-public/") { name = "codemc" }
        maven("https://repo.codemc.io/repository/maven-releases/") { name = "codemc-releases" }
        // MCProtocolLib (protocol bots for :tools:e2e, ADR-0186). Scoped: nothing else resolves here.
        maven("https://repo.opencollab.dev/maven-releases/") {
            name = "opencollab"
            content {
                includeGroupAndSubgroups("org.geysermc")
                includeGroupAndSubgroups("org.cloudburstmc")
                includeGroupAndSubgroups("com.nukkitx")
            }
        }
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
    ":plugin:items",       // depend [Core]; Item-centric content (ADR-0073),
                           //                 /astera-items give, click → skill bind.
    ":plugin:combat",      // depend [Core], softdepend [Items]
    ":plugin:match",       // depend [Core], softdepend [Combat]
    ":plugin:stats",       // depend [Core], softdepend [Match, Combat, Items]
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
    // Resource pack pipeline + HUD glyph art generator (ADR-0182). Not shipped.
    ":tools:resource-pack",
    // Procedural 32x32 item icons + item model JSON for weapons (ADR-0193). Not shipped.
    ":tools:item-art",
    // Headless game simulator: boots the production wiring with simulator
    // ports, runs scenarios, exports JSON traces (ADR-0174). Not shipped.
    ":tools:game-sim",
    // Static three.js viewer for game-sim traces + headless PNG/GIF skill frames (ADR-0211). Not shipped, not in `check`.
    ":tools:skill-viewer",
    // Renders the procedural sky-island lobby plan to a PNG preview (ADR-0179). Not shipped.
    ":tools:lobby-gen",
    // Black-box E2E: MCProtocolLib bots join a real Paper backend (docker, `make e2e`) and assert
    // on the packets a client receives (ADR-0186). Needs docker; NOT part of `check`. Not shipped.
    ":tools:e2e",
)
