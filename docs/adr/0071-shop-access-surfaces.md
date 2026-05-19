---
status: Accepted
date: 2026-05-19
deciders: ryuzu
supersedes: null
superseded_by: null
---

# ADR-0071: Shop access surfaces — converge every entry path onto one routed intent

## Status

Accepted

## Context

Wave 5g pre-Paper-sweep audit (`/home/node/.claude/plans/wave-5g-handoff.md`) のうち、Shop の **access surfaces** (NPC vendor / 外部 EC reference / chat alias) について domain side が何もコミットしていない問題を扱う。

RTM `model/issue/#4` で **WeaponMechanics 内蔵の NPC-as-vendor を decline** したが、代替設計を ADR に記録していなかった。リポ全体で `npc` / `villager` / `trader` / `merchant` の hit はコメント 2 件のみ。`/astera-economy shop open <id>` の slash command 1 経路しか持たない。

Phase 4 (Wave 8b 系) で Web admin REST + Discord bot + cross-server chat router が乗ってくると:

- `/shop open` slash command
- Villager / ArmorStand / Display entity を右クリック (NPC vendor)
- `!shop my_shop` のような chat alias
- Web admin / Discord / Velocity proxy からの `external open` トリガ

— の 4 surfaces が同時着地する。各 surface が独自に `ShopBrowseUseCase` を直接呼ぶ実装になると、auth check、analytics hook、access-policy 拡張のすべてが 4 倍化する。

## Decision

**全 shop-open path を `IShopAccessIntent` sealed 4 variant に converge し、`ShopAccessIntentRouter` 単一の routing point を経由して `ShopBrowseUseCase` に到達させる**。

### `IShopAccessIntent` 4 variant (Wave 5g land)

```kotlin
sealed interface IShopAccessIntent {
    val playerId: PlayerId
    data class OpenFromCommand(playerId, shopId)        // slash command
    data class OpenFromNpc(playerId, vendorId)          // NPC right-click
    data class OpenFromChat(playerId, alias)            // chat alias (Phase 4)
    data class OpenFromExternal(playerId, externalRef)  // Web/Discord/Velocity
}
```

### Companion outbound ports

- `IShopVendor` (`bind / lookup / unbind / all`) — NPC entity ↔ ShopId binding。`VendorId` value class は opaque (Paper-side adapter は entity UUID / NPC handle を埋める)。Paper impls (`PaperVillagerShopVendor`, optional `CitizensShopVendor`) は Phase 4 Wave 8b。Wave 5g default = `InMemoryShopVendorBinding` (priority 0、map-backed)。
- `IShopReferenceQuery` (`resolvePublic / allPublic`) — read-only public projection (`PublicShopReference` / `PublicShopOffer`)。**stock counts は意図的に projection に含めない** (low-stock arbitrage scraping を防ぐ)。Phase 4 REST `/api/shop/<id>/offers` は本 port を wrap、`IShopRegistry` を直接触らない。Wave 5g default = `DefaultShopReferenceQuery` (`IShopRegistry` wrap + projection)。

### Routing convergence

`ShopAccessIntentRouter` が:

- `OpenFromCommand(playerId, shopId)` → 直接 `ShopBrowseUseCase.execute(playerId, shopId)`
- `OpenFromNpc(playerId, vendorId)` → `IShopVendor.lookup(vendorId)` で shopId 解決 → browse
- `OpenFromChat(playerId, alias)` → `IShopReferenceQuery.allPublic()` で alias match → browse (Phase 4 で `IChatAliasResolver` 拡張可)
- `OpenFromExternal(playerId, externalRef)` → `ShopId(externalRef)` 構築 → browse (Phase 4 で external authentication 拡張可)

返り値: `Result<Unit, ShopAccessError>` sealed (`UnknownVendor` / `UnknownAlias` / `InvalidExternalRef` / `BrowseFailed(ShopBrowseError)`)。

### Wave 5g land スコープ

- 3 新 port + sealed intent + Router + 2 Fake impl + 4 variant routing test
- ADR-0071 (本 ADR) で **Phase 4 Wave 8b commitment** を明示 (NPC vendor / external EC / chat alias 実装は遅延、port shape は固定)

## Consequences

### 良い影響

- **scaling**: 新 surface を追加する際は `IShopAccessIntent` に sealed variant を 1 つ追加するだけ。`ShopBrowseUseCase` を直接呼ぶ実装が散逸しない
- **public/private 分離**: `IShopReferenceQuery` の projection 通過が *唯一* の external read path。stock 漏洩を 1 個所で防ぐ
- **NPC vendor の柔軟性**: `IShopVendor.bind(vendorId, shopId)` は Villager / ArmorStand / Display / Citizens NPC の choice を Paper-side adapter に押し付ける。domain は entity の "種類" を知らない (ADR-0002)
- **Phase 4 commitment の trace**: RTM #4 → ADR-0071 → Wave 8b の trace が ADR + Konsist allow-list comment に残る。Wave 8b 着手で必ず引き当て

### 痛い影響

- Wave 5g 時点で 3 port は **port-only** (Paper impl なし)。RTM 上 🔌 marker 必要
- Phase 4 Web admin が REST `/api/shop/<id>/offers` を実装する時、`IShopReferenceQuery` の projection 仕様 (公開する field) を別 ADR で拡張する必要 (`available: Boolean` は粒度が粗い — Wave 8b で `priceLabel / availability_window` 等が問われる可能性)
- chat alias resolver / external authentication は Wave 5g では minimal (`allPublic().match(alias)` の linear scan)。Phase 4 で別実装に置き換え

### 中立

- `IShopVendor` の `VendorId` opaque value class は Bukkit Entity UUID を埋める想定だが、Citizens NPC handle は別 namespace。Paper-side adapter で `"villager:<uuid>"` / `"citizens:<id>"` のような prefix 規約を定める (Wave 8b で確定)

## Related

- ADR-0027 (Wave 4.5 Shop spec / ShopBrowseUseCase / ShopPurchaseUseCase)
- ADR-0068 (Cross-server persistence boundary)
- ADR-0072 (Pre-Paper-sweep Port Commitments) — same wave、`IShopStockRepository` + ports 群
- RTM `model/issue/#4` (WeaponMechanics-NPC-as-vendor decline)
