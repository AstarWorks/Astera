package com.astarworks.astera.core.domain.quest

import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for [QuestChain] invariant validation (Wave 5s, ADR-0091).
 *
 * The chain enforces structural correctness at construction so malformed YAML
 * never reaches the use case:
 *  - at least one stage,
 *  - no duplicate stage ids within a chain,
 *  - every `followingStageId` resolves to a stage in the same chain.
 */
class QuestChainTest {

    @Test
    fun `chain with one stage exposes it as entryStage`() {
        val chain = QuestChain(
            id = QuestChainId("solo"),
            titleKey = MessageKey("astera.quest.solo.title"),
            stages = listOf(stage("a")),
        )
        assertThat(chain.entryStage.id.value).isEqualTo("a")
    }

    @Test
    fun `chain rejects empty stages`() {
        assertThatThrownBy {
            QuestChain(
                id = QuestChainId("bad"),
                titleKey = MessageKey("astera.quest.bad.title"),
                stages = emptyList(),
            )
        }.hasMessageContaining("must have at least one stage")
    }

    @Test
    fun `chain rejects duplicate stage ids`() {
        assertThatThrownBy {
            QuestChain(
                id = QuestChainId("dupe"),
                titleKey = MessageKey("astera.quest.dupe.title"),
                stages = listOf(stage("a"), stage("a")),
            )
        }.hasMessageContaining("duplicate stage ids")
    }

    @Test
    fun `chain rejects followingStageId that references unknown stage`() {
        assertThatThrownBy {
            QuestChain(
                id = QuestChainId("dangling"),
                titleKey = MessageKey("astera.quest.dangling.title"),
                stages = listOf(stage("a", following = "z")),
            )
        }.hasMessageContaining("follows unknown stage 'z'")
    }

    @Test
    fun `stage lookup returns null for unknown id`() {
        val chain = QuestChain(
            id = QuestChainId("solo"),
            titleKey = MessageKey("astera.quest.solo.title"),
            stages = listOf(stage("a")),
        )
        assertThat(chain.stage(QuestStageId("z"))).isNull()
        assertThat(chain.stage(QuestStageId("a"))).isNotNull()
    }

    private fun stage(id: String, following: String? = null): QuestStage = QuestStage(
        id = QuestStageId(id),
        titleKey = MessageKey("astera.quest.solo.$id.title"),
        descriptionKey = MessageKey("astera.quest.solo.$id.desc"),
        criteria = AdvancementCriteria.KillCount(1),
        followingStageId = following?.let { QuestStageId(it) },
    )
}
