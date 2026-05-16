# content/skills/

Skill definitions. **Phase 2 で本格的に追加**される。

Phase 1 では空。武器 (`../weapons/`) のクールダウン/ダメージで最小限を表現する。

Phase 2 schema イメージ (確定前):

```yaml
id: example-blast
display_name_key: astera.skill.example-blast.name
cooldown_ticks: 100
range: 10.0
damage:
  base: 15.0
  attribute: fire
status_effects:
  - id: burn
    duration_ticks: 60
    chance: 0.8
```
