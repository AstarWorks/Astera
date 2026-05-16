# MC Adapter Layer

Minecraft 連携の **2 段構え adapter 層**の詳細。

## なぜ 2 段なのか

ドメインを Minecraft から切り離す ([[adr/0001]]) のは大前提。しかし、**Minecraft 連携部分自体もサーバーコア (Paper / Spigot / Folia / Purpur / Velocity / Fabric) によって挙動が違う**。

例:
- Folia は region-based threading で `BukkitScheduler` の前提が違う
- Velocity は proxy で player/world API がそもそも違う

「Paper 専用で書く」と、将来別コアに乗り換える時に adapter 層を丸ごと書き直すことになる。

そこで:

1. **`adapter-minecraft-api`** — vendor-neutral な MC 概念抽象 (Bukkit を import しない)
2. **`adapter-minecraft-impl-{paper,folia,…}`** — サーバーコア固有実装

詳細決定: [[adr/0002-vendor-neutral-mc-adapter-layer]]

## `adapter-minecraft-api` の主要抽象

| Interface | 役割 |
|---|---|
| `IMcServer` | サーバー全体 (worlds, players list, tick 数) |
| `IMcWorld` | 単一ワールド (block 操作, entity spawn) |
| `IMcPlayer` | プレイヤー (message, inventory, teleport, location) |
| `IMcEntity` | 一般エンティティ |
| `IMcLivingEntity` | 生物 (damage, health) |
| `IMcBlock` | ブロック (type, state, properties) |
| `IMcInventory` | インベントリ |
| `IMcItem` | アイテムスタック |
| `IMcEvent` (sealed) | イベント基底 (PlayerJoinMc, BlockBreakMc, ...) |
| `IMcScheduler` | tick 抽象 (run later, run repeating, run async) |
| `IMcParticleSink` | パーティクル送信先 |
| `IMcSoundSink` | サウンド送信先 |

これらは **Bukkit / Paper を一切 import しない**。たとえば `IMcPlayer.sendMessage(...)` は Adventure `Component` ではなく Astera ドメイン側で定義したラッパを受け取る。

## `application` の port を `IMc*` で binding する

例: `IPlayerGateway` (application が定義) の実装:

```kotlin
// adapter/minecraft-api/binding/McPlayerGateway.kt
class McPlayerGateway(private val server: IMcServer) : IPlayerGateway {
    override fun sendMessage(playerId: PlayerId, key: MessageKey) {
        val mc = server.findPlayer(playerId) ?: return
        mc.sendMessage(renderer.render(key, mc.locale))
    }
    // ...
}
```

`McPlayerGateway` は Bukkit を知らない。`IMcServer.findPlayer` の実装が **vendor 固有 module で具象化**される。

## `adapter-minecraft-impl-paper` での具象化

```kotlin
// adapter/minecraft-impl-paper/server/PaperServer.kt
class PaperServer(private val bukkit: org.bukkit.Server) : IMcServer {
    override fun findPlayer(id: PlayerId): IMcPlayer? =
        bukkit.getPlayer(id.uuid)?.let { PaperPlayer(it) }
}
```

このモジュールでだけ `org.bukkit.*` の import が許可される。

## サーバーコア切替の手順

例: Folia へ移行する場合

1. `adapter/minecraft-impl-folia/` を新規追加
2. `IMcScheduler` を Folia の `RegionScheduler` で実装
3. `IMcEvent` 周りで region locality を考慮した実装
4. `platform-folia-plugin/` を新規 module として追加、Koin で folia 実装を bind
5. **`domain` / `application` / `adapter-minecraft-api` / 他 adapter は変更ゼロ**

Phase 1 では Paper のみサポート。Folia/Spigot/Velocity は将来オプションとして空ディレクトリも切らない (必要になってから追加 = YAGNI)。

## イベント変換の方向

```
Bukkit Event (Paper 固有)
  ↓ BukkitEventAdapter (adapter-minecraft-impl-paper)
IMcEvent (adapter-minecraft-api, vendor-neutral)
  ↓ McEventTranslator (adapter-minecraft-api/binding)
DomainEvent (domain) ← application が listen
```

逆方向 (Astera → Minecraft):

```
Use case (application)
  ↓ port (IWorldGateway, IPlayerGateway, ...)
McWorldGateway (adapter-minecraft-api/binding) — IMc* を呼び出す
  ↓
PaperWorld (adapter-minecraft-impl-paper) — Bukkit API 呼出し
```
