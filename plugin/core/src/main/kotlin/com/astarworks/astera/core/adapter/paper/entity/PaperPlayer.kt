package com.astarworks.astera.core.adapter.paper.entity

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import kotlin.time.Duration
import net.kyori.adventure.text.minimessage.MiniMessage
import com.astarworks.astera.core.adapter.paper.text.AsteraTextDeserializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IMcPlayer]. The only layer where `org.bukkit.*` imports
 * are sanctioned for player operations (see ADR-0002 and the Konsist guard
 * in :tools:architecture-test).
 *
 * Vanilla templates resolve via `Material.matchMaterial`; External templates
 * (Oraxen / ItemAdder) fall back to STONE in Stage 1 since the provider
 * adapters are not yet wired (Stage 6 / Phase 4).
 *
 * PDC tags: every materialized item gets an `astera:item-id` tag plus any
 * extra entries from `template.pdcTags`. Stage 2 H uses these for weapon
 * identity at fire-time (replacing material matching).
 */
class PaperPlayer(private val bukkit: Player) : IMcPlayer {

    override val id: PlayerId = PlayerId(bukkit.uniqueId)
    override val name: String = bukkit.name
    override val location: Vec3 get() = bukkit.location.let { Vec3(it.x, it.y, it.z) }
    override val locale: String? get() = bukkit.locale().toString()

    override fun sendMessage(rendered: String) {
        bukkit.sendMessage(AsteraTextDeserializer.deserialize(rendered))
    }

    override fun giveItem(template: IMcItemTemplate, displayNameOverride: String?, loreOverride: List<String>) {
        val item = when (template) {
            is IMcItemTemplate.Vanilla -> buildVanilla(template)
            is IMcItemTemplate.External -> {
                log.warn(
                    "External item template '{}' (key={}) — provider adapter not wired in Stage 1; falling back to STONE.",
                    template.id, template.key,
                )
                ItemStack(Material.STONE)
            }
        }
        item.editMeta { meta ->
            // Wave _items-1 (ADR-0073): the template now carries pre-rendered
            // MiniMessage strings (resolved from ItemSpec's inline LocalizedText
            // by the application-side toAdapterTemplate conversion). Override
            // wins; otherwise fall back to the template's own pre-rendered
            // value; otherwise leave the Material's default name / no lore.
            val effectiveDisplay = displayNameOverride
                ?: (template as? IMcItemTemplate.Vanilla)?.displayName
            if (effectiveDisplay != null) {
                meta.displayName(AsteraTextDeserializer.deserialize(effectiveDisplay))
            }
            val effectiveLore = if (loreOverride.isNotEmpty()) {
                loreOverride
            } else {
                (template as? IMcItemTemplate.Vanilla)?.lore ?: emptyList()
            }
            if (effectiveLore.isNotEmpty()) {
                meta.lore(effectiveLore.map { AsteraTextDeserializer.deserialize(it) })
            }
            val pdc = meta.persistentDataContainer
            pdc.set(ITEM_ID_KEY, PersistentDataType.STRING, template.id.value)
            if (template is IMcItemTemplate.Vanilla) {
                if (template.customModelData != null) {
                    @Suppress("DEPRECATION")
                    meta.setCustomModelData(template.customModelData)
                }
                for ((rawKey, rawValue) in template.pdcTags) {
                    val key = NamespacedKey.fromString(rawKey) ?: continue
                    pdc.set(key, PersistentDataType.STRING, rawValue)
                }
            }
        }
        bukkit.inventory.addItem(item)
    }

    override fun heldItemMaterialKey(): String? {
        val item = bukkit.inventory.itemInMainHand
        return if (item.type.isAir) null else item.type.name
    }

    override fun sendActionBar(messageKey: MessageKey, placeholders: Map<String, String>) {
        TODO("Wave 6 で実装 — Adventure API integration per ADR-0031")
    }

    override fun sendTitle(
        titleKey: MessageKey,
        subtitleKey: MessageKey?,
        fadeIn: Duration,
        stay: Duration,
        fadeOut: Duration,
    ) {
        TODO("Wave 6 で実装 — Adventure API integration per ADR-0031")
    }

    private fun buildVanilla(template: IMcItemTemplate.Vanilla): ItemStack {
        val material = Material.matchMaterial(template.material) ?: run {
            log.warn("Unknown Material '{}' for item id={}, falling back to STONE", template.material, template.id)
            Material.STONE
        }
        return ItemStack(material)
    }

    private companion object {
        val MM: MiniMessage = MiniMessage.miniMessage()
        val ITEM_ID_KEY: NamespacedKey = NamespacedKey.fromString("astera:item-id")!!
        val log = LoggerFactory.getLogger(PaperPlayer::class.java)
    }
}
