package com.astarworks.astera.core.application.contents

/**
 * A balance value that `astera admin reload` can replace while consumers hold the ref: they read
 * [current] at the moment they need it (never cache the value), so a reload applies from the next read.
 */
public class BalanceRef<T : Any>(initial: T) {
    @Volatile
    public var current: T = initial
        private set

    /** Replaces the value; returns the previous one. */
    public fun swap(next: T): T {
        val prev = current
        current = next
        return prev
    }
}
