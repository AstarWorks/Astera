package com.astarworks.astera.core.application.service

/**
 * Detects, per GUI intent dispatch, that a handler told the player "no" so [DefaultGuiIntentRouter] can play the
 * deny cue once, centrally, instead of every handler doing it. A refusal is a player-facing message rendered in red
 * (the catalogue's convention for errors) while an intent is being handled on this thread.
 */
public object GuiRefusalProbe {
    public class Watch internal constructor(internal val previous: Watch?) {
        internal var refused: Boolean = false
        internal var denied: Boolean = false
    }

    private val current = ThreadLocal<Watch?>()

    /** Starts watching the current thread; hand the result to [finish]. */
    public fun begin(): Watch = Watch(current.get()).also { current.set(it) }

    /** Stops watching; true when the handler refused and did not already play the deny cue itself. */
    public fun finish(watch: Watch): Boolean {
        current.set(watch.previous)
        return watch.refused && !watch.denied
    }

    /** Called by the message renderer wrapper for every rendered message. */
    public fun rendered(text: String) {
        if (text.startsWith("<red>")) current.get()?.refused = true
    }

    /** Called when the deny cue is played by anyone, so it is not doubled. */
    public fun denyPlayed() {
        current.get()?.denied = true
    }
}
