# Adding a Shop

Astera Shop は `content/shops/<id>.yaml` 1 ファイルで定義します
(Wave 5g / ADR-0036, Wave 5refine / ADR-0100)。Item を Currency で売る
NPC merchant の vocabulary を宣言的に書きます。Kotlin コードは触りません。

## 最小例 (example-shop)

```yaml
# content/shops/example-shop.yaml
id: example-shop
display_key: astera.economy.shop.example-shop.title

offers:
  - id: knight-sword
    item: knight-sword
    price:
      currency: GOLD
      amount: 50
    stock:
      type: unlimited
    display_key: astera.economy.shop.example-shop.offer.sword

  - id: pistol
    item: pistol
    price:
      currency: GOLD
      amount: 40
    stock:
      type: unlimited
    display_key: astera.economy.shop.example-shop.offer.pistol
```

## Schema reference

`plugin/core/.../domain/shop/ShopSpec.kt`
が SSOT。

| Field | Type | Required | 説明 |
|---|---|---|---|
| `id` | `String` | ✅ | `ShopId`。`^[a-z][a-z0-9-]*$` |
| `display_key` | `String` | ✅ | i18n key (Shop GUI のタイトル) |
| `offers` | `List<ShopOffer>` | ✅ | 売り物 (≥ 1) |
| `offers[].id` | `String` | ✅ | offer 識別子 (shop 内で unique) |
| `offers[].item` | `String` | ✅ | `ItemId` (`content/items/` 参照) |
| `offers[].price.currency` | `enum` | ✅ | `GOLD` / `STAR` / `GEM` (Currency 一覧は `Currency.kt`) |
| `offers[].price.amount` | `Long` | ✅ | > 0 |
| `offers[].stock.type` | `enum` | ✅ | `unlimited` / `limited` |
| `offers[].stock.count` | `Int` | `limited` 時必須 | 在庫数 |
| `offers[].stock.refresh` | `enum` | optional | `none` / `daily` / `weekly` (default `none`) |
| `offers[].display_key` | `String` | ✅ | offer GUI のラベル i18n key |

## Validation rule

- `offers[].item` が `IItemRegistry` に存在しない = `MissingReference`
  (`ShopReferencesItemCheck` が boot 時に walk; Wave 5server-harden / ADR-0123)
- `offers[].id` の重複 = parse error
- `offers[].price.amount` ≤ 0 = parse error
- `offers[].stock.type = limited` で `count` 未指定 = parse error
- `offers[].stock.refresh = daily` 時、`/astera-debug shop-refresh-tick`
  で手動 fire 可

## Reload command

```
/astera reload shops
```

atomic swap。stock state は in-memory (Phase 1) / Postgres (Persistence
plugin 有効時)、reload では維持される (offer 定義のみ swap)。

## Currency

`plugin/core/.../domain/economy/Currency.kt` が enum。Phase 1 では:

| Currency | 用途 |
|---|---|
| `GOLD` | 汎用通貨 (rewards / 普通の merchant) |
| `STAR` | premium (限定 offers) |
| `GEM` | (将来予約) |

価格は `Currency` 単位で 1 つ。multi-currency / 複合支払いは Phase 4 で
別 ADR 起草。

## Shop UI と購入フロー

ショップの入口は 2 つ (WP13 で整理):

- **ロビー NPC**: `content/lobby-npcs/shop-npc.yaml` の右クリックが
  `lobby.shop.open` を発火 → `LobbyShopOpenIntentHandler` → `ShopBrowseUseCase`
  (`payload.shop_id` 省略時は `example-shop`)。メインメニューのショップタイルも同じ intent。
- **コマンド**: `/astera shop` (既定ショップ) / `/astera shop <shop-id>`。

購入フロー: ショップ GUI の offer クリック → `shop.purchase` intent →
`ShopPurchaseUseCase` (`ICurrencyLedger.debit` → 在庫 decrement → `giveItem`)。
所持金は新規プレイヤーの starter grant (`content/balance/starter-grant.yaml`、GOLD 100) か、
管理者の `/astera economy grant <player> <currency> <amount>` で付与。

`stock: limited` の場合、`IShopStockRepository` (Persistence overlay 100,
in-memory 0) で在庫を atomically decrement。

## 参考

- ADR-0036 (Economy + Shop vendor foundation)
- ADR-0100 (Stat-flex item refinement)
- ADR-0123 §A.example (`ShopReferencesItemCheck`)
- [[contributing/add-an-item-yaml]]
