# JSON schemas (vendored)

## mc-model.schema.json

Minecraft Java Edition `model.json` JSON Schema (draft-07).

- **Source**: https://raw.githubusercontent.com/Levertion/minecraft-json-schema/master/java/assets/model.json
- **Vendored**: 2026-05-17 (commit pinned by file contents — re-fetch only if a deliberate sync is intended)
- **License**: Levertion repo is MIT (see https://github.com/Levertion/minecraft-json-schema)
- **Local patches**:
  - `properties.textures.propertyNames.pattern`: `^[a-z_\-/]+$` →
    `^[a-z0-9_\-/]+$`. Allows digits in slot names like `layer0`, `layer1`,
    which vanilla MC item models use.
  - `definitions.texture.oneOf[1].pattern`: `^#[a-z_\-/]+$` →
    `^#[a-z0-9_\-/]+$`. Same fix for face texture refs (`#layer0`).
  Both patches consistent with the schema's own examples, which the upstream
  pattern was inconsistent with.

## resource_location.schema.json

Companion shared schema for resource location strings (`namespace:path`
shaped identifiers). Source:
`https://raw.githubusercontent.com/Levertion/minecraft-json-schema/master/java/shared/resource_location.json`

This schema is the source of truth for everything the Studio 3D model
editor writes. It is loaded by:

- The CodeMirror linter (`/models` view, JSON validation in-editor)
- The Hono server's MCP `apply_patch` post-validator (rejects invalid
  patches before they reach the WebSocket bridge)

Re-fetch with:

```bash
curl -fsSL https://raw.githubusercontent.com/Levertion/minecraft-json-schema/master/java/assets/model.json \
  -o tools/studio/web/server/schemas/mc-model.schema.json
```

If the upstream schema changes shape (e.g. new top-level keys, stricter
coordinate ranges, MC version bump), update both this file and the
`samples/*.model.json` in `tools/model-gen/samples/`.
