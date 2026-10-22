# Adding a command

Every command lives in the single `/astera` tree ([ADR-0184](../adr/0184-single-astera-command-tree.md)):
`/astera <area> <action> <args>`. There is no second root and no `commands:` block in `plugin.yml`.

## 1. Write the spec (vendor-neutral, in your plugin's `command/` package)

```kotlin
CommandSpec.Literal(
    name = "give",
    subcommands = listOf(
        CommandSpec.Argument("target", ArgumentType.PlayerName),
        CommandSpec.Argument("item", ArgumentType.ItemIdArg, completer = { items.all().map { it.id.value } }),
        CommandSpec.Argument("amount", ArgumentType.IntegerArg(min = 1, max = 1024), optional = true),
    ),
    executor = { ctx ->
        // ... call the use case; reply on success, return Failure(key) on every error
        ctx.reply(MessageKey("astera.items.command.give.success"), mapOf("player" to target))
        CommandResult.Success
    },
).requirePermission(Permission("astera.items.give"))       // admin: operators only
// or: .forEveryone(Permission("astera.queue.join"))      // basic player command
```

- Permission node: `astera.<area>.<action>`, on **every** executable leaf. Forgetting it is fail-safe
  (operators only) and the whole-tree test fails.
- Argument types: `PlayerSelector`, `PlayerName`, `IntegerArg`, `DoubleArg`, `StringArg` (single word or
  greedy), `ItemIdArg`, `Choice(...)`. Optional arguments go last. Dynamic suggestions are a `completer`
  closure over your registry; it receives the typed prefix, the arguments already parsed and the sender.
- Reply with `ctx.reply(key, placeholders)` (works for the console too) and return
  `CommandResult.Failure(key, placeholders)` for each error. Do not check permissions in the executor.

## 2. Mount it from your wiring (no Bukkit)

```kotlin
val commands = ctx.commandMount("Astera-YourPlugin")      // removed again by ctx.close() on disable
commands.register("items", giveItemSubcommand(...))       // -> /astera items give
```

Contributions to the same area from several plugins are merged; two executors for one path fail at
enable. Plugins that enable after Core are picked up (the Paper bridge republishes the tree).

## 3. Strings

Add to **both** `content/languages/en.yaml` and `ja.yaml`:

- the node description: `astera.command.help.<path-joined-by-dash>` (e.g. `astera.command.help.items-give`);
  areas too (`astera.command.help.items`);
- every reply / error key your executor uses (`CommandMessageKeysTest` finds literal keys that are missing).

## 4. Tests

Integration tests only (game-sim scenario / e2e / IT); unit tests are forbidden (see `testing-policy.md`).

`:tools:game-sim` boots every real wiring: `sim.command("alice", "items give alice dagger", operator = true)`,
`sim.complete("alice", "items give ")`, and `CommandTreeTest` already asserts permission, executor and
description for every leaf of the merged tree.
