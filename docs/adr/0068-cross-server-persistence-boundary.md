---
status: Accepted
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0068: Cross-server persistence boundary — DB-backed state, ephemeral pods

## Status

Accepted

## Context

Astera は次の 2 つの運用前提を持つ:

1. **Lobby ↔ Game pod の cross-server traversal**: Phase 4 で Velocity proxy を導入すると、player は lobby pod と複数の game pod を行き来する。state が pod-local だと毎 traversal で消える。
2. **k8s pod は ephemeral cattle**: Wave _ops KEDA ScaledObject が active match 0 で game pod を 0 replica まで scale-down する。pod kill / replace は日常茶飯事。さらに user spec で「永続化 Volume を信用したくない、使い捨てがバンバンできるようにしたい」と明示された。

ところが Wave 5e 完了時点で 4 つの state holder が in-memory only:

| Port | Wave 5e port doc 主張 | Production 影響 |
|---|---|---|
| `IPlayerSessionRepository` | "Persistence is not required — sessions are reconstructed at `PlayerJoined`" | サーバ移動中の Loading 状態が失われる、action-lock 効かない |
| `IFriendshipRepository` | 無言 (Postgres 化を意図していたが Wave 5e で遅延) | サーバ再起動で friend list 消失 |
| `IPartyRepository` | "Parties are runtime-scoped and disband on server restart" | サーバ移動 / pod kill で party 散解 |
| `IRewardBridge` | "Wave 5e provides an in-memory single-JVM impl. Wave 6 swaps to Redis pub/sub" | reward bundle が deliver 前に pod kill されると永久喪失 |

これらの port doc の主張は **Wave 5e 時点での design intent** で、(a) cross-server traversal を「Phase 4 機能」と位置付けていた、(b) pod ephemeral 前提を強く意識していなかった。本 ADR でこの方針を覆す。

## Decision

**全 state holder port は Postgres-backed (priority 100) を production 必須とする**。InMemory impl は dev/test fallback (priority 0) に降格。

### 4 port を Postgres 化 (Wave _persist-1 で実装)

| Port | Migration | Storage 方針 |
|---|---|---|
| `IPlayerSessionRepository` | V16 `player_sessions` | JSONB single row per player; SessionState + currentMatch |
| `IFriendshipRepository` | V17 `friendships` | 列カラム multi-row; canonical-pair (player_low < player_high) で対の uniqueness 保証 |
| `IPartyRepository` | V18 `parties` + `party_members` | 2-table FK; members atomic replace on upsert |
| `IRewardBridge` | V15 `reward_outbox` | Outbox pattern; `delivered_at IS NULL` で未配信、`consumeForPlayer` で atomic SELECT-then-UPDATE |

V11-V13 は Wave 5f で `currency_issuance_log` / `marketplace_listings` / `creator_revenue_records` に使用済、V14 は Wave 5e で `player_attributes` に使用済。Session / Friendship / Party は V16/V17/V18 に retreat した (Flyway は monotonic 順序のみ求める、contiguous 不要)。

### Ephemeral by design (persist しない state を明示列挙)

以下は **per-server / per-match runtime state**、永続化不要。pod が死んでも問題ない:

- HUD ports: `IBossBarManager`, `IScoreboardManager`, `ITabList` — 表示の状態は connected player にしか意味がない
- Per-match world: `IBlockStateGateway`, `ISchematicLoader` — マッチ毎に Schematic から再生成、マッチ終了で破棄
- Config systems: `IItemContents`, `IGuiContents`, `IShopRegistry`, `IReloadableContentsRegistry` — content/*.yaml を起動時にロード、hot-reload で更新

### Pod-local Volume は信用しない

- `deploy/minecraft/game/game-deployment.yaml` の `hostPath: /srv/astera/game` は **trusted persistent storage ではない**。MC world ファイルは Schematic から再生成可能なら良い (per-match world は ephemeral)、plugin runtime cache (config 展開、JAR 抽出 dir 等) も emptyDir 相当として扱う
- 正式な `hostPath → emptyDir` 切り替えは別 wave (`Wave _stateless-pod`) で処理

### Konsist rule (Phase 2 で land)

"production registered InMemory landmine detector": `*Plugin.onEnable()` で priority=100 register 対象として ADR 列挙した port (Session / Friendship / Party / Reward + 既存 12 件) が、対応 PostgresXxxRepository.kt を持つことを check する Konsist rule。Wave _persist-1 では rule の **text のみ** ADR に書き、実 enforcement は別 wave。

## Consequences

### Positive

- **Cross-server で player state survives**: Velocity proxy 導入時に persistence layer の書き換えが要らない。Phase 4 の Velocity wave で Redis pub/sub (notify layer) を追加するだけで multi-server reward が動く
- **Pod kill / replace に robust**: KEDA scale-to-zero、k8s rolling update、node drain で state を失わない
- **Outbox pattern が forward-compatible**: Phase 4 で Redis pub/sub を notify に追加しても、`reward_outbox` table は source-of-truth として残る。Redis が落ちても消える心配なし、Redis 復活で再 deliver 可能
- **Wave 6 (Paper Sweep) 直前の foundational fix**: domain + persistence backbone が固まった状態で Paper impl 着手するのが正しい順序

### Negative / Trade-offs

- **新規 state holder port 追加時に Postgres impl が必須**: boilerplate 1.5x。table + JSON wrapper + repository + migration + IT = 5 ファイル
- **Postgres 不在で動かなくなる範囲が広がる**: `ASTERA_PG_URL` 不在時は in-memory fallback で動くが、production deploy では Postgres available を必須前提に
- **outbox の row 増加 (delivered な row が残る)**: Phase 4 で `delivered_at < now() - INTERVAL '7 days'` の row を vacuum する CronJob を追加。Phase 1 PoC では絶対量が少ないので放置

### Mitigations

- **boilerplate 削減**: ADR-0060 (DataDriven Contents substrate) と同じ思想で persistence substrate を将来検討 (Phase 2-3)。今は手書き copy-paste で OK
- **dev fallback の動作確認**: `ASTERA_PG_URL=` 不在で全機能が in-memory 動作することを Konsist + smoke で確認 (Wave _persist-1 で gradle check 経由で確認済)
- **outbox vacuum cron**: Phase 4 で `delete_old_delivered_rewards.yaml` CronJob を追加。Wave _persist-1 では schema のみ用意 (`delivered_at` index で fast scan 可)

## Alternatives Considered

- **InMemory のまま放置 + port doc の "ephemeral by design" 主張を維持**: cross-server traversal で UX が破綻 (party 散解、reward 喪失)、user spec 「鯖をまたぐ DB-backed」と矛盾。**却下**
- **Redis を Phase 1 から導入**: Redis を notify + cache + 永続化 layer の三役にして Postgres を skip。Redis 喪失で全 state が消えるリスクが Phase 1 PoC で許容できない。Redis cluster 運用も Phase 1 で立てるのは複雑。**却下** (Phase 4 で Redis を notify layer として追加するが、source-of-truth は Postgres)
- **Hazelcast / Apache Ignite で in-memory data grid**: pod 跨ぎの shared in-memory state。複雑度過大、Phase 1 で正当化できない。**却下**
- **Postgres + Outbox pattern (本 ADR の採用案)**: 単純、既存 Postgres infrastructure (V1-V14) の延長。Phase 4 で Redis 追加時も outbox table は残せる。**採用**

## References

- 関連 ADR:
  - [[adr/0006-gitops-with-argocd-kustomize]] — k8s + ArgoCD 運用前提
  - [[adr/0020-persistence-architecture]] — Postgres + Exposed + HikariCP + Flyway の前提パターン
  - [[adr/0014-jsonb-usage-policy]] — JSONB vs columnar の選択指針 (bounded read-whole aggregate → JSONB)
  - [[adr/0022-multi-plugin-portable-architecture]] — Persistence plugin が priority 100 overlay する設計
  - [[adr/0027-phase-1-poc-closing-scope]] — Phase 1 PoC 完了基準
  - [[adr/0034-session-social-tutorial-enderchest-reward-bridge-inventory-snapshot]] — Wave 5e charter (本 ADR で部分的に上書き)
- 関連 docs:
  - `plugin/persistence/src/main/kotlin/.../platform/AsteraPersistencePlugin.kt` — registration overlay 実装
  - `plugin/persistence/src/main/resources/db/migration/V16__player_sessions.sql` / `V17__friendships.sql` / `V18__parties.sql` / `V15__reward_outbox.sql` — 本 wave で land
  - `tools/architecture-test/src/test/kotlin/.../ArchitectureTest.kt` — Konsist rule 候補地 (Phase 2 で landmine detector 追加)
- 外部リンク:
  - [Microservices Outbox pattern (Chris Richardson)](https://microservices.io/patterns/data/transactional-outbox.html) — outbox semantic の原典
