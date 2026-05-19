package com.astarworks.astera.stats.scenario

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat

/**
 * Entry point for a scenario test block. Constructs the harness and invokes
 * [block] inside a [ScenarioContext]; returns the context so individual
 * tests can do follow-up assertions outside the lambda.
 *
 * ```kotlin
 * @Test fun `kills target`() = scenario {
 *     val attacker = player("alice")
 *     val target = player("bob")
 *     kills(attacker, target, with = ItemId("amethyst-sword"))
 *     shouldBeDead(target)
 * }.let { ctx -> ctx.harness.broadcaster.published.assertHas<PlayerKilled>() }
 * ```
 *
 * Wave _infra-1 / ADR-0059; Wave _items-1 / ADR-0073 dropped the
 * `scenarioWith(weapons = ...)` / `fire(...)` DSL surface (weapons plugin
 * gone — items are now first-class, and skill triggering goes through
 * `IDomainEventBus.publish(SkillCastRequested)`).
 *
 * Intentionally small — ergonomic glue, not a full framework. Add primitives
 * here only when 2+ scenarios duplicate the same setup boilerplate.
 */
public fun scenario(block: ScenarioContext.() -> Unit): ScenarioContext {
    val ctx = ScenarioContext(AsteraTestHarness.newDefault())
    ctx.block()
    return ctx
}

// ──────────────────────────────────────────────────────────────────────
// Kill DSL — the smoke-test idiom: publish PlayerKilled directly.
// ──────────────────────────────────────────────────────────────────────

/**
 * Synthetic kill: publishes a [PlayerKilled] event on the in-memory bus with
 * [attacker] as killer and [target] as victim, damage source =
 * `DamageSource.Item(with, attacker.id)`. Listeners (RewardOnKill /
 * MissionProgressOnKill) fire synchronously.
 *
 * Use this inside a `scenario { ... }` block — it needs the harness on the
 * receiver to find the event bus.
 */
public fun ScenarioContext.kills(attacker: IMcPlayer, target: IMcPlayer, with: ItemId) {
    harness.eventBus.publish(
        PlayerKilled(
            victim = target.id,
            killer = attacker.id,
            source = DamageSource.Item(itemId = with, attackerId = attacker.id),
            atTick = harness.scheduler.currentTick(),
        ),
    )
}

// ──────────────────────────────────────────────────────────────────────
// Assertion helpers — AssertJ-flavored.
// ──────────────────────────────────────────────────────────────────────

/**
 * Asserts [this] has died in the scenario by checking the in-memory bus for
 * a matching [PlayerKilled]. The scenario must publish (directly or via
 * `kills()`) for this to pass.
 */
public fun ScenarioContext.shouldBeDead(player: IMcPlayer) {
    val killed = published<PlayerKilled>().any { it.victim == player.id }
    assertThat(killed)
        .withFailMessage { "expected ${player.name} to be dead but no PlayerKilled event was published" }
        .isTrue()
}

/** Asserts the player has at least [atLeast] XP via the level service. */
public fun ScenarioContext.shouldHaveXp(player: IMcPlayer, atLeast: Long) {
    val xp = harness.levelService.totalXp(player.id).value
    assertThat(xp)
        .withFailMessage { "expected ${player.name} to have >= $atLeast XP but had $xp" }
        .isGreaterThanOrEqualTo(atLeast)
}

/**
 * Every domain event captured by the harness's bus sniffer, typed to [T].
 *
 * The harness subscribes to [DomainEvent] at construction so every publish
 * is recorded in [AsteraTestHarness.publishedEvents]; this helper just
 * filters that log to the requested type.
 */
public inline fun <reified T : DomainEvent> ScenarioContext.published(): List<T> =
    harness.publishedEvents.filterIsInstance<T>()

/**
 * Matcher variant: asserts the [FakeDomainEventBus] has published at least one
 * [T] matching [matcher]. Use only with a harness configured to use
 * [FakeDomainEventBus] — the default harness uses the production
 * [com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus]
 * (which does not retain a published list). Scenarios that need this
 * assertion should construct their own [FakeDomainEventBus] and subscribe
 * the harness listeners manually.
 */
public inline fun <reified T : DomainEvent> FakeDomainEventBus.shouldHavePublished(
    noinline matcher: (T) -> Boolean = { true },
) {
    val match = publishedOfType<T>().any(matcher)
    assertThat(match)
        .withFailMessage {
            "expected at least one ${T::class.simpleName} matching predicate but found none in ${published.size} published events"
        }
        .isTrue()
}

/** Tiny helper so callers can write `harness.bus.publish(...)` then assert origin Vec3. */
public fun originVec3(): Vec3 = Vec3(0.0, 64.0, 0.0)
