# Architecture Overview

Astera は **Clean Architecture / Hexagonal** に基づき、ゲームドメインを Minecraft から切り離した設計を持つ。

## 1 枚絵

```
┌────────────────────────────────────────────────────────────────────┐
│  Lv3  platform-paper-plugin                                        │
│       (JavaPlugin entrypoint, Koin module 結線)                    │
├────────────────────────────────────────────────────────────────────┤
│  Lv2  adapter-minecraft-impl-paper      adapter-persistence-postgres
│  Lv2  adapter-minecraft-api  (vendor-neutral, no Bukkit imports)    │
│  Lv2  adapter-messaging-redis   adapter-providers/* (Oraxen 等)     │
├────────────────────────────────────────────────────────────────────┤
│  Lv1  application                                                  │
│       Use case + Port (inbound / outbound)                         │
├────────────────────────────────────────────────────────────────────┤
│  Lv0  domain                                                       │
│       Entity / ValueObject / DomainEvent / Rule                    │
│       (Pure Kotlin, no Minecraft, no Bukkit)                       │
└────────────────────────────────────────────────────────────────────┘
                依存方向: 上→下 のみ
```

## 主要な約束

1. **Minecraft はエッジ** — `domain` / `application` は Bukkit を一切知らない ([[adr/0001]])
2. **サーバーコアも差替え可能** — `adapter-minecraft-api` が vendor-neutral、各 server core 用 impl module を差し替え可能 ([[adr/0002]])
3. **外部プラグインはすべて soft-dep** — Provider パターンで切替可能 ([[adr/0009]])
4. **設定は YAML** — `content/` 配下で UGC コントリビュータが Kotlin を書かずに追加可能

## モジュール一覧

詳細は [module-map.md](module-map.md)。

| Module | 役割 | 依存 |
|---|---|---|
| `domain` | 純粋ドメイン | Kotlin stdlib + coroutines |
| `application` | UC + port | domain |
| `adapter-minecraft-api` | MC 概念抽象 | application |
| `adapter-minecraft-impl-paper` | Paper 実装 | adapter-minecraft-api, paper-api (compileOnly) |
| `adapter-persistence-postgres` | DB 実装 | application |
| `adapter-messaging-redis` | Pub/Sub 実装 | application |
| `adapter-providers/*` | 外部プラグイン適応 | adapter-minecraft-api, 該当 plugin (compileOnly) |
| `platform-paper-plugin` | Paper entrypoint | 全層 |

## データの流れ (例: 武器発射)

```
Player クリック
  → Bukkit Event (paper)
  → BukkitEventAdapter (adapter-minecraft-impl-paper)
  → IMcEvent (adapter-minecraft-api)
  → FireWeaponUseCase (application)
  → Weapon.fire() ルール (domain) → DamageProfile を返す
  → IWorldGateway.applyDamage(...) (port)
  → PaperWorldGateway (adapter-minecraft-impl-paper) → Bukkit damage 呼出し
```

`Bukkit.Player` というオブジェクトはドメインに辿り着く前に `PlayerId` に変換される。ドメインは `Bukkit` を一切知らない。

## 関連 ADR

- [[adr/0001-clean-architecture-with-mc-as-edge]]
- [[adr/0002-vendor-neutral-mc-adapter-layer]]
- [[adr/0003-kotlin-jdk25-paper-26]]
- [[adr/0004-koin-single-di-not-dagger-spring]]
