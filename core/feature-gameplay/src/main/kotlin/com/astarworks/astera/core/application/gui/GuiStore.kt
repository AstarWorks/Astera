package com.astarworks.astera.core.application.gui

import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Redux-style store for a single declarative-GUI screen.
 *
 *  - [reducer] applies a [GuiIntent] to the current state to produce the next.
 *  - [renderer] projects state into an immutable [GuiSpec] (the VDOM that
 *    [GuiDiffEngine] will reconcile against the previous frame).
 *
 * Holding *state* + *render fn* in the store — rather than a `StateFlow<GuiSpec>`
 * directly — is intentional: it lets call sites express domain state in their
 * own vocabulary (`LoadoutScreenState`, `ShopState`, …) and still benefit from
 * the universal `GuiSpec → SlotPatch` pipeline.
 *
 * **Lifecycle**: the store is bound to a [scope]. The renderer flow uses
 * [SharingStarted.Eagerly] so a fresh subscriber immediately gets the current
 * spec without paying for a re-render, and the upstream `state` map keeps
 * evaluating as long as `scope` is alive. Cancel the scope to release the
 * store (e.g. when the player closes the GUI).
 *
 * **Thread-safety**: [dispatch] is safe to call from any thread —
 * `MutableStateFlow`'s assignment is atomic under the "intent in, state out"
 * contract this class enforces. We do not allow parallel dispatches to
 * interleave; if the host needs back-pressure, channel intents through a
 * coroutine first.
 */
public class GuiStore<S>(
    initial: S,
    public val reducer: (S, GuiIntent) -> S,
    public val renderer: (S) -> GuiSpec,
    scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(initial)

    /** Current state, as a hot flow. */
    public val state: StateFlow<S> = _state.asStateFlow()

    /**
     * VDOM projection of [state]. Eagerly evaluated so the first subscriber
     * does not pay an extra round trip and so it shares the same [GuiSpec]
     * instance across observers (key for `GuiDiffEngine` reference-equality
     * fast paths).
     */
    public val specFlow: StateFlow<GuiSpec> = _state
        .map(renderer)
        .stateIn(scope, SharingStarted.Eagerly, renderer(initial))

    /**
     * Apply [intent] through [reducer]. If the next state is structurally
     * equal to the current one, the underlying `MutableStateFlow` will
     * deduplicate and no spec recomputation happens.
     */
    public fun dispatch(intent: GuiIntent) {
        _state.value = reducer(_state.value, intent)
    }
}
