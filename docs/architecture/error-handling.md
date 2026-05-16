# Error Handling

Astera uses **explicit Result types** for expected failure modes and
**exceptions only for bugs / unrecoverable states**.

## The two failure categories

| Category | Mechanism | Examples |
|---|---|---|
| **Expected** — failure is a value the caller must handle | [`Result<T, E>`](../../plugin/domain/src/main/kotlin/com/astarworks/astera/domain/Result.kt) with a sealed `E` | "weapon not found", "insufficient funds", "player not in match" |
| **Unrecoverable** — programming error or environmental failure | `require(...)` / `check(...)` / typed exceptions | "negative cooldown ticks", "DB connection lost mid-write" |

If a caller might reasonably need to take a different action on the failure,
it's expected — return `Result`.

## Naming convention

- Each use case defines a sealed error class named `<UseCase>Error`:
  - `GiveWeaponError`, `FireWeaponError`, `WalletError`
- Cases are data classes carrying enough state to render a message *or* drive
  follow-up logic without re-querying.
- Each case exposes a `messageKey: MessageKey` so callers can `render(key, ...)`
  without duplicating the failure→string mapping.

```kotlin
public sealed class GiveWeaponError {
    public abstract val messageKey: MessageKey

    public data class InvalidWeaponId(val raw: String) : GiveWeaponError() {
        override val messageKey = MessageKey("astera.command.give.invalid_id")
    }
    public data class WeaponNotFound(val raw: String) : GiveWeaponError() {
        override val messageKey = MessageKey("astera.command.give.not_found")
    }
    public data class PlayerNotFound(val targetName: String) : GiveWeaponError() {
        override val messageKey = MessageKey("astera.command.give.player_not_found")
    }
}
```

## Why not `kotlin.Result<T>`?

The standard library `kotlin.Result<T>` uses `Throwable` as its failure side.
That couples expected outcomes to platform exceptions and disables compile-time
exhaustiveness — both unwanted in domain code. Astera's `Result<T, E>` keeps
`E` as a sealed domain type so `when (error) { ... }` is checked at compile time.

## Helpers

```kotlin
val result: Result<Wallet, WalletError> = ledger.debit(player, Currency.STAR, 50)

result
    .onSuccess { wallet -> /* persist or display */ }
    .onFailure { err -> player.sendMessage(messages.render(player, err.messageKey)) }

val balance: Long = result.map { it.balance(Currency.STAR) }.getOrNull() ?: 0
```

| Helper | Purpose |
|---|---|
| `map { }` | transform success; failure passes |
| `flatMap { }` | chain another Result-returning op |
| `mapError { }` | transform failure; success passes |
| `fold(onSuccess, onFailure)` | collapse to a single value |
| `getOrNull()` / `errorOrNull()` | extract one side or null |
| `onSuccess { } / onFailure { }` | side effects, returns `this` for chaining |

## Where exceptions still belong

- `require(...)` in constructors and value-class init blocks (invariants).
- `IllegalStateException` from `check(...)` when an internal invariant is
  violated (a "this should be impossible" condition).
- Wrapped exceptions from third-party library failures (JDBC, network) get
  caught at the adapter boundary and converted to a sealed error case.

A use case returning `Result` should not throw for a regular failure path.

## Layer responsibilities

| Layer | Returns / handles |
|---|---|
| `domain` rules | Pure functions; can return `Result` for partial functions (e.g. `Wallet.withdraw`). |
| `application` use cases | Return `Result<Success, SealedError>`. Never throw for expected failures. |
| `adapter-*` outbound | May throw on infrastructure failure; the bridging code in `platform-*` translates to `Result` if needed. |
| `platform-*` | Top-level handler renders error `MessageKey` to the player and / or logs. |

## Renderering failures to players

Pattern: use the error's `messageKey` plus contextual placeholders.

```kotlin
val out = giveWeapon.execute(req)
out.onFailure { err ->
    val msg = messages.render(
        invokerId,
        err.messageKey,
        mapOf("weapon" to req.weaponIdStr, "player" to req.targetName),
    )
    players.sendMessage(invokerId, msg)
}
```

The use case itself can pre-render at the failure site (current `GiveWeaponUseCase`
does this), or push that responsibility to the caller. Phase 2 mid will likely
push it out so a non-Minecraft frontend (Discord, Web) can render its own way.
