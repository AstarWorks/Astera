---
status: Accepted
date: 2026-05-21
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0084: Cross-server proxy commitment — IProxyMessenger + IServerTransferGateway + ProxyEvent

## Status

Accepted

## Context

Astera は Wave 6 で実 BungeeCord / Velocity proxy 接続を land する予定だが、
複数の use-case (matchmaker → game-server hop, lobby return, admin
`/transfer`, party-follow) は Phase 1 (Wave 5r 時点) で「**プロキシ越し**」を
前提に設計が進んでいる。

特に Wave _persist-1 (ADR-0068) で cross-server persistence boundary が
sealed され、player session / friendship / party / outbox が「**どの Astera
node からでも同じ Postgres state を読み書きする**」前提になった。残るのは
state transfer ではなく **player transport** — proxy 越しの「player A を
backend B に移す」操作と、関連するメッセージング channel。

選択肢:

1. **Wave 6b まで遅延**: proxy abstraction は Velocity adapter と一緒に
   land。問題: Wave 5r 以降の use-case が proxy port をモックで参照できず、
   matchmaker (Wave 5w 系) / lobby HUD (Wave 5l 系) が直接 Bukkit
   `ConnectionRequestBuilder` を握ることになる → Wave 6b で domain rewrite
   が必要。
2. **Phase 1 で port commitment**: vendor-neutral な `IProxyMessenger` +
   `IServerTransferGateway` + sealed proxy events を Core domain/application
   に land、InMemory fakes を priority 0 で publish。Wave 6b は **Velocity
   binding だけ書く**。
3. **Velocity SDK を Core に取り込む**: domain で `com.velocitypowered.api.*`
   を直接握る。一見ラクだが ADR-0001 / ADR-0002 違反 (Minecraft is an edge
   + vendor-neutral adapter layer)、Konsist が即 build-fail。

(2) が一貫している:
- Wave 5t (ADR-0086) の `IPermissionService` が proxy transfer の権限 gate
  として既に存在。Wave 5r は new gate を作らず、`astera.transfer.<server>`
  node を check するだけで済む。
- Wave _persist-1 の `IPlayerSessionRepository` (Postgres-backed) は既に
  cross-node sharable。proxy 経由で player が移っても session state は
  自動的に target server で観測される (DB shared)。
- `IDomainEventBus` に流す proxy 系 event (`PlayerTransferRequested` /
  `PlayerArrivedOnServer` / `ProxyHeartbeat`) はすでに in-process broker と
  Wave 6 で land 予定の Redis-backed broker の両方を vendor-neutral に
  受け入れる shape。

## Decision

**Wave 5r で以下を Core に land** (forward-only、後方互換取らず):

### 1. `core.domain.proxy.*` (vendor-neutral vocabulary)

- `ServerName(value: String)` — operator 制御の backend 名 (non-blank だけ
  enforce、shape は proxy 側に委譲)。
- `ProxyChannel(name: String)` — lowercase / dot kebab。built-in:
  `astera.transfer` / `astera.heartbeat` / `astera.match.found`。
- `ProxyMessage(fromServer, channel, payload: ByteArray)` — content-equality
  override (default `ByteArray.equals` は identity)。payload sub-protocol は
  上位レイヤに委譲 (sub-channel routing / framing / serializer choice)。
- sealed `ProxyError`: `NoProxyConnection` / `UnknownTargetServer` /
  `SerializationFailure`。
- sealed `TransferReason`: `MatchFound` / `LobbyReturn` / `AdminMove` /
  `CustomReason(MessageKey)`。
- sealed `TransferWhen`: `Immediately` / `OnNextLogin(timeoutSeconds)` /
  `AfterCurrentMatch(MatchId)`。
- sealed `TransferError`: `ProxyUnavailable(cause)` / `PermissionDenied(node)`
  / `TargetServerOffline(server)` / `QueueFull`。

各 error variant は `MessageKey` を持つ。`Throwable` は一切露出しない
(Konsist `application layers must not import raw JVM throwable types`)。

### 2. `core.application.port.outbound.IProxyMessenger`

```kotlin
public interface IProxyMessenger {
    public fun sendToServer(playerId: PlayerId, targetServer: ServerName): Result<Unit, ProxyError>
    public fun sendMessage(targetServer: ServerName, channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError>
    public fun broadcast(channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError>
    public fun subscribe(channel: ProxyChannel, handler: (ProxyMessage) -> Unit): Subscription
}
```

raw byte-channel messaging port。Velocity / BungeeCord plugin channel と
1:1 写像する。

### 3. `core.application.port.outbound.IServerTransferGateway`

```kotlin
public interface IServerTransferGateway {
    public fun transfer(playerId: PlayerId, targetServer: ServerName, transferReason: TransferReason): Result<Unit, TransferError>
    public fun queueTransfer(playerId: PlayerId, targetServer: ServerName, when_: TransferWhen, transferReason: TransferReason): Result<Unit, TransferError>
}
```

`IProxyMessenger` の **上位** port。追加責務:
- `astera.transfer.<targetServer>` permission を `IPermissionService`
  (Wave 5t ADR-0086) で check。fail → `TransferError.PermissionDenied`。
- transfer 直前に `PlayerTransferRequested` domain event を publish
  (HUD / audit / inventory snapshot listener が反応できる)。
- `queueTransfer` で deferred スケジュール (`OnNextLogin` /
  `AfterCurrentMatch`) を hold。in-memory 実装は internal queue + bus
  subscription (`PlayerJoinedServer` / `MatchEnded`) で flush。

### 4. ProxyEvent variants (`core.domain.event.*`)

- `PlayerTransferRequested(playerId, targetServer, transferReason, …)`
- `PlayerArrivedOnServer(playerId, fromServer, targetServer, …)`
- `ProxyHeartbeat(localServer, knownPeers, …)`

いずれも既存 `DomainEvent` interface に乗る (sealed ではない — Wave 6
で Velocity adapter が同 interface に events を足す cross-plugin shape)。

### 5. InMemory defaults (priority 0)

- `InMemoryProxyMessenger(localServer, neighbors)` — self-loopback +
  test-server topology + transfer recording。`publishInbound` テストフック
  で domain test が proxy 経由 message flow を全部 reproduce 可能。
- `InMemoryServerTransferGateway(messenger, eventBus, permissionService?, queueCapacity)` —
  3 種類の deferred queue (immediate / on-login / on-match-end) + 1024
  デフォルト cap + `permissionNodeFor(server)` helper。

Wave 6b で `VelocityProxyMessenger` + `VelocityServerTransferGateway` が
priority 100 で overlay (ADR-0068 流儀)。

### 6. Konsist rule

`core.domain.proxy.*` must not import `com.velocitypowered.api.*` /
`net.md_5.bungee.api.*`。違反は build-fail。Velocity / BungeeCord SDK は
Wave 6b の `core.adapter.paper.proxy.*` (新規) に閉じる。

### 7. AsteraCorePlugin wiring

`onEnable` で permission service の直後に proxy + transfer gateway を
register (priority 0)。SERVICE_NAMES に追加して startup log で見える。

## Consequences

### Positive

- Wave 5r 以降の use-case (matchmaker / lobby HUD / party-follow / admin
  `/transfer`) は domain-level の `IServerTransferGateway` を握って書ける。
  Bukkit `ConnectionRequestBuilder` は domain には漏れない。
- proxy transfer の **permission gate が Phase 1 で hard-coded**:
  `astera.transfer.<server>` node 経由で operator が制御。Wave 6b で
  Velocity adapter を land しても権限ロジックの書き換えは不要。
- deferred queue (`OnNextLogin` / `AfterCurrentMatch`) は in-process bus
  subscription で fire するので、Wave 5r 単独で end-to-end テスト可能
  (`InMemoryServerTransferGatewayTest` が PlayerJoinedServer / MatchEnded
  に反応する fire を pin)。
- ADR-0068 の "cross-server state は Postgres-backed" 方針と直交。proxy は
  player の物理移動を扱い、state は DB shared — 二重 ownership 無し。

### Negative / Trade-offs

- InMemory gateway は in-process bus に直結なので、複数 Astera node を
  同 JVM で立ち上げる test scenario では gateway 同士が互いの bus を
  共有しない。Wave 6b の Redis-backed eventBus が出るまで multi-node
  domain test は擬似 (single-node simulation) に限定される。
- `ProxyMessage.payload: ByteArray` の sub-protocol を Phase 1 では決めない。
  Wave 5l (matchmaker) / Wave 5w (lobby HUD) で初めて使う段階で sub-channel
  framing の ADR を別途切る必要がある。
- queue capacity 1024 は magic number。production load 不明なので、
  Wave 6b の Velocity adapter が land する時点で再評価。

### Mitigations

- multi-node test ギャップは Wave 6b の adapter テストで Testcontainers
  + Redis broker で埋める計画 (本 ADR では deferred)。
- sub-protocol gap は Wave 5l/5w ハンドオフ doc で「最初の sub-channel を
  定義する ADR」を明示的に要求。
- queue cap 1024 はバックプレッシャ用の logged warning を Wave 6b adapter
  で追加 (`QueueFull` 連発を operator が観測できるよう)。

## Alternatives Considered

- **Wave 6b まで遅延 (alt-1)**: 棄却。matchmaker / lobby HUD の domain
  shape が Velocity SDK に直接結合する → Wave 6b で全部 rewrite。
- **Velocity SDK を Core に取り込む (alt-3)**: 棄却。ADR-0001 / ADR-0002
  違反。Konsist が即 build-fail。
- **ProxyEvent を sealed にする**: 棄却。Wave 6b で Velocity adapter が
  新しい event variant (`ProxyShutdownPending` 等) を加える際に Core を
  modify せざるを得なくなる。`DomainEvent` interface 経由の open hierarchy
  を選択 (既存 `MatchStarted` / `BlockBroken` etc. と同じ shape)。
- **queueTransfer に permission check を入れる**: 棄却。queue 時 → fire 時
  に role が変わる可能性があり、fire 時 check が正しい (`transfer()` 経由で
  自然に gate される)。

## References

- 関連 ADR: [[adr/0001-vertical-slice-architecture]] (Clean Architecture)
- 関連 ADR: [[adr/0002-paper-adapter-layer]] (vendor-neutral MC adapter)
- 関連 ADR: [[adr/0022-multi-plugin-portable-architecture]] (plugin layout)
- 関連 ADR: [[adr/0068-cross-server-persistence-boundary]] (Wave _persist-1)
- 関連 ADR: [[adr/0086-admin-ops-permission-presence-feedback]] (Wave 5t)
- 関連 docs: `.claude/plans/wave-5g-core-hardening-giggly-sunset.md` §"Wave 5r"
- Wave 6b で land 予定: `VelocityProxyMessenger` / `VelocityServerTransferGateway`
  (`core.adapter.paper.proxy.*`)
