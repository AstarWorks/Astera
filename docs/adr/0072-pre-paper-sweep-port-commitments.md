---
status: Accepted
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0072: Pre-Paper-sweep port commitments — freeze outbound port shape before Wave 6

## Status

Accepted

## Context

Wave 6 (Paper Sweep) は Bukkit/Paper adapter 一括着地の転換点 (Wave 6a Combat
/ Wave 6b World+UI+Bridges)。Wave 6 を「**Paper impl だけ書く**」に純化する
ため、Core 側の outbound port commitment を Wave 5g で固定する。

監査 (2026-05-19) で 3 つの "未コミット" 発見:

1. **Shop stock 実バグ**: `ShopPurchaseUseCase` が `Limited.remaining` を
   読むだけで decrement していない。`OutOfStock` は到達可能だが定常状態
   では unreachable (在庫が永久に減らない)。
2. **Anomaly 5 TODO**: `ResolveAnomalyEffectUseCase` の StatusBurst /
   SpawnSurge / TimeWarp / GravityShift / FogOfWar が `// TODO(Wave 6)`
   no-op のまま (Wave 5c で domain land、wire 未完)。
3. **4 outbound port が未定義**: `IMatchTimerService` (TimeWarp 用),
   `IPlayerMovementGateway` (GravityShift 用), `IPlayerVisionGateway`
   (FogOfWar 用), `IStructurePaste` (匿名構造の paste/undo 用 — RTM の
   `StructureService` 相当、`ISchematicLoader` と分離: match-start geometry
   vs mid-match runtime)。

これらが揃わないまま Wave 6 に突入すると、Paper impl wave が「新 port 設計
+ Paper 接続 + content data path」の 3 軸を同時に進めることになり、設計
ミスが Paper-side コードに hard-code される。

**Vanilla Potion ↔ Astera StatusEffect の統一は domain に引き込まない**:
ユーザ確認済の決定として、Wave 5g の `IStatusEffectContainer` API 拡張は
`applyBulk` default method 1 件のみに留め、Vanilla Potion 観測 / 上書き
ロジックは Wave 6a の `PaperStatusEffectAdapter` 内 merge layer に閉じる
(ADR-0002 / ADR-0030 §8: domain は `org.bukkit.potion.*` 系の概念を mirror
enum 経由でも持たない)。

## Decision

Wave 5g で以下を一括 land (forward-only、後方互換取らず):

### 1. Shop stock atomic decrement (実バグ fix)

- 新 port `IShopStockRepository` (`currentStock / decrementStock / seed`)
  — spec (`IShopRegistry`) と stock を分離。spec immutable、stock mutable。
- 新 sealed error `ShopStockError` (`OutOfStock / UnknownOffer / Storage`)。
- Postgres impl は `SELECT ... FOR UPDATE` inside `transaction { }` で
  row-lock。同 offer の concurrent 購入 → ちょうど 1 件 success + 残り
  `OutOfStock`。`PostgresShopStockRepositoryIT` が Testcontainers で
  この race を pin する。
- InMemory impl (priority 0) は `ConcurrentHashMap` + atomic `compute`、
  Postgres overlay は priority 100 (ADR-0068 流儀、`AsteraPersistencePlugin`
  19 → 20 ports)。
- `ShopPurchaseUseCase`: `ledger.debit` 成功直後に
  `stockRepository.decrementStock(...)` を atomic に呼び、失敗時は
  `ledger.credit` で reverse + `ShopPurchaseError.DeliveryFailed` を返す
  (新 error variant 不要、既存 `DeliveryFailed` を流用 — buyer 視点では
  「届けられなかった」で意味的に同一)。
- `ShopLoaderService` 起動時に `ShopOffer.stock is Limited` を idempotent
  `seed`。再起動で既存 row を上書きしない (insertIgnore)。
- V19 Flyway migration: `shop_stock(shop_id, offer_id, remaining INT
  CHECK >= 0, PRIMARY KEY(shop_id, offer_id))`。
- 既存 i18n `astera.economy.shop.out_of_stock` 流用 (en/ja 両方既存)。

### 2. `IStatusEffectContainer.applyBulk` default method

```kotlin
public fun applyBulk(instances: Collection<StatusEffectInstance>): Unit =
    instances.forEach { apply(it) }
```

- 既存 Fake / 既存 caller 0 修正 (default impl で transparent extend)。
- Wave 6a `PaperStatusEffectAdapter` は batched packet flush のために
  override 可。
- **これが Wave 5g の唯一の API delta**。Vanilla Potion 関連の新 type /
  method は domain に追加しない (上述 Context 末尾の方針)。

### 3. Anomaly 5 TODO 解消 (Fake-level wire)

- **StatusBurst** (partial wire): `IStatusEffectContainer.applyBulk(emptyList())`
  で port edge を live にする。descriptor → concrete `StatusEffectInstance`
  list の resolver は Wave 6a の content-driven mapping 待ち。
  unit test は no-op contract を pin (active effects 空 + generator 不変)。
- **SpawnSurge**: `PlayerMatchAttributes.RESPAWN_RATE_MULTIPLIER =
  AttributeKey("player_match.respawn_rate_multiplier")` を
  `IPlayerAttributeContainerStore.containerFor(playerId)` に modifier
  として書く。sourceTag = `"anomaly:<id>"` で `expire` 時 sweep。
- **TimeWarp**: `IMatchTimerService.shiftPhaseTimer(matchId, deltaTicks)`
  — `durationTicks × modifierValue` で signed delta 計算、
  `coerceIn(Int.MIN..MAX)` で clamp。one-shot (expire-side undo なし)。
- **GravityShift**: `IPlayerMovementGateway.setGravityMultiplier(playerId,
  multiplier, ttlTicks)` — gateway 側で TTL 経過時に自動 reset。
- **FogOfWar**: `IPlayerVisionGateway.setFogRadius(playerId, radius,
  ttlTicks)` — 同上 TTL 自動 reset、`radius < 4` は crash hazard なので
  clamp。**Wave 6a Paper adapter が BLINDNESS potion + render distance
  shrink のどちらをするか (or 両方) は Paper-side 決定** (domain は
  「fog 半径を縮める」だけを知る)。

`ResolveAnomalyEffectUseCase` constructor は 7 引数に膨らむ
(`generators` + 5 nullable collaborators + `matchPlayerListProvider`
lambda)。すべて nullable で tests は必要な subset だけ wire 可能。

### 4. 4 outbound port + Fake (Wave 5g land)

| Port | Wave 6 Paper impl | Wave 5g land |
|---|---|---|
| `IMatchTimerService.shiftPhaseTimer(matchId, deltaTicks): Result<Unit, TimerShiftError>` | `PaperMatchTimerService` (Wave 6a — 既存 `PhaseTimerUseCase` に inject) | `FakeMatchTimerService` (`plugin/match/src/test/`) |
| `IPlayerMovementGateway.setGravityMultiplier / resetGravity` | `PaperPlayerMovementGateway` (Wave 6a — Bukkit `Player.setGravity` + scheduler reset) | `FakePlayerMovementGateway` (`plugin/test-fixtures/`) |
| `IPlayerVisionGateway.setFogRadius / resetFog` | `PaperPlayerVisionGateway` (Wave 6a — 戦略は Paper 側決定) | `FakePlayerVisionGateway` (`plugin/test-fixtures/`) |
| `IStructurePaste.paste / undo` (`Result<PasteHandle, StructurePasteError>`) | `PaperStructurePaste` (Wave 6b — FAWE adapter、ADR-0015 D path で optional) | `FakeStructurePaste` (`plugin/test-fixtures/`、paste→undo round-trip) |

production 側に no-op InMemory 実装は登録しない。consuming use case
(`ResolveAnomalyEffectUseCase`) は nullable 引数で受け取り、未登録時は
no-op 分岐。Konsist allow-list rule (下記) が Paper impl 不在を Wave 6
まで許可。

### 5. Konsist enforcement

新 rule 2 件 (baseline 28 → 30):

1. **`Wave 6 Paper impl allow-list (ADR-0072)`**: 上記 4 port の FQN を
   `allowedMissingPaperImpl: Set<String>` として宣言。2 軸 assert:
   - (a) 各 entry が実在する interface に解決する (rename/delete drift
     fail-loud)
   - (b) `*.adapter.paper.*` 配下に該当 port を implement する class が
     存在しないこと (positive shrink: Paper impl が land した瞬間に
     allow-set entry の削除を強制)
2. **`IShopStockRepository requires priority 100 Postgres overlay (Wave 5g)`**:
   `PostgresShopStockRepository` class が `com.astarworks.astera.persistence.repository.*`
   に存在する場合、`IShopStockRepository` を implement することを Konsist
   で pin。

## Consequences

### Positive

- **Wave 6 純化**: Wave 6a/b は "wire Paper adapter to existing port"
  だけになる。新 port 設計 / signature 議論を Wave 6 に持ち込まない。
- **Phase 1 PoC closing forward-only 方針 (CLAUDE.md §Pre-release posture)
  と整合**: dual-decoder / fallback shim 一切なし。`ShopPurchaseUseCase`
  の constructor 引数を一方的に追加、既存 caller を破壊的更新。
- **Anomaly 5 variant の "wire は完成、payload だけ Wave 6a で完成" 構造**:
  Wave 6a で content YAML から `StatusEffectInstance` collection を構築
  する resolver を足すだけで StatusBurst が live になる。port edge の
  compile-time guarantee が既に成立しているので、Wave 6a での edge 追加
  忘れが Konsist + コンパイラで catch される。
- **Konsist allow-list の "shrink as completion" 設計**: ADR-0072
  allow-set から entry が 1 つ消える = Wave 6 が 1 step 進む。進捗
  visibility が test に組み込まれる。
- **domain の Bukkit-free 性を強化**: Vanilla Potion 概念を domain に
  引き込まないことで、ADR-0002 / ADR-0030 §8 の不変条件を Wave 6a で
  *再確認* するための余地を残した。Wave 6a で「FogOfWar = BLINDNESS at
  amplifier 0」の選択は Paper adapter ファイルに閉じ、`grep` 一発で
  どの Paper-strategy を取ったか追跡できる。

### Negative / Trade-offs

- **StatusBurst の partial wire**: `applyBulk(emptyList())` は意図的に
  no-op だが、log line だけ見ると "fired but did nothing" になる。
  Wave 6a で content path が land するまで誤解を招く可能性 (KDoc +
  test name で明示)。
- **4 outbound port が Paper impl なし**で Wave 5g を抜ける。Konsist
  allow-list が visibility を確保するが、impl 完成は別 wave の宿題。
- **`ResolveAnomalyEffectUseCase` の constructor 引数 7 個**: nullable
  defaults でも複雑度は増えた。test 側は必要な subset だけ wire で
  対処、Wave 6a で必要に応じて split を検討。

### Mitigations

- Konsist allow-list rule = mechanical positive-shrink monitoring。
- `ResolveAnomalyEffectUseCaseTest` + `AnomalyScenarioTest` が各 variant
  の Fake-level wiring contract を pin、Wave 6a Paper 実装で regression
  baseline。
- ADR-0071 (Shop Access Surfaces) と本 ADR は同じ Wave 5g commitment
  package として review される (both Wave 5g land、roadmap §"ADR
  allocation per wave" に並記)。

## Alternatives Considered

- **`IStatusEffectContainer` を Vanilla 対応に拡張** (`VanillaPotionType`
  enum + `applyVanilla` / `hasVanilla` / `removeVanilla` 追加): 検討の上
  **却下**。理由: (1) `VanillaPotionType` は実質 `org.bukkit.potion.PotionEffectType`
  の 1:1 mirror で、domain が Minecraft-specific 概念を持つことになる
  (ADR-0002 違反の精神)。(2) StatusBurst / FogOfWar で「POISON 適用」
  「BLINDNESS 適用」を domain が決めることは impl strategy の前倒し
  (Paper adapter が BLINDNESS でなく render distance 操作を選ぶかも)。
  (3) ユーザ確認 (2026-05-19) で「applyBulk default method 追加のみ。
  Vanilla 統一は Wave 6a Paper adapter merge layer に閉じる」が明示
  された。
- **4 gateway port を Wave 6a に遅延**: Paper impl と新 port 設計を同時
  進行で wave がブロート。Wave 6a を pure-impl wave に保つために 5g で
  port land を選択。
- **One mega-port `IPlayerEnvironmentGateway`** (gravity + vision + fog):
  3 つの concern は独立した Bukkit API を叩くので、合体させると
  Paper adapter が分岐 hell に。allow-list rule も粒度が粗くなる。
- **`IStructurePaste` を `ISchematicLoader` の subtype に**: lifetime が
  違う (match-start static placement vs mid-match ephemeral with undo)。
  継承すると domain branch が混入する。
- **`applyBulk` を Wave 6a に遅延**: Wave 6a Paper adapter が batched
  apply を実装する時、port に entry がないと side channel API を生やす
  ことになる。default method の forward-compat cost はゼロなので 5g で
  land。

## References

- 関連 ADR:
  - [[adr/0002-mc-as-edge]] — domain に Bukkit 漏らさない原則
  - [[adr/0022-multi-plugin-layout]] — sibling-isolation matrix
  - [[adr/0027-phase-1-poc-closing-scope]] — forward-only 方針
  - [[adr/0030-combat-depth-and-damage-event-decomposition]] §8 — status
    effect surface rules
  - [[adr/0032-game-mode-framework-and-generator-anomaly]] — anomaly
    framework Wave 5c
  - [[adr/0058-universal-attribute-container]] — `PlayerMatchAttributes`
    pattern justification
  - [[adr/0068-cross-server-persistence-boundary]] — priority 100 Postgres
    overlay convention
  - [[adr/0071-shop-access-surfaces]] — sibling Wave 5g ADR
- 関連 docs:
  - `docs/RTM-PARITY-TRACKER.md` — Cat 3 (StructureService), Cat 5
    (Anomaly 5 variants), Cat 6 (shop stock + access surfaces)
  - `docs/IMPLEMENTATION-ROADMAP.md` — Wave 5g entry (Tier 6.5)
- Code:
  - `plugin/combat/.../application/port/outbound/IStatusEffectContainer.kt`
    (`applyBulk` default method)
  - `plugin/match/.../application/port/outbound/IMatchTimerService.kt`
  - `plugin/core/.../application/port/outbound/IPlayerMovementGateway.kt`
  - `plugin/core/.../application/port/outbound/IPlayerVisionGateway.kt`
  - `plugin/core/.../application/port/outbound/IStructurePaste.kt`
  - `plugin/core/.../application/port/outbound/IShopStockRepository.kt`
  - `plugin/match/.../application/usecase/anomaly/ResolveAnomalyEffectUseCase.kt`
  - `tools/architecture-test/.../ArchitectureTest.kt` (Wave 6 allow-list rule)
