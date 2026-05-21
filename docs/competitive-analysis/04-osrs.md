# Old School RuneScape (OSRS)

> 出典確認: 2026-05-21

## 概要

Old School RuneScape は Jagex が 2013 年に公開した、2007 年版 RuneScape の
復刻 MMO。23 種類の skill を Lv 1-99 まで並走で育てる multi-axis progression
を中核に据え、月額 Membership + Bond economy (real-money ↔ in-game gold) で
20 年超の運営継続中。同時接続は 100k 規模、Polling System (プレイヤー投票で
update 採否) という極端なコミュニティ駆動運営も特徴。

PvE-centric (PvP は Wilderness の opt-in 区画のみ)、boss drop の RNG が極端に
辛い (Twisted Bow 1/34025) こと、99 cape / Quest cape / Achievement diary 等
「grind の証明」を可視化する surface が層状に積まれていることが、Astera の
PvE + 厳選 + 承認欲求 retention 設計の最重要 reference になる。

## なぜ長寿か (retention 5 軸へのマッピング)

### 1. 多軸 progression

23 skill が **完全並走** で走る。各 skill は Lv 1-99、Lv 99 到達には
**約 13M XP** (累積)、Lv 99 → 200M XP (cap) まで延ばす completionist 層も
いる。skill 同士は緩く依存 (Smithing は Mining、Cooking は Fishing 等) で、
プレイヤーは「今日は Slayer task をこなしながら Hitpoints + 戦闘系 3 種を
上げる」のような **multi-axis 同時 progression** が日常。

### 2. 厳選レイヤー乗算

boss rare drop の RNG が極端。**Twisted Bow 1/34025** (Chambers of Xeric)、
**Tumeken's Shadow 1/24000** (Tombs of Amascut)、**pet drop 1/3000 級**
(Vorkath pet 1/3000, Zulrah pet 1/4000)。dry streak (期待値の 2-3 倍まで
出ない) を耐える文化と、「dry を耐えた story」が flex の材料になる。

### 3. 承認欲求の可視化

**Cape of Accomplishment** (Skill cape, Lv 99 到達で購入可能) は背中に表示
される grind 証明。**Max cape** (全 23 skill Lv 99) / **Completionist cape**
(全 quest + 全 achievement) / **Trimmed cape** (複数 99 持ち向け装飾) の
階層があり、達成順位が一目で分かる。Quest cape, Achievement diary cape も
同列に並ぶ。

### 4. 限定時間 cadence

**Leagues** (期間限定の rule-modified server, 通常 8-10 週) を年 1-2 回開催。
通常 server とは別 progression、relic system でビルド差別化、leaderboard 競争。
**DMM (Deadman Mode)** も同様の seasonal event。通常 grind に飽きた層を呼び
戻す cadence。

### 5. プレイヤー駆動経済

**Grand Exchange** が完全 player-driven (NPC 価格固定なし)。**Bond** (¥1,000
相当 → 14 日 Membership + 取引可能 GP に変換可能) で real money ↔ in-game
gold を**公式に**橋渡し、RMT を吸収。経済全体が player-to-player で回り、
flipping (差益取り) や PvM (boss farm → drop 売却) が主要 income loop。

## システム詳細 (core mechanic 深掘り)

### 23 skill 並走 + Lv 99 達成

| 系統 | Skill |
|---|---|
| 戦闘 (7) | Attack / Strength / Defence / Hitpoints / Ranged / Magic / Prayer |
| 採取 (6) | Mining / Fishing / Woodcutting / Hunter / Farming / Thieving |
| 生産 (8) | Smithing / Cooking / Firemaking / Crafting / Fletching / Runecrafting / Construction / Herblore |
| その他 (2) | Slayer / Agility |

- **Lv 99 までの XP**: 約 **13,034,431 XP**
- **Cap**: 200,000,000 XP (virtual Lv 126 相当)
- **Cape**: Lv 99 で **99,000 GP** で skill cape 購入可能、外見差別化
- **Trimmed cape**: 他 skill で Lv 99 を 1 つ以上持つと色変更
- 戦闘 7 種を全部 99 にすると **Quiver / Combat 99** 風の特殊 surface

これが Astera 多軸 skill 設計の元祖 reference。「23 並走」は Astera には
多すぎるが、**5-8 軸 並走 + 軸毎の visible cape/badge** は流用候補。

### Boss rare drop の極端 RNG

| Drop | 確率 | 出典 |
|---|---|---|
| Twisted Bow (CoX) | 1/34025 | wiki Chambers of Xeric drop table |
| Tumeken's Shadow (ToA) | 1/24000 (raid level 500+) | wiki Tombs of Amascut |
| Scythe of Vitur (ToB) | 1/19200 | wiki Theatre of Blood |
| Dragon Warhammer (Mole? Lizardman Shaman) | 1/5000 | wiki |
| Vorkath pet | 1/3000 | wiki Vorkath |
| Zulrah pet | 1/4000 | wiki Zulrah |

**Dry protection なし** (一部 boss の megarare のみ unique table 経由で確率
補正あり)。dry streak (期待値の 2-3x) は日常で、「2x dry まで耐える」前提
の grind culture。

### Quest cape / Achievement diary

- **Quest cape**: 全 quest クリアで装備可能、新 quest 追加の度に再取得が
  必要 (削除されないが「再認定」が起きる)
- **Achievement diary** (Easy/Medium/Hard/Elite) — 各地域ごとに 4 段階。
  全 Elite 完了で **Achievement Diary cape**
- **Combat Achievements** (Easy → Grandmaster の 6 tier) — 各 boss の
  challenge clear、Grandmaster 全完了で **Combat Achievement cape**

これらは「**1 回達成すれば消えない**」 type の completionist 承認欲求 surface。

### Membership / Bond economy

- F2P で skill cap Lv 20 程度 + 限定 area
- **Membership**: 月額 ~$13.99 (年払いで割安)
- **Bond**: $7.99 で 14 日 Membership 引換券 (game 内で売買可能)
  - in-game GP で Bond を買えば実質 F2P で Membership 維持可能
  - real money 課金者は Bond を売って GP を得る → RMT を公式が吸収
- **GE 経由で Bond の市場価格は変動** (現在 5-8M GP 帯)

## Astera 適用候補 (流用 / 模倣 / 拒否 の 3 段階)

| 要素 | 判断 | 理由 |
|---|---|---|
| 23 skill 並走 | 模倣 (縮小) | 5-8 軸並走に縮小して Astera multi-axis design に。`stats` plugin の skill XP 軸を 5-8 軸に拡張。 |
| Lv 99 cape | 流用 | 軸毎の visible badge (背中表示 / 名前 prefix) を Astera `economy`+`stats` 連携で実装。grind の証明を即座に他プレイヤーに見せる surface。 |
| Trimmed cape (複数 99) | 流用 | 「複数軸 max 達成で装飾変化」は flex 加速の優秀パターン。Astera では aura / particle 強度で表現可能。 |
| 1/34025 級 megarare drop | 模倣 (緩和) | Astera は PvP-match-only から PvE 移行直後で、初期は 1/500 - 1/2000 帯から始めるべき。1/34025 は population が桁違いに大きい OSRS 専用の数字。 |
| Quest cape (削除されない達成) | 流用 | Astera Achievement system の base 設計。「一度達成すれば永続」+「新 quest 追加時に re-certify」のループは長寿達成の好例。 |
| Achievement diary 4 段階 | 流用 | Easy → Elite の段階制承認欲求は Astera 各 stage / boss の challenge tier として直接流用可能。 |
| Bond economy (real money ↔ GP) | 拒否 | Phase 1 PoC では monetization 設計に踏み込まない。ADR-0010 (generative AI postpone) と同列に Phase 6 以降の検討事項。 |
| Polling System (community 投票で update) | 拒否 (現段階) | Phase 1 では development velocity 優先、community polling は user base が育ってから。 |
| Grand Exchange (player-driven 経済) | 模倣 | Astera `economy` plugin の market design の reference。NPC 価格固定なし + flipping 可能な spread の design。 |
| Leagues (seasonal rule-modified) | 流用 (Phase 6) | Astera の seasonal event 設計の reference。通常 progression と別軸で leaderboard 競争を作るパターン。 |

## Source / References

- https://oldschool.runescape.wiki/ — OSRS Wiki (drop rate / XP table の SSOT)
- https://oldschool.runescape.wiki/w/Cape_of_Accomplishment — 99 cape 文化
- https://oldschool.runescape.wiki/w/Twisted_bow — Twisted Bow 1/34025 drop rate
- https://oldschool.runescape.wiki/w/Tombs_of_Amascut — ToA raid drop table
- https://oldschool.runescape.wiki/w/Achievement_Diary — Achievement diary 制度
- https://oldschool.runescape.wiki/w/Bond — Bond economy
- https://secure.runescape.com/m=news/oldschool/ — Jagex 公式 update news
- https://oldschool.runescape.wiki/w/Drop_table — drop table 全般
