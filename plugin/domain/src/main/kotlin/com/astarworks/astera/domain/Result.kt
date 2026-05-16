package com.astarworks.astera.domain

/**
 * Sealed result type for operations that can fail.
 *
 * The standard library's [kotlin.Result] is intentionally avoided because it
 * uses `Throwable` as the failure side, which leaks platform exceptions into
 * domain code and conflates programming errors with expected failure modes.
 *
 * Use the Astera [Result] for "this operation has expected failure modes that
 * callers must handle explicitly" — typically use-case outcomes (`give weapon`,
 * `start match`, `debit wallet`) whose failures are values, not bugs.
 *
 * Keep [E] as a domain-defined sealed type (e.g. `GiveWeaponError`) so the
 * compiler enforces exhaustive handling.
 */
public sealed class Result<out T, out E> {

    public data class Success<out T>(val value: T) : Result<T, Nothing>()
    public data class Failure<out E>(val error: E) : Result<Nothing, E>()

    public val isSuccess: Boolean get() = this is Success
    public val isFailure: Boolean get() = this is Failure

    /** Returns the success value, or null on failure. */
    public fun getOrNull(): T? = (this as? Success)?.value

    /** Returns the failure error, or null on success. */
    public fun errorOrNull(): E? = (this as? Failure)?.error

    /** Transforms the success value; failures pass through. */
    public inline fun <R> map(transform: (T) -> R): Result<R, E> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    /** Chains another Result-returning operation onto a success. */
    public inline fun <R> flatMap(transform: (T) -> Result<R, @UnsafeVariance E>): Result<R, E> = when (this) {
        is Success -> transform(value)
        is Failure -> this
    }

    /** Transforms the failure error; successes pass through. */
    public inline fun <F> mapError(transform: (E) -> F): Result<T, F> = when (this) {
        is Success -> this
        is Failure -> Failure(transform(error))
    }

    /** Reduces both arms to a single value. */
    public inline fun <R> fold(onSuccess: (T) -> R, onFailure: (E) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(error)
    }

    /** Side effect on success; returns `this` for chaining. */
    public inline fun onSuccess(action: (T) -> Unit): Result<T, E> = also {
        if (this is Success) action(value)
    }

    /** Side effect on failure; returns `this` for chaining. */
    public inline fun onFailure(action: (E) -> Unit): Result<T, E> = also {
        if (this is Failure) action(error)
    }

    public companion object {
        public fun <T> success(value: T): Result<T, Nothing> = Success(value)
        public fun <E> failure(error: E): Result<Nothing, E> = Failure(error)
    }
}
