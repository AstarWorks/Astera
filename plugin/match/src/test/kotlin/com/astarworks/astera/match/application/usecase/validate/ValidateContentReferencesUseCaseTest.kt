package com.astarworks.astera.match.application.usecase.validate

import com.astarworks.astera.combat.application.port.outbound.ISkillRegistry
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.item.DropPolicy
import com.astarworks.astera.core.domain.item.DurabilityPolicy
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemAttributes
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.ItemSkillBinding
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.domain.item.PhaseOverrides
import com.astarworks.astera.core.domain.item.ItemTemplate
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.skill.SkillTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ValidateContentReferencesUseCaseTest {

    @Test
    fun `report is ok when every item binding resolves to a known skill`() {
        val known = SkillId("fire-blast")
        val items = StubItemRegistry(
            listOf(itemSpecWithBinding("sword", SkillTrigger.LEFT_CLICK, known)),
        )
        val skills = StubSkillRegistry(setOf(known))
        val useCase = ValidateContentReferencesUseCase(items, skills)

        val report = useCase.validate()

        assertThat(report.ok).isTrue
        assertThat(report.issues).isEmpty()
        assertThat(report.checkedCategories).containsExactlyInAnyOrder("items", "skills")
        assertThat(report.unknownCategories).isEmpty()
    }

    @Test
    fun `report surfaces UnknownSkillReference for a dangling binding`() {
        val items = StubItemRegistry(
            listOf(
                itemSpec(
                    "sword",
                    listOf(
                        ItemSkillBinding(SkillTrigger.LEFT_CLICK, SkillId("fire-blast")),
                        ItemSkillBinding(SkillTrigger.RIGHT_CLICK, SkillId("ghost-skill")),
                    ),
                ),
            ),
        )
        val skills = StubSkillRegistry(setOf(SkillId("fire-blast")))
        val useCase = ValidateContentReferencesUseCase(items, skills)

        val report = useCase.validate()

        assertThat(report.ok).isFalse
        assertThat(report.issues).hasSize(1)
        val issue = report.issues.single() as ValidationIssue.UnknownSkillReference
        assertThat(issue.itemId).isEqualTo(ItemId("sword"))
        assertThat(issue.missingSkillId).isEqualTo(SkillId("ghost-skill"))
    }

    @Test
    fun `unknown category names are returned in unknownCategories without throwing`() {
        val items = StubItemRegistry(emptyList())
        val skills = StubSkillRegistry(emptySet())
        val useCase = ValidateContentReferencesUseCase(items, skills)

        val report = useCase.validate(setOf("items", "mobs"))

        assertThat(report.checkedCategories).containsExactly("items")
        assertThat(report.unknownCategories).containsExactly("mobs")
        assertThat(report.ok).isFalse
    }

    @Test
    fun `skills category flags a no-op mechanic and a CEL script in a skill phase`() {
        val id = SkillId("healer")
        val phases = PhaseMechanics.Buff(
            cast = listOf(
                Mechanic.Heal(Targeter.Caster, NumericValue.Literal(5.0)),
                Mechanic.PlaySound(Targeter.Caster, SoundId("entity.blaze.shoot"), volume = NumericValue.Cel("2.0")),
            ),
        )
        val useCase = ValidateContentReferencesUseCase(
            StubItemRegistry(emptyList()),
            StubSkillRegistry(setOf(id), mapOf(id to phases)),
        )

        val report = useCase.validate(setOf("skills"))

        assertThat(report.ok).isFalse
        val issues = report.issues.map { it as ValidationIssue.UnsupportedContent }
        assertThat(issues).allMatch { it.owner == "skill healer" }
        assertThat(issues.map { it.problem }).anyMatch { "Heal" in it }.anyMatch { "CEL" in it }
    }

    @Test
    fun `skills category flags a no-op mechanic in an item binding phase override`() {
        val id = SkillId("fire-blast")
        val binding = ItemSkillBinding(
            trigger = SkillTrigger.LEFT_CLICK,
            skillId = id,
            phaseOverrides = PhaseOverrides(
                hitEntityAppend = listOf(Mechanic.Explode(Targeter.Target, NumericValue.Literal(2.0))),
            ),
        )
        val useCase = ValidateContentReferencesUseCase(
            StubItemRegistry(listOf(itemSpec("wand", listOf(binding)))),
            StubSkillRegistry(setOf(id)),
        )

        val report = useCase.validate(setOf("skills"))

        val issue = report.issues.single() as ValidationIssue.UnsupportedContent
        assertThat(issue.owner).isEqualTo("item wand binding fire-blast")
        assertThat(issue.problem).contains("Explode")
    }

    @Test
    fun `skills category passes for fully supported content`() {
        val id = SkillId("ok")
        val phases = PhaseMechanics.Buff(
            cast = listOf(Mechanic.PlaySound(Targeter.Caster, SoundId("entity.blaze.shoot"))),
        )
        val useCase = ValidateContentReferencesUseCase(
            StubItemRegistry(emptyList()),
            StubSkillRegistry(setOf(id), mapOf(id to phases)),
        )

        assertThat(useCase.validate(setOf("skills")).ok).isTrue
    }

    @Test
    fun `empty category set defaults to ALL`() {
        val items = StubItemRegistry(emptyList())
        val skills = StubSkillRegistry(emptySet())
        val useCase = ValidateContentReferencesUseCase(items, skills)

        val report = useCase.validate(emptySet())

        assertThat(report.checkedCategories).isEqualTo(ValidateContentReferencesUseCase.ALL)
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun itemSpec(id: String, bindings: List<ItemSkillBinding>): ItemSpec = ItemSpec(
        id = ItemId(id),
        template = ItemTemplate.Vanilla(material = "IRON_SWORD"),
        displayName = LocalizedText.EMPTY,
        lore = LocalizedLore.EMPTY,
        rarity = Rarity.COMMON,
        levelRequirement = 1,
        attributes = ItemAttributes.EMPTY,
        equipSlot = EquipSlot.MAINHAND,
        durability = DurabilityPolicy.DEFAULT,
        dropPolicy = DropPolicy.DEFAULT,
        enchantable = false,
        glint = null,
        skillBindings = bindings,
        pdcTags = emptyMap(),
    )

    private fun itemSpecWithBinding(id: String, trigger: SkillTrigger, skillId: SkillId): ItemSpec =
        itemSpec(id, listOf(ItemSkillBinding(trigger = trigger, skillId = skillId)))

    private class StubItemRegistry(items: List<ItemSpec>) : IItemRegistry {
        private val byId = items.associateBy { it.id }
        override fun find(key: ItemId): ItemSpec? = byId[key]
        override fun all(): Collection<ItemSpec> = byId.values
    }

    /**
     * Returns SkillSpec lookups as `null` (we only ever consult `all()` for
     * the id set, never `find(...)`). [knownIds] backs `all()` via a stub
     * SkillSpec list whose only field the use case touches is `id`.
     */
    private class StubSkillRegistry(
        private val knownIds: Set<SkillId>,
        private val phasesById: Map<SkillId, PhaseMechanics> = emptyMap(),
    ) : ISkillRegistry {
        override fun find(key: SkillId): SkillSpec? = null
        override fun all(): Collection<SkillSpec> =
            knownIds.map { id -> buildSkillSpecStub(id) }

        // The use case touches only `SkillSpec.id`, so we can produce a
        // minimal stub spec. Reaching for reflection / mockk in a domain
        // unit test would be heavier than the constructor surface here.
        private fun buildSkillSpecStub(id: SkillId): SkillSpec = SkillSpec(
            id = id,
            name = LocalizedText.EMPTY,
            description = LocalizedText.EMPTY,
            archetype = com.astarworks.astera.combat.domain.skill.SkillArchetype.MELEE,
            cooldownTicks = 0,
            params = com.astarworks.astera.combat.domain.skill.params.SkillParams.MeleeParams(
                range = 1.0,
                knockback = 0.0,
                damage = com.astarworks.astera.core.domain.damage.DamageProfile.single(
                    1.0,
                    com.astarworks.astera.core.domain.damage.DamageAttribute.PHYSICAL,
                ),
            ),
            phases = phasesById[id] ?: PhaseMechanics.Melee(),
        )
    }
}
