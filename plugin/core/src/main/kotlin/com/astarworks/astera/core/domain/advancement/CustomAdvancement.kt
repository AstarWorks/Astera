package com.astarworks.astera.core.domain.advancement

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Astera's vanilla-Minecraft-advancement replacement (Wave 5p, ADR-0093).
 *
 * # Why replace Vanilla
 *
 * Vanilla Minecraft advancements ship as datapack JSON consumed by Mojang's
 * server with its own NBT round-trip and per-world scope. Astera needs:
 *
 *   - **Cross-server progression** — proxy traversal preserves state via our
 *     own Postgres tables (ADR-0068); Vanilla advancements would be world-local.
 *   - **Custom criteria vocabulary** — Astera-specific signals like
 *     `CurrencyEarned` (STAR / GOLD / GEM) and `MatchesWon` have no Vanilla
 *     equivalent.
 *   - **Composable rewards** — currency / title / achievement / item dispatch
 *     into Astera's own service ports, not Vanilla's `recipes` / `function`
 *     handlers.
 *   - **i18n parity** — title / description rendered through
 *     `IMessageRenderer` (en/ja parity tested), not Vanilla's lang files.
 *
 * # Tree shape
 *
 * [parent] forms an in-memory adjacency tree (parent → many children) that
 * the HUD adapter (Wave 6a) walks for layout. Cycles are rejected by the
 * loader (`AdvancementContents.bootstrap`) at load time; the domain itself
 * does no graph validation.
 *
 * # Lifecycle
 *
 *  1. Loader: YAML in `content/advancements/` parsed into [CustomAdvancement] via
 *     [com.astarworks.astera.core.application.config.advancement.AdvancementYamlConfig].
 *  2. Registry: [com.astarworks.astera.core.application.port.outbound.IAdvancementRegistry]
 *     (Contents-backed) holds the catalog and is reloadable via `/astera reload advancements`.
 *  3. Progress: per-event listeners increment counters in
 *     [com.astarworks.astera.core.application.port.outbound.IPlayerAdvancementProgressRepository].
 *  4. Completion: [com.astarworks.astera.core.application.usecase.advancement.ProgressAdvancementUseCase]
 *     latches `completedAt`, fires [com.astarworks.astera.core.domain.event.AdvancementCompleted],
 *     and dispatches every [AdvancementReward].
 *
 * @property iconItemId The [ItemId] whose icon represents this advancement in
 *  the HUD (resolved to a stack via `IItemRegistry`).
 * @property hidden If true, the advancement is hidden from the player's HUD
 *  until completed (Vanilla's "hidden" flag equivalent).
 */
public data class CustomAdvancement(
    public val id: AdvancementId,
    public val titleKey: MessageKey,
    public val descriptionKey: MessageKey,
    public val iconItemId: ItemId,
    public val criteria: AdvancementCriteria,
    public val rewards: List<AdvancementReward>,
    public val parent: AdvancementId? = null,
    public val frame: AdvancementFrame = AdvancementFrame.TASK,
    public val hidden: Boolean = false,
)
