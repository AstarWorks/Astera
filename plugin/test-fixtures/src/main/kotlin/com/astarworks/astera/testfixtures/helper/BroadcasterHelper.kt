package com.astarworks.astera.testfixtures.helper

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.testfixtures.FakeBroadcaster
import org.assertj.core.api.Assertions.assertThat

/**
 * Extension helpers on [FakeBroadcaster] for scenario / use case tests.
 *
 * Tests typically need two operations on the recorded `published` list:
 *
 *   1. **Filter by event type** — "give me every [com.astarworks.astera.core.domain.event.DamageDealtEvent]".
 *   2. **Assert at least one matches** — "at least one [DamageDealtEvent] was for player X".
 *
 * Both are reified-generic so callers spell the event type once at the call
 * site. The matcher overload uses AssertJ so failure output is consistent
 * with the rest of the test suite (file:line + readable message).
 *
 * Note: this lives in `helper/` rather than alongside [FakeBroadcaster] so
 * downstream test classes can star-import the helpers without pulling the
 * entire `testfixtures.*` surface.
 */

/**
 * Return every recorded broadcast whose runtime type is [T]. Order matches
 * publish order. Empty if no event of [T] has been published.
 */
public inline fun <reified T : DomainEvent> FakeBroadcaster.capturedOf(): List<T> =
    published.filterIsInstance<T>()

/**
 * Assert that at least one published event of type [T] satisfies [matcher].
 * The default matcher (`{ true }`) reduces to "at least one of T was
 * published".
 *
 * On failure, AssertJ reports the captured-of-T list so the diff is obvious.
 */
public inline fun <reified T : DomainEvent> FakeBroadcaster.shouldHaveBroadcast(
    noinline matcher: (T) -> Boolean = { true },
) {
    val captured = capturedOf<T>()
    assertThat(captured)
        .`as`("expected at least one ${T::class.simpleName} matching predicate")
        .anyMatch(matcher)
}
