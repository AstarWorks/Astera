# content/stages/

Stage (map) definitions for Siege Warfare. **Phase 3 で本格的に追加**される。

Phase 1-2 では空。

Phase 3 schema イメージ (確定前):

```yaml
id: example-fortress
display_name_key: astera.stage.example-fortress.name
schematic: example-fortress.schem
spawn:
  attackers:
    - { x: 0, y: 64, z: -100 }
  defenders:
    - { x: 0, y: 64, z: 100 }
core:
  position: { x: 0, y: 70, z: 80 }
  health: 1000
```

Schematics (WorldEdit `.schem`) は `stages/schematics/` 配下 (Phase 3 で導入)。
