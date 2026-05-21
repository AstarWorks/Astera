---
status: Accepted
date: 2026-05-21
deciders: Wave 5o orchestrator
supersedes: null
superseded_by: null
---

# ADR-0081: Respawn flow + PvP toggle + Spawn selector + Status-effect interaction matrix

## Status

Accepted

## Context

Astera は Phase 1 PoC closing の Wave 5o で、Annihilation server vision
(`.claude/plans/idea-annihilation-vision.md` 2026-05-20 確定) の **respawn
loop** を land する。Wave 5k (ADR-0080) で `IPlayerHealthGateway` /
`IResourcePoolService` 等の Bukkit-runtime mutation layer が完了したので、
そこに乗る「死亡 → respawn → 一時無敵 → 再戦」サイクルを 1 wave で commit する
タイミング。

直接の動機は 4 つの隣接する gap を同時に閉じることで、それぞれ単独 wave 化
すると wiring 重複・ADR 番号浪費・テスト fixture 重複が出る:

1. **RespawnUseCase が存在しない**。MatchSpawnUseCase (Wave 4.5, ADR-0027)
   は match entry の片道 teleport だけで、死後の復帰経路を持たない。
2. **Spawn protection (post-respawn invuln) が存在しない**。Annihilation で
   は spawn 直後 5 秒の damage 完全 reject が必須。combat pipeline (ADR-0030)
   側に「いま無敵か?」の概念がない。
3. **PvP toggle (per-player) が存在しない**。Wave 5n (parallel branch) の
   region-permission service は region 単位の PvP rule を持つが、`/pvp off`
   等の per-player override + safe-zone + spawn-protection を統合した
   `resolveCanDamage(attacker, victim, position)` の単一エントリポイントが
   ない。
4. **Spawn selector strategy が貧弱**。`SpawnRotationStrategy` (Wave 4.5)
   は random / faction-filter のみで、CycleRotation / FurthestFromEnemies
   等を加えるには sealed `SpawnPoint.Attacker(pos)` shape が邪魔
   (variant per faction で facing / world / 配列 index を保持できない)。

加えて Wave 5o の status-effect 側の隣接 gap として:

5. **Status-effect interaction matrix が存在しない**。 fire vs frost,
   wet vs shock 等の cross-effect 干渉を扱う resolver がなく、適用順だけで
   挙動が決まる。

これらは「死亡 → 再戦の loop に登場する 5 つの relationship」として 1 ADR で
本に書く方が後で読みやすい。

## Decision

### §1. SpawnPoint を flat data class に reshape (pre-release breaking change)

Pre-Wave-5o:

```kotlin
sealed interface SpawnPoint {
    val pos: Vec3
    data class Attacker(override val pos: Vec3) : SpawnPoint
    data class Defender(override val pos: Vec3) : SpawnPoint
    data class Lobby(override val pos: Vec3) : SpawnPoint
    data class Spectator(override val pos: Vec3) : SpawnPoint
}
```

Wave 5o:

```kotlin
data class SpawnPoint(
    val world: WorldId,
    val position: Vec3,
    val yaw: Float = 0.0f,
    val pitch: Float = 0.0f,
    val faction: SpawnFaction = SpawnFaction.LOBBY,
) { val pos: Vec3 get() = position }

enum class SpawnFaction { ATTACKER, DEFENDER, LOBBY, SPECTATOR }
```

理由: spawn-selector strategy が「faction で filter → strategy で 1 つ pick
→ teleport」となるので、faction が enum + position/yaw/pitch/world が flat
data として持てる方が自然。pre-release posture (CLAUDE.md §Pre-release
posture) により compat shim は書かず、YAML schema (`type:` →`faction:`/
`position:`/`yaw:`/`pitch:`/`world:`) も同時に reshape。`pos` getter のみ
レガシー名で残し、ほかの caller (`MatchSpawnUseCase`,
`MatchSpawnUseCaseTest`, `SpawnRotationStrategy`) は full migration。

### §2. ISpawnSelector + 4 つの SpawnStrategy

```kotlin
sealed interface SpawnStrategy {
    data object FirstAvailable : SpawnStrategy
    data object Random : SpawnStrategy
    data class CycleRotation(val previousSpawnIndex: Int) : SpawnStrategy
    data class FurthestFromEnemies(val safeRadius: Double) : SpawnStrategy
}

interface ISpawnSelector {
    fun selectFor(player: PlayerId, match: MatchId, stage: Stage, strategy: SpawnStrategy): SpawnPoint?
}
```

`DefaultSpawnSelector` 実装は (a) `IMatchTeamLookup` で player → TeamId
→ SpawnFaction を解決、(b) stage の spawn list を faction で filter、(c)
strategy で 1 つ pick、(d) `FurthestFromEnemies` の safeRadius を満たす
spawn が無くても **必ず最遠を返す** (Annihilation rule: never deny respawn)。

Konsist: `ISpawnSelector implementations must not import Bukkit Paper
(ADR-0081)` — `*SpawnSelector*.kt` in `match.application.service.*` は
`org.bukkit.*` / `io.papermc.*` import 不可。

### §3. RespawnUseCase + RespawnSpec + PreFillKit

```kotlin
data class RespawnSpec(
    val playerId: PlayerId, val matchId: MatchId?, val spawnPoint: SpawnPoint,
    val protectionSeconds: Int = 5, val initialHealth: Double = 20.0,
    val kit: PreFillKit? = null, val effects: List<StatusEffectInstance> = emptyList(),
)
data class PreFillKit(val items: List<Pair<EquipSlot, ItemId>>, val applyOnRespawn: Boolean = true)
class RespawnUseCase(...) {
    fun respawn(spec: RespawnSpec): Result<Unit, RespawnError>
}
```

Sequence (1) teleport → (2) `setHealth(initialHealth, HealthChangeSource.Respawn)`
→ (3) `grantProtection(protectionSeconds)` → (4) kit validation (unknown id
→ `RespawnError.UnknownKitItem`, applyOnRespawn=false で skip) → (5)
`statusEffectContainer?.applyBulk(effects)` → (6) `PlayerRespawned` publish。
Sealed `RespawnError` = `TeleportFailed | UnknownKitItem`。

### §4. ISpawnProtectionGateway + InMemorySpawnProtectionGateway

```kotlin
interface ISpawnProtectionGateway {
    fun grantProtection(playerId: PlayerId, durationSeconds: Int)
    fun isProtected(playerId: PlayerId): Boolean
    fun revokeProtection(playerId: PlayerId)
}
```

`InMemorySpawnProtectionGateway` は per-player expiry tick + scheduler 上の
one-shot revoke task、`grantProtection` の重複は max(remaining, new) で
extend (Annihilation の「保護中に /kill」edge case を最小コードで吸収)。
新 events: `PlayerProtectionGranted` / `PlayerProtectionExpired`。

### §5. IPlayerPvpStateService + PvpDisableReason

```kotlin
interface IPlayerPvpStateService {
    fun isEnabled(playerId: PlayerId): Boolean
    fun enable(playerId: PlayerId)
    fun disable(playerId: PlayerId, reason: PvpDisableReason)
    fun resolveCanDamage(attacker: PlayerId, victim: PlayerId, position: Vec3): Boolean
}
sealed interface PvpDisableReason {
    data object SpawnProtection : PvpDisableReason
    data object SafeZone : PvpDisableReason
    data object NewPlayer : PvpDisableReason
    data object AdminOverride : PvpDisableReason
}
```

`InMemoryPlayerPvpStateService` の `resolveCanDamage` 解決順は (a) victim
protected? → SpawnProtection、(b) safeZonePredicate(position)? → SafeZone、
(c) victim disabled? → 保存 reason、(d) attacker disabled? → 保存 reason。
拒否時は `PvpDamageBlocked` を publish。`safeZonePredicate` は Wave 5n
region-permission service が landしたら priority 100 で overlay。

### §6. Status-effect interaction matrix

```kotlin
sealed interface StatusInteraction {
    data class Cancel(val cancelledEffectId: StatusEffectId) : StatusInteraction
    data class Suppress(val suppressedEffectId: StatusEffectId, val durationTicks: Int) : StatusInteraction
    data class Amplify(val targetEffectId: StatusEffectId, val multiplier: Double) : StatusInteraction
    data object Pass : StatusInteraction
}
interface IStatusEffectInteractionRule {
    fun resolve(incoming: StatusEffectInstance, existing: Collection<StatusEffectInstance>): StatusInteraction
}
```

`YamlStatusEffectInteractionRule` は `content/balance/status-effect-interactions.yaml`
を読み、宣言順で first match wins。1 rule = 1 action (cancel / suppress /
amplify 排他) を construction-time に validate。missing / empty file は
"Pass for everything" にフォールバック (malformed は throw)。新 event:
`StatusEffectCancelled`。

### §7. Wiring

- `AsteraCorePlugin`: `ISpawnProtectionGateway` + `IPlayerPvpStateService`
  を priority 0 register。
- `AsteraCombatPlugin`: `IStatusEffectInteractionRule` を YAML から load して
  priority 0 register。
- `AsteraMatchPlugin`: `ISpawnSelector` (DefaultSpawnSelector) を priority
  0 register + `RespawnUseCase` を組み立て (世帯 deps 揃わない場合は
  graceful skip + log)。

## Consequences

### Positive
- Respawn loop が application 層で end-to-end test 可能 (Paper harness 不要)。
- PvP toggle が単一 entry point (`resolveCanDamage`) に集約され、combat
  pipeline 側が分岐を持たなくて済む。
- Spawn selector の strategy 拡張が `when (strategy)` の 1 branch 追加で済む。
- Status interaction が YAML で authoring 可能、code change 無し。
- `SpawnPoint` の facing / world フィールドが手に入ったので Wave 6 Paper
  teleport adapter が `Location(world, x, y, z, yaw, pitch)` を直接組める。

### Negative / Trade-offs
- `SpawnPoint` 破壊的変更で既存 stage YAML 全件書き換えが必要
  (content/stages/example-arena.yaml のみ既存、migration 済み)。
- `IPlayerPvpStateService.resolveCanDamage` は safe-zone predicate を
  Wave 5n が land するまで「常に false」で wire — interim では region 単位
  の PvP off が効かない (per-player の disable は機能する)。
- `FurthestFromEnemies` の `enemyPositionsProvider` を呼び出し側が threading
  する必要あり (Wave 5o ships empty default、Wave 5w 等の analytics が
  threading)。
- Wave 5w / Wave 5l 等の parallel branch が `SpawnPoint` 旧 shape を持つ
  ことが分かっており、merge 時に conflict が出る — orchestrator が手で
  resolve すべし (forward-only commit posture)。

## Cross-cutting

- ADR-0080 (Wave 5k Player physiology) — `IPlayerHealthGateway` を本 wave
  で活用、`HealthChangeSource.Respawn` variant は Wave 5k で既に用意済み。
- ADR-0027 (Wave 4.5 Match spawn) — `MatchSpawnUseCase` + `SpawnPoint` の
  原型は維持しつつ、新 selector が並列で使える形に reshape。
- ADR-0058 (Wave 5e Universal attribute container) — status-effect interaction
  の Amplify は `IAttributeContainer` の magnitude scaling を経由しない
  (effect-level の immediate scaling、container は consume 側の責務)。
- ADR-0022 (Multi-plugin layout) — `:plugin:match` が `IPlayerHealthGateway`
  (core port) + `IStatusEffectContainer` (combat port、compileOnly + soft
  resolve) に依存するのは matrix 通り。
