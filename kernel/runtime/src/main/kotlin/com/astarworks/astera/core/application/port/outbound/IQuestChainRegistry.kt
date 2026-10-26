package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.quest.QuestChain
import com.astarworks.astera.core.domain.quest.QuestChainId

/**
 * Read-only catalog of [QuestChain] templates (Wave 5s, ADR-0091).
 *
 * Populated at startup by `QuestChainContents.bootstrap(content/quests/)` and
 * hot-reloadable via `/astera reload quests` (ADR-0065 dispatcher). Mirrors
 * [IAdvancementRegistry] / [IItemRegistry] / [ICraftingRecipeRegistry] — the
 * marker extends [Contents] so the underlying substrate (YamlDynamicContents)
 * provides `find` / `all` / `bootstrap` / `reload` uniformly.
 *
 * Phase 4 (UGC marketplace) may swap to a DB-backed `Contents<QuestChainId,
 * QuestChain>` implementation without touching any use case.
 */
public interface IQuestChainRegistry : Contents<QuestChainId, QuestChain>
