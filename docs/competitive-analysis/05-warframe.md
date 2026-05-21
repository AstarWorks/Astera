# Warframe

> 出典確認: 2026-05-21

## 概要

Warframe は Digital Extremes が 2013 年に launch した F2P 三人称 looter-shooter。
Frame (操作キャラ) と weapon を「育てて → max まで level up して → Forma で
polarity 再構成して → さらに育てる」という **削っては作り直し** のループを
9 年以上回し続けている。Mastery Rank (MR) は全アイテムの累積 leveling を統合
した「総合 progression」、Riven Mod は weapon-specific RNG mod として **削って
戻れない厳選** の代表例で、Astera が後発として最も学ぶべき "swap 可能 vs 削って
戻れない" design tension の reference。

Fashion Frame ("Fashion Frame is endgame" は公式が認めるコミュニティ slogan)
として、性能と無関係の見た目 customization (skin / syandana / armor pieces /
energy color / operator cosmetics) が承認欲求 surface として確立している。

## なぜ長寿か (retention 5 軸へのマッピング)

### 1. 多軸 progression

**Mastery Rank** (MR 0 - 30+, Legendary Rank も累積) は frame / weapon /
companion / archwing の **leveling 累積** がそのまま MR XP になる統合 surface。
個別軸として: Frame level (0-30), Weapon level (0-30), Forma 1 周毎に Lv 0 から
やり直し、Focus school (Tenno operator) lens system、Intrinsics (Railjack
operator skill) と並走多軸が層状。

### 2. 厳選レイヤー乗算

**Riven Mod** は **weapon-specific RNG mod**。1 つの Riven は:

- 1-4 affix (典型は 3 positive + 1 negative or 2 positive)
- 各 affix は 30+ stat pool から RNG 抽選
- Disposition (weapon-tier balancing multiplier 0.5x - 1.55x) が weapon 毎に
  固定 (人気武器は低 disposition = Riven が弱い)
- **Kuva re-roll**: cost escalation **900 → 1000 → 2000 → 2500 → 3000 → 3500**
  Kuva (9 回目以降は 3500 固定)
- 完璧 roll への journey は実質無限

**Treasures of the Lotus / Sortie / Arbitration / Steel Path** から rotation
報酬として更に modular RNG が積み重なる。

### 3. 承認欲求の可視化

- **Fashion Frame**: skin, syandana, armor (shoulder / chest / leg), helmet,
  energy color (RGB ramp), 色を組み合わせて全体 palette を作る。性能完全独立。
- **Operator cosmetics**: 表情、髪型、入れ墨、Amp パーツ
- **Sentinel / Kavat / Kubrow** companion の毛色 RNG (Kubrow imprint trade)
- **Conclave / Mastery 30 称号 (Old Mate, etc.)** — MR 到達で称号付与
- **Profile stat** がプロフィールから見える (kill 数, hours played 等)

### 4. 限定時間 cadence

- **Nightwave** (季節 battle pass 風 narrative event, 10 週前後)
- **Tennocon** 周年に新 frame / quest 開放
- **Prime Vault** rotation (古い Prime frame の relic を再供給する 6-8 週
  cycle、その後 vault に戻る)
- **Baro Ki'Teer** 隔週で訪れる商人、限定 mod / cosmetic を Ducat で販売

### 5. プレイヤー駆動経済

- **Platinum** (premium currency, real money 購入 or プレイヤー間 trade)
- **Riven trade**: warframe.market で 100p - 50000p+ で取引、market 形成
- **Prime parts trade**: vault rotation で価格変動
- **Kuva Lich / Sister of Parvos** trade (個別 RNG weapon)
- 公式 RMT を Platinum 経由で吸収 (OSRS Bond と同型)

## システム詳細 (core mechanic 深掘り)

### Riven Mod RNG re-roll system

| 要素 | 数値 / 仕様 |
|---|---|
| Affix 数 | 2 positive only, 2 positive + 1 negative, **3 positive + 1 negative** (最も一般的) |
| Stat pool | 30+ (damage / multishot / crit chance / crit damage / status / fire rate / magazine / reload / zoom / recoil 等、武器 type で抽選 pool 異なる) |
| Negative の利益 | +Damage や +CC を底上げ (許容できる negative を引くのが key) |
| Disposition | weapon 毎 0.50x - 1.55x、人気で弱体化 (popularity tax) |
| Kuva re-roll cost | 900 → 1000 → 2000 → 2500 → 3000 → **3500** (8 回目以降 cap) |
| Kuva 取得 | Kuva Siphon / Flood missions (mission per 600-1200 Kuva), Kuva Survival で時間効率向上 |
| Trade tax | platinum 価格の 0% (trade 自体は free), warframe.market が de facto 市場 |

**重要**: Riven は **swap 可能** ではなく **re-roll** モデル。前 roll は破棄、
Kuva を払って次 roll に賭ける。「削って戻れない」の典型で、Astera が逆方向
(swap 可能 = roll を残せる) を選ぶか同方向を選ぶかの判断軸。

### Mastery Rank (MR)

- MR 0 → MR 30 まで (Legendary Rank 0 - 4 で更に累積)
- **Frame Lv 30 = 6000 MR XP**, **Weapon Lv 30 = 3000 MR XP** (Forma 周回は
  MR XP に影響しない、初回 Lv 30 到達のみ)
- MR 上昇で daily standing cap / void trace cap / mod cap が解放
- MR Test (rank up 時の試練) — 失敗すると 24h cooldown
- MR 30 到達は 1000h+ 級のプレイ証明

### Forma + Polarity

- Frame / Weapon の各 mod slot に **polarity** (V / D / Bar / Ability / Umbra) を
  burn できる Forma を消費
- Forma 1 個消費で **Lv 30 → Lv 0** にリセット、再度 Lv 30 まで上げ直し
- 完璧 build のために 6-7 Forma 消費する weapon は珍しくない
- Forma 1 個は 23h build + relic farm or 35-50p で trade

### Fashion Frame

- Frame skin (deluxe / tennogen / immortal): 70-225p
- Energy color: Phoenix / Smoke / Saturated 等の palette pack (各 75p)
- Syandana (背中の cloth): 75-165p
- Armor set (shoulder / chest / leg): 75-185p
- Operator cosmetics: 75-200p
- **すべて性能と独立** (energy color が damage に影響しない等)

"Fashion Frame is endgame" は **DE 公式が認めた** community slogan。

## Astera 適用候補 (流用 / 模倣 / 拒否 の 3 段階)

| 要素 | 判断 | 理由 |
|---|---|---|
| Riven の cost-escalation re-roll | 模倣 | Astera gemstone re-roll の resource sink design として最も近い参照。900 → 3500 Kuva のような **escalating cost** は厳選 RNG の心理を回す。 |
| Riven の「削って戻れない」 | **拒否** | Astera は「**swap 可能 / 過去 roll をキープ**」を design 軸に置く方が後発有利 (前 roll が gold だったのに上書きしてしまう体験の最悪さは Warframe community で 9 年語られてきた負債)。 |
| Disposition (人気 weapon penalty) | 拒否 | Astera 規模 (PvE-match) では balancing tax の概念は不要。複雑性に見合うリターンなし。 |
| Affix 3 pos + 1 neg の組合せ | 流用 | Astera Refinement / gemstone の affix 構造として 2-3 positive + 0-1 negative は learnable な複雑度。 |
| Mastery Rank (全 progression 統合 surface) | 流用 | Astera の "総合 player level" 設計の reference。各 skill / weapon / armor の leveling を統合した MR-like rank が承認欲求の base になる。 |
| Forma (削って polarity burn) | 模倣 (慎重) | "Lv 30 → Lv 0 リセット + 強くなる" の design は強力だが、Astera 初期は frustration が勝るリスク。Phase 5 以降の検討。 |
| Fashion Frame (性能完全独立 cosmetic) | **流用 (最優先)** | "性能と完全に独立した見た目 customization" は承認欲求 surface として **コスパが極めて高い** (バランス調整不要)。Astera `economy` plugin の cosmetic market design の base。 |
| Platinum (real money ↔ in-game) | 拒否 (Phase 1) | OSRS Bond と同列、monetization design は Phase 6 以降。 |
| Nightwave (10 週 battle pass narrative) | 模倣 | Astera seasonal event の枠組みとして直接流用可能。任務制 + 報酬 track + narrative の三層構造。 |
| Riven trade market (warframe.market 級の market) | 拒否 (現段階) | Astera 規模では external market site は不要、内部 GE で十分。 |
| Kuva Lich (個別 RNG weapon — owner-specific) | 流用 | "プレイヤー個別の RNG weapon" は厳選軸 + ownership 物語性の両立で優秀。Astera 「個別 boss から派生する RNG weapon」design の reference。 |

## Source / References

- https://warframe.fandom.com/wiki/Riven_Mods — Riven 仕様 (affix / re-roll cost / disposition)
- https://warframe.fandom.com/wiki/Mastery_Rank — MR 計算式、MR XP source
- https://warframe.fandom.com/wiki/Kuva_(Resource) — Kuva 取得、re-roll cost
- https://warframe.fandom.com/wiki/Forma — Forma + polarity 仕様
- https://warframe.fandom.com/wiki/Fashion_Frame — Fashion Frame slogan の出自
- https://www.warframe.com/news — DE 公式 update / patch notes
- https://forums.warframe.com/ — community discussion (Riven 設計議論の元 thread)
- https://warframe.market/ — Riven / Prime parts 取引市場
