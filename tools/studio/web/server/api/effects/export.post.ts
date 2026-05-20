import path from 'node:path'
import {
  appendEffectsEntry,
  effectsDir,
  type EffectsManifestEntry,
} from '../../utils/effects/manifest'
import {
  animationsDir,
  atomicWrite,
  modelsEffectsDir,
  relToRepo,
  safeSlug,
  type ExportBody,
} from '../../utils/effects/effects-lib'

export default defineEventHandler(async (event) => {
  let body: ExportBody
  try {
    body = (await readBody(event)) as ExportBody
  } catch {
    throw createError({ statusCode: 400, statusMessage: 'invalid json' })
  }
  const basename = body.basename
  if (!basename || !safeSlug(basename)) {
    throw createError({ statusCode: 400, statusMessage: 'invalid basename' })
  }
  const slug = basename
  const written: string[] = []
  const decode = (b64: string) => Buffer.from(b64, 'base64')

  try {
    // 1. Recipe → content/effects/<slug>.fxgraph.json
    if (body.fxgraph) {
      const recipePath = path.join(effectsDir, `${slug}.fxgraph.json`)
      atomicWrite(recipePath, JSON.stringify(body.fxgraph, null, 2) + '\n')
      written.push(relToRepo(recipePath))
    }

    // 2. Vertical sheet + mcmeta
    if (body.vertical_sheet_b64) {
      const png = path.join(animationsDir, `${slug}.png`)
      atomicWrite(png, decode(body.vertical_sheet_b64))
      written.push(relToRepo(png))
      if (body.mcmeta) {
        const meta = path.join(animationsDir, `${slug}.mcmeta.json`)
        atomicWrite(meta, JSON.stringify(body.mcmeta, null, 2) + '\n')
        written.push(relToRepo(meta))
      }
    }

    // 3. Horizontal sheet
    if (body.horizontal_sheet_b64) {
      const png = path.join(animationsDir, `${slug}_horiz.png`)
      atomicWrite(png, decode(body.horizontal_sheet_b64))
      written.push(relToRepo(png))
    }

    // 4. Per-frame PNGs
    if (body.frames_b64 && Array.isArray(body.frames_b64)) {
      for (let i = 0; i < body.frames_b64.length; i++) {
        const pad = String(i).padStart(2, '0')
        const png = path.join(animationsDir, `${slug}_${pad}.png`)
        atomicWrite(png, decode(body.frames_b64[i]))
        written.push(relToRepo(png))
      }
    }

    // 5. item_display model
    let modelPath: string | undefined
    if (body.model_json) {
      modelPath = path.join(modelsEffectsDir, `${slug}.model.json`)
      atomicWrite(modelPath, JSON.stringify(body.model_json, null, 2) + '\n')
      written.push(relToRepo(modelPath))
    }

    // 5b. summon help (resource-pack instructions + paste-ready /summon)
    if (body.summon_help) {
      const helpPath = path.join(effectsDir, `${slug}.summon.md`)
      atomicWrite(helpPath, body.summon_help)
      written.push(relToRepo(helpPath))
    }

    // 6. Manifest entry
    const entry: EffectsManifestEntry = {
      slug,
      fxgraph_path: `content/effects/${slug}.fxgraph.json`,
      texture_paths: written.filter((p) => p.endsWith('.png')),
      model_path: modelPath ? relToRepo(modelPath) : undefined,
      exported_at: new Date().toISOString(),
      frames: body.fxgraph?.frames ?? 0,
      size: body.fxgraph?.size ?? [32, 32],
      frame_time_ticks: body.fxgraph?.frame_time_ticks ?? 2,
      formats: body.formats,
    }
    appendEffectsEntry(slug, entry)

    // Pull the first `/summon ...` line out of the help file so the front-end
    // can show it directly in the success toast for one-click copy-paste.
    let summon_command: string | undefined
    if (body.summon_help) {
      const m = body.summon_help.match(/\/(summon [^\n]+)/)
      if (m) summon_command = `/${m[1]}`
    }

    return { ok: true, slug, written, entry, summon_command }
  } catch (e) {
    throw createError({ statusCode: 500, statusMessage: `export failed: ${String(e)}` })
  }
})
