package com.astarworks.astera.core.domain.skill

import com.astarworks.astera.core.domain.item.ItemId

/**
 * Naming scheme of "skill tester" items (F2-5, ADR-0194): an admin item that casts
 * one arbitrary skill on right-click. There is no content file per skill. The item
 * carries the id `skill-tester-<skill-id>` in its `astera:item_id` PDC, and the items
 * plugin turns a right-click on such an id into a cast of `<skill-id>`.
 *
 * `skill-tester` (no suffix) is the content item the testers are templated from
 * (material, lore).
 */
public object SkillTesterItems {

    public const val PREFIX: String = "skill-tester-"

    /** Content item the per-skill testers copy material and look from. */
    public val TEMPLATE: ItemId = ItemId("skill-tester")

    public fun idFor(skill: SkillId): ItemId = ItemId(PREFIX + skill.value)

    /** The skill a tester item casts, or `null` when [item] is not a tester id. */
    public fun skillOf(item: ItemId): SkillId? =
        item.value.takeIf { it.startsWith(PREFIX) && it.length > PREFIX.length }
            ?.let { runCatching { SkillId(it.removePrefix(PREFIX)) }.getOrNull() }
}
