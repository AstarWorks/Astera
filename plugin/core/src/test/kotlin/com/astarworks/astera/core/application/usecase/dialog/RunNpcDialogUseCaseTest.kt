package com.astarworks.astera.core.application.usecase.dialog

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.contents.NpcDialogContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter
import com.astarworks.astera.core.application.port.outbound.IDialogPredicateEvaluator
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.dialog.DialogEndReason
import com.astarworks.astera.core.domain.dialog.DialogNode
import com.astarworks.astera.core.domain.dialog.DialogNodeId
import com.astarworks.astera.core.domain.dialog.DialogPredicate
import com.astarworks.astera.core.domain.dialog.DialogTreeId
import com.astarworks.astera.core.domain.dialog.NpcDialogError
import com.astarworks.astera.core.domain.dialog.NpcDialogTree
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.NpcDialogEnded
import com.astarworks.astera.core.domain.event.NpcDialogNodeEntered
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.permission.Permission
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.testfixtures.FakeMcPlayer
import com.astarworks.astera.testfixtures.FakeMcServer
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [RunNpcDialogUseCase] (Wave 5p, ADR-0088).
 *
 * Covers: text walk, choice selection, condition branching, intent dispatch,
 * end reasons, error modes.
 */
class RunNpcDialogUseCaseTest {

    private val player = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val npc = EntityHandle(UUID.randomUUID(), backendId = "minimal")
    private val treeId = DialogTreeId("test-tree")

    private lateinit var registry: NpcDialogContents
    private lateinit var events: InMemoryDomainEventBus
    private lateinit var enteredCaptured: MutableList<NpcDialogNodeEntered>
    private lateinit var endedCaptured: MutableList<NpcDialogEnded>
    private lateinit var dispatched: MutableList<Pair<IMcPlayer, GuiIntent>>
    private lateinit var server: FakeMcServer
    private lateinit var useCase: RunNpcDialogUseCase

    @BeforeEach
    fun setUp() {
        registry = NpcDialogContents()
        events = InMemoryDomainEventBus()
        enteredCaptured = mutableListOf()
        endedCaptured = mutableListOf()
        events.subscribe(NpcDialogNodeEntered::class) { enteredCaptured.add(it) }
        events.subscribe(NpcDialogEnded::class) { endedCaptured.add(it) }
        dispatched = mutableListOf()
        server = FakeMcServer().also { it.register(FakeMcPlayer(id = player, name = "test-player")) }
        useCase = RunNpcDialogUseCase(
            registry = registry,
            intentRouter = StubIntentRouter(dispatched),
            predicateEvaluator = StubPredicateEvaluator(alwaysTrue = true),
            events = events,
            scheduler = FakeScheduler(startTick = 0L),
            server = server,
        )
    }

    @Test
    fun `text node walk publishes one entered then ended`() {
        registry.register(
            NpcDialogTree(
                id = treeId,
                rootNodeId = DialogNodeId("greet"),
                nodes = mapOf(
                    DialogNodeId("greet") to DialogNode.Text(
                        messageKey = MessageKey("astera.dialog.greet"),
                        nextNodeId = null,
                    ),
                ),
            ),
        )

        val started = useCase.startTree(player, npc, treeId)
        assertThat(started).isInstanceOf(Result.Success::class.java)
        assertThat(enteredCaptured).hasSize(1)
        assertThat(enteredCaptured[0].nodeId).isEqualTo(DialogNodeId("greet"))

        val advanced = useCase.advance(player)
        assertThat(advanced).isInstanceOf(Result.Success::class.java)
        assertThat(endedCaptured).hasSize(1)
        assertThat(endedCaptured[0].reason).isEqualTo(DialogEndReason.COMPLETED)
        assertThat(useCase.currentNodeId(player)).isNull()
    }

    @Test
    fun `choice node requires choice index, advances on valid index`() {
        registry.register(
            NpcDialogTree(
                id = treeId,
                rootNodeId = DialogNodeId("pick"),
                nodes = mapOf(
                    DialogNodeId("pick") to DialogNode.Choice(
                        promptKey = MessageKey("astera.dialog.pick"),
                        options = listOf(
                            com.astarworks.astera.core.domain.dialog.DialogChoice(
                                labelKey = MessageKey("astera.dialog.pick.option_a"),
                                nextNodeId = DialogNodeId("end-ok"),
                            ),
                            com.astarworks.astera.core.domain.dialog.DialogChoice(
                                labelKey = MessageKey("astera.dialog.pick.option_b"),
                                nextNodeId = DialogNodeId("end-cancel"),
                            ),
                        ),
                    ),
                    DialogNodeId("end-ok") to DialogNode.End(DialogEndReason.COMPLETED),
                    DialogNodeId("end-cancel") to DialogNode.End(DialogEndReason.CANCELLED),
                ),
            ),
        )
        useCase.startTree(player, npc, treeId)
        val noIdx = useCase.advance(player, choiceIndex = null)
        assertThat(noIdx).isInstanceOf(Result.Failure::class.java)
        assertThat((noIdx as Result.Failure).error).isInstanceOf(NpcDialogError.IllegalAdvance::class.java)

        val outOfRange = useCase.advance(player, choiceIndex = 99)
        assertThat((outOfRange as Result.Failure).error)
            .isInstanceOf(NpcDialogError.ChoiceIndexOutOfRange::class.java)

        useCase.advance(player, choiceIndex = 0)
        // landed on end-ok; one more advance to consume the End publishes ended.
        useCase.advance(player)
        assertThat(endedCaptured).hasSize(1)
        assertThat(endedCaptured[0].reason).isEqualTo(DialogEndReason.COMPLETED)
    }

    @Test
    fun `condition node is walked through transparently`() {
        registry.register(
            NpcDialogTree(
                id = treeId,
                rootNodeId = DialogNodeId("check"),
                nodes = mapOf(
                    DialogNodeId("check") to DialogNode.Condition(
                        predicate = DialogPredicate.PermissionCheck(Permission("astera.demo")),
                        ifTrue = DialogNodeId("ok"),
                        ifFalse = DialogNodeId("denied"),
                    ),
                    DialogNodeId("ok") to DialogNode.Text(
                        messageKey = MessageKey("astera.dialog.allowed"),
                        nextNodeId = null,
                    ),
                    DialogNodeId("denied") to DialogNode.End(DialogEndReason.FALSE_BRANCH),
                ),
            ),
        )
        useCase.startTree(player, npc, treeId)
        // alwaysTrue evaluator routes through to `ok`.
        assertThat(enteredCaptured).hasSize(1)
        assertThat(enteredCaptured[0].nodeId).isEqualTo(DialogNodeId("ok"))
    }

    @Test
    fun `intent node dispatches and advances`() {
        registry.register(
            NpcDialogTree(
                id = treeId,
                rootNodeId = DialogNodeId("act"),
                nodes = mapOf(
                    DialogNodeId("act") to DialogNode.Intent(
                        intent = GuiIntent("shop.open", payload = mapOf("shop_id" to "demo")),
                        nextNodeId = null,
                    ),
                ),
            ),
        )
        useCase.startTree(player, npc, treeId)
        useCase.advance(player)
        assertThat(dispatched).hasSize(1)
        assertThat(dispatched[0].second.key).isEqualTo("shop.open")
        assertThat(endedCaptured).hasSize(1)
    }

    @Test
    fun `cancel ends with CANCELLED reason`() {
        registry.register(
            NpcDialogTree(
                id = treeId,
                rootNodeId = DialogNodeId("greet"),
                nodes = mapOf(
                    DialogNodeId("greet") to DialogNode.Text(
                        messageKey = MessageKey("astera.dialog.greet"),
                        nextNodeId = DialogNodeId("greet"), // loop, but we'll cancel
                    ),
                ),
            ),
        )
        useCase.startTree(player, npc, treeId)
        val cancelled = useCase.cancel(player)
        assertThat(cancelled).isInstanceOf(Result.Success::class.java)
        assertThat(endedCaptured).hasSize(1)
        assertThat(endedCaptured[0].reason).isEqualTo(DialogEndReason.CANCELLED)
    }

    @Test
    fun `unknown tree returns TreeNotInCatalog`() {
        val r = useCase.startTree(player, npc, DialogTreeId("does-not-exist"))
        assertThat((r as Result.Failure).error).isInstanceOf(NpcDialogError.TreeNotInCatalog::class.java)
    }

    @Test
    fun `cycle is broken by transition cap`() {
        // Self-true Condition loops forever; the cap aborts.
        registry.register(
            NpcDialogTree(
                id = treeId,
                rootNodeId = DialogNodeId("loop"),
                nodes = mapOf(
                    DialogNodeId("loop") to DialogNode.Condition(
                        predicate = DialogPredicate.PermissionCheck(Permission("astera.x")),
                        ifTrue = DialogNodeId("loop"),
                        ifFalse = DialogNodeId("loop"),
                    ),
                ),
            ),
        )
        val r = useCase.startTree(player, npc, treeId)
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure).error).isInstanceOf(NpcDialogError.IllegalAdvance::class.java)
        assertThat(endedCaptured).hasSize(1)
        assertThat(endedCaptured[0].reason).isEqualTo(DialogEndReason.CANCELLED)
    }

    /** Stub intent router that records dispatches. */
    private class StubIntentRouter(
        private val recorded: MutableList<Pair<IMcPlayer, GuiIntent>>,
    ) : IGuiIntentRouter {
        override fun dispatch(player: IMcPlayer, intent: GuiIntent) {
            recorded.add(player to intent)
        }
        override fun register(
            prefix: String,
            handler: (GuiIntent, IMcPlayer) -> Unit,
        ): ServiceRegistry.Registration = object : ServiceRegistry.Registration {
            override fun unregister() {}
            override val isActive: Boolean = true
        }
    }

    /** Stub predicate evaluator returning a fixed verdict. */
    private class StubPredicateEvaluator(private val alwaysTrue: Boolean) :
        IDialogPredicateEvaluator {
        override fun evaluate(playerId: PlayerId, predicate: DialogPredicate): Boolean = alwaysTrue
    }
}
