/*
 * :plugin:adapter:minecraft-impl-paper — Paper / Bukkit-specific IMc* implementations.
 *
 * ONLY this module, the :plugin:adapter:providers:*, and :plugin:platform-paper-plugin
 * are allowed to import org.bukkit.* / io.papermc.*. Other layers stay vendor-neutral
 * (see ADR-0002).
 *
 * For Folia / Spigot / Velocity support in the future, add sibling
 * :plugin:adapter:minecraft-impl-{folia,spigot,velocity} modules. Domain /
 * application / minecraft-api remain unchanged.
 */

plugins {
    id("astera.kotlin-paper")
}

dependencies {
    api(project(":plugin:adapter:minecraft-api"))

    // PaperScheduler uses suspendCancellableCoroutine + CoroutineDispatcher.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":plugin:test-fixtures"))
    // MockBukkit's Paper 26.x support to be verified at Phase 1 build time;
    // fall back to paper-test if needed. See plan §9.10.
}
