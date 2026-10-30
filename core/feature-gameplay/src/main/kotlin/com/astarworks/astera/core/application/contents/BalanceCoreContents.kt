package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.BlockDurabilityYamlConfig
import com.astarworks.astera.core.application.config.EgoYamlConfig
import com.astarworks.astera.core.application.config.HealthRegenYamlConfig
import com.astarworks.astera.core.application.config.StarterGrantYamlConfig
import com.astarworks.astera.core.application.service.BlockDurabilityLoader
import com.astarworks.astera.core.application.service.EgoConfigLoader
import com.astarworks.astera.core.application.service.HealthRegenConfigLoader
import com.astarworks.astera.core.application.service.StarterGrantLoader
import com.astarworks.astera.core.application.service.ContentLoader
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.ego.EgoConfig
import com.astarworks.astera.core.domain.player.StarterGrant
import com.astarworks.astera.core.domain.player.physiology.HealthRegenConfig
import com.astarworks.astera.core.domain.world.BlockDurabilityRule
import com.charleskorn.kaml.Yaml
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * The core balance files (`content/balance/{starter-grant,block-durability,ego,health-regen}.yaml`)
 * as one reload category (`balance-core`). Boot is fail-soft (the existing loaders); a reload is
 * strict and all-or-nothing: if any present file is invalid nothing is swapped and the report names
 * the file. A missing file means the same defaults as at boot.
 *
 * Consumers hold the [BalanceRef]s and read `.current` at use time, so a swap applies from the next
 * read: ego / health-regen on the next tick, durability at the next hit, the starter grant to the
 * next joiner. A raised starter-grant wallet amount is also topped up on existing profiles through
 * [onStarterGrantChanged] (never clawed back).
 */
public class BalanceCoreContents(balanceDir: Path) {
    public val starterGrant: BalanceRef<StarterGrant> = BalanceRef(StarterGrantLoader().load(balanceDir.resolve(STARTER_GRANT)))
    public val durability: BalanceRef<BlockDurabilityRule> = BalanceRef(BlockDurabilityLoader().load(balanceDir.resolve(DURABILITY)))
    public val ego: BalanceRef<EgoConfig> = BalanceRef(EgoConfigLoader().load(balanceDir.resolve(EGO)))
    public val healthRegen: BalanceRef<HealthRegenConfig> = BalanceRef(
        runCatching { HealthRegenConfigLoader().load(balanceDir.resolve(HEALTH_REGEN)) }.getOrDefault(HealthRegenConfig.DEFAULTS),
    )

    /** Called with `(old, new)` after a successful swap of the starter grant. Set by the wiring. */
    @Volatile
    public var onStarterGrantChanged: (old: StarterGrant, new: StarterGrant) -> Int = { _, _ -> 0 }

    public val reloadable: ReloadableContents = object : ReloadableContents {
        override val categoryName: String = "balance-core"
        override fun reload(dir: Path): ContentLoader.LoadReport = this@BalanceCoreContents.reload(dir)
    }

    private fun reload(dir: Path): ContentLoader.LoadReport {
        val failures = mutableListOf<ContentLoader.LoadFailure>()
        fun <T> strict(name: String, absent: T, parse: (String) -> T): T {
            val path = dir.resolve(name)
            if (!path.exists()) return absent
            return runCatching { parse(path.readText()) }.getOrElse {
                failures += ContentLoader.LoadFailure(path, "invalid $name: ${it.message}")
                absent
            }
        }

        val grant = strict(STARTER_GRANT, StarterGrant.EMPTY) { text ->
            when (val r = Yaml.default.decodeFromString(StarterGrantYamlConfig.serializer(), text).toDomain()) {
                is Result.Success -> r.value
                is Result.Failure -> error(r.error.detail)
            }
        }
        val rule = strict(DURABILITY, BlockDurabilityRule.defaultMatrix()) { text ->
            when (val r = Yaml.default.decodeFromString(BlockDurabilityYamlConfig.serializer(), text).toDomain()) {
                is Result.Success -> r.value
                is Result.Failure -> error(r.error.detail)
            }
        }
        val egoCfg = strict(EGO, EgoConfig.DEFAULTS) { Yaml.default.decodeFromString(EgoYamlConfig.serializer(), it).toDomain() }
        val regen = strict(HEALTH_REGEN, HealthRegenConfig.DEFAULTS) {
            Yaml.default.decodeFromString(HealthRegenYamlConfig.serializer(), it).toDomain()
        }
        if (failures.isNotEmpty()) return ContentLoader.LoadReport(loaded = 0, failed = failures.size, failures = failures)

        val oldGrant = starterGrant.swap(grant)
        durability.swap(rule)
        ego.swap(egoCfg)
        healthRegen.swap(regen)
        val notes = mutableListOf(
            "ego / health-regen apply from the next tick, block durability from the next hit",
            "starter-grant items, tutorial and welcome apply to players who join from now on",
        )
        val topped = onStarterGrantChanged(oldGrant, grant)
        if (topped > 0) notes += "starter-grant wallet raise: topped up $topped existing profiles (no claw-back on lowering)"
        return ContentLoader.LoadReport(loaded = 4, failed = 0, notes = notes)
    }

    private companion object {
        const val STARTER_GRANT = "starter-grant.yaml"
        const val DURABILITY = "block-durability.yaml"
        const val EGO = "ego.yaml"
        const val HEALTH_REGEN = "health-regen.yaml"
    }
}
