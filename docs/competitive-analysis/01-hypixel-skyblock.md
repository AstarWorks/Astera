# Hypixel SkyBlock (HSB)

> 出典確認: 2026-05-21

## 概要

Hypixel SkyBlock は Hypixel Network 上で 2019 年 6 月にリリースされた永続的 PvE
中心 MMORPG-like Minecraft minigame。元は「スカイブロック (浮島生存)」を MMO 化した
ものだが、現在は Skill system / Dungeon / Slayer / Auction House / Bazaar など、
事実上独立した経済を持つ巨大ゲームに膨張。**Astera と最も近い比較対象**: Paper
サーバー上で動く、PvE 武器共用 + 厳選レイヤー乗算 + 承認欲求可視化を全部やっている。

PvP は事実上存在しない (Crimson Isle の Dojo / Mage 鬪技場など niche のみ)。本研究
の焦点は **PvE 武器を全プレイヤーで共用する設計** と、それを 7 年間延命させている
**厳選レイヤーの乗算構造**。

## なぜ長寿か (retention 5 軸へのマッピング)

### 1. 多軸 progression

12 個の Skill (Combat / Mining / Farming / Foraging / Fishing / Enchanting /
Alchemy / Taming / Carpentry / Runecrafting / Social / Dungeoneering) が並列に
存在し、各々レベルキャップ 50 または 60、XP 曲線は指数。Combat 60 まで
55,172,425 XP。Skill ごとに固有の perk と coin / stat ボーナス。さらに HotM
(Heart of the Mountain) Mining 専用 talent tree、Garden / Bestiary などサブ
progression が複数並列。

### 2. 厳選レイヤー乗算

下記「武器強化レイヤー」セクション参照。base item × reforge × enchant × HPB ×
gemstone × stars × dye × recombobulator の 8 軸が **乗算的に積み重なる**。

### 3. 承認欲求の可視化

- **Mythic glow**: rarity が Mythic に達した item が虹色 enchant glint で光る
- **Networth**: 全所持資産を coin 換算した「Networth」値が外部サイト
  (sky.shiiyu.moe) で公開され、leaderboard 化
- **SkyBlock Level**: 全 collection / skill / minion 等を集約した総合レベル
  (400-500 がトップ層の目安)
- **Auction House の Mirror tier**: 1 item で 数十億 coin の超高額 item は所有
  自体が flex (例: ✪✪✪✪✪ Necron's Blade with God Roll)

### 4. 限定時間 cadence

- **Mayor election** 5 日サイクル: 5 日 × 12 候補で経済 perk 変化
- **Jacob's Farming Contest** リアル数時間ごと出現 (medal で報酬)
- **Spooky Festival / Jerry's Workshop / New Year Celebration** など季節 event
- **Patch cadence**: 大型 update は半年〜1 年に 1 回、間に hotfix。Crimson Isle
  (2022), Garden (2023), Galatea / Foraging rework (2024-2025) など。

### 5. プレイヤー駆動経済

- **Bazaar**: 限定 commodity (essence, enchant book, raw material) の order book
  式取引
- **Auction House**: unique item は競売
- 大量の craft chain (例: Hyperion → Necron's Blade → Necron's Handle + Wither
  Catalyst × 24 + ...) で素材市場が広く深い

## システム詳細 (core mechanic 深掘り)

### A. 武器強化レイヤー (乗算構造)

剣 1 本に乗る乗算軸 (Hyperion class の最終強化を想定):

| Layer | 効果範囲 | resource |
|---|---|---|
| Base item rarity | Common → Mythic (5 段階 + ✪ stars) | 素材 craft |
| Reforge | +damage / +strength / +crit chance 等 (Fabled, Spicy, Withered, Ancient) | Reforge stone + coin |
| Enchantment | Sharpness 7, Smite 7, Critical 7, Ultimate Wise 5 など 15+ slot | Enchant book |
| Hot Potato Book (HPB) | +2 damage × 15 段 = +30 damage | HPB item |
| Fuming Potato Book | +2 damage × 5 段 (HPB 15 以降) = +10 | Fuming book |
| Gemstone slot | Combat type (Jasper / Ruby) で +damage / +crit damage | Perfect Gemstone |
| Stars (✪) | ✪×5 で +25% stat、Master Star でさらに ✪✪ → ✪✪✪✪✪ | Essence (Wither/Dragon 等) |
| Recombobulator 3000 | rarity を 1 段階 up (Legendary → Mythic) | Rare drop / Dark Auction |
| Dye | 見た目のみ (Crystal Dye など 数億 coin) | super rare drop |

**乗算結果**: Hyperion 1 本を「god roll」にするコストは数千万 - 数億 coin、
時間にして数百時間。これが 1 武器の depth。武器は約 50 種類あり、全部 max まで
詰める prestige プレイヤーは数千時間プレイ。

### B. Catacombs Dungeon (group PvE instance)

- 5 人 instance、固定 role: Tank / Healer / Berserk / Mage / Archer
- Floor F1-F7 + 各 Master Mode (M1-M7) = 14 difficulty
- 1 Floor で 5-15 分、score S+ (300 点) で reward 最大化
- **Catacombs Skill** はこの dungeon でのみ XP 入手、Cap 50 (50 で実用ライン)
- **Master Star essence farm** が後期目標 (Wither Essence は M7 で時間効率最大)
- Class level も並列: Tank 50 / Healer 50 / Berserk 50 / Mage 50 / Archer 50

承認欲求面: Floor 7 S+ 時間アタック leaderboard (公式 in-game)、Secret 数 (例:
"Catacombs Secrets Found: 100,000"), Mythological Burrow など niche stat 全てが
in-game UI で見える。

### C. Slayer system (個人 boss farm の典型)

| Slayer | unlock | Tier | 主要 drop |
|---|---|---|---|
| Zombie | Combat 3 | T1-T5 | Revenant Catalyst, Reaper Mask |
| Spider | Combat 4 | T1-T4 | Tarantula Helmet, Digested Mosquito |
| Wolf | Combat 5 | T1-T4 | Grizzly Bait, Hunter Knife |
| Enderman | Combat 8 | T1-T4 | Judgement Core, Summoning Eye, **Necron's Handle** |
| Blaze | Slayer 3 系 max | T1-T4 | Magma Lord set, Dwarven Handwarmers |
| Vampire | Crimson Isle Rep | T1-T5 | Ichor, Mango, Yoggie |

各 Slayer に独立 XP bar、Tier 1 → 5 まで合計約 1,000,000 XP (Slayer により異なる
が Enderman は 1,000,000)。**rare drop は数千 boss kill / RNG 1/x のオーダー**:
例 Judgement Core T4 ≈ 1/15,000 で magic find buff 込みで 1/数千。

### D. Skill system (12 軸並列)

各 Skill cap 50 (一部 60)。Skill レベル up で permanent stat 増加:

- Combat 50: +25 Crit Damage, +25 Crit Chance, +50 Strength
- Farming 60: +35 Farming Fortune, Pelt cap up
- Mining 60 + HotM 7: drilling speed / Mithril Powder farm 解放
- Dungeoneering 50: Dungeon class XP gain +50%

**Skill XP は farming method が全部違う**: Foraging は木を切る、Fishing は釣る、
Alchemy は brew する、Carpentry は craft する。**各 Skill が独立した日課を要求**
する構造が 1000 時間級の playtime に直結。

## Astera 適用候補 (流用 / 模倣 / 拒否 の 3 段階で)

| 要素 | 判断 | 理由 |
|---|---|---|
| 武器強化の **乗算 8 軸** | 流用 (簡略化) | Astera は 4-5 軸に縮約 (base × reforge × enchant × gemstone × stars)。HSB の 8 軸は overwhelming だが乗算構造自体は厳選の核心。 |
| Recombobulator (rarity up consumable) | 模倣 | rarity 1 段階 up consumable は厳選レイヤーの psychological hook が強い。Astera では超低確率 drop or event 報酬で配布。 |
| Catacombs role 制 (Tank/Healer/Berserk/Mage/Archer) | 模倣 | 5 人 instance + role 制は group PvE の retention 設計の defacto。Astera の party 機能と整合。 |
| Master Star (✪✪→✪✪✪✪✪ 段階強化) | 流用 | endgame item の「もう一押し」軸として優秀。essence farming で時給を可視化できる。 |
| Slayer Tier ↔ rare drop 確率テーブル | 流用 | Astera の PvE drop table の直接の prior art。Tier 1-5 × 個別 rare 1/数千-1/数万 の RNG layer。 |
| 12 Skill 並列 | 拒否 (まず) | Astera Phase 1 は Combat / Mining 程度の 2-3 軸で十分。12 は scope creep。 |
| HotM talent tree | 模倣 (将来) | Mining 専用 perk tree は Astera の Class 拡張の参考に。 |
| Mayor election (5 日 econ 変動) | 拒否 (Phase 1) | 経済が成熟する前に入れると balance 不安定。 |
| Mythic glow + Networth 外部 leaderboard | 流用 | rarity 視認性 + 外部 leaderboard は承認欲求の標準装備。 |
| Auction House + Bazaar 二段経済 | 模倣 (Phase 2+) | Astera economy plugin の future design に直結。 |
| Reforge stone (random affix) | 流用 | PoE Chaos Orb と等価機能。後述 PoE の craft 設計と合流する。 |
| Dye (cosmetic only, 高額) | 模倣 | cosmetic-only flex item は経済の sink + flex として優秀。 |

## Source / References

- https://wiki.hypixel.net/ — 公式 wiki (Skill / Slayer / Dungeon の数値 SSOT)
- https://hypixel-skyblock.fandom.com/ — community wiki (歴史的 patch note アーカイブ)
- https://hypixel.net/forums/skyblock-patch-notes.158/ — 公式 patch notes
- https://sky.shiiyu.moe/ — 非公式 Networth / stat dashboard (承認欲求 surface の好例)
- https://wiki.hypixel.net/Dungeons — Catacombs role / floor 仕様
- https://wiki.hypixel.net/Slayer — Slayer Tier / drop 確率
- https://wiki.hypixel.net/Skills — 12 Skill cap / XP 曲線
