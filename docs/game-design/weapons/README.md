# Weapons

武器システムの設計。

- [ttk.md](ttk.md) -- 全 production 武器の TTK 表、アーキタイプ別の帯、レベル要件、dev/demo の分離(MODE-03/08/09)。
  表は `python3 scripts/ttk-table.py` で content から再生成できる。
- 3 アーキタイプ: 銃(ammo, 射程と弾数)、剣(energy, 近接と突進)、杖(energy, 状態異常とエリア)。看板武器(Limbus 系)は
  s1/s2/s3/def/ego の多段キットで、基本武器より遅く深い。

予定: `catalog.md` (YAML から自動生成)
