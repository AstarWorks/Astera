#!/usr/bin/env bun
/**
 * Astera Studio CLI — thin curl wrapper for the local Hono REST API.
 *
 * Run via `bun run studio <verb> <args...>` from `tools/studio/web/`.
 * Default API is http://localhost:3008 (override with ASTERA_STUDIO_API_URL).
 *
 * Verbs:
 *   state                                       print current server state JSON
 *   load <scope> <slug>                         POST /api/state/load
 *   new [--parent=...]                          POST /api/cuboids/_new
 *   add-cuboid <from> <to> [--rot-axis=y --rot-angle=22.5 --rot-origin=x,y,z]
 *   move <i> <dx,dy,dz>                         PATCH /api/cuboids/:i {delta}
 *   resize <i> [--from=x,y,z] [--to=x,y,z]      PATCH /api/cuboids/:i
 *   rotate <i> <axis> <angle> [--origin=x,y,z]  PATCH /api/cuboids/:i {rotation}
 *   delete <i>                                  DELETE /api/cuboids/:i
 *   duplicate <i> [<dx,dy,dz>]                  POST /api/cuboids/:i/duplicate
 *   paint <i> <face> <slug>                     POST /api/paint
 *   paint-all <i> <slug>                        POST /api/paint (all 6 faces)
 *   list-textures [<scope>] [<filter>]          GET /api/textures
 *   reference <path|url|base64> <value>         POST /api/state/reference
 *   selection <i> <face>                        POST /api/state/selection
 *   selection clear                             POST /api/state/selection (null)
 *   screenshot [<camera>] [<out-path>]          POST /api/screenshot → save PNG
 *                                               (returns rendered_view: {image_id, ...})
 *   crop <image_id> <x0,y0,x1,y1> [<out-path>]  POST /api/screenshot/crop
 *   crop-model-view <view_id> <part> [<out>] [--pad=N]
 *                                               POST /api/screenshot/crop_model_view
 *   save [<slug>]                               POST /api/state/save
 *   export-bb [<slug>]                          POST /api/state/export-bb
 *   models                                      GET /api/models
 *
 * Used by .claude/skills/build-model/SKILL.md for the structured workflow.
 */
import { writeFile } from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { randomUUID } from 'node:crypto'

const BASE = process.env.ASTERA_STUDIO_API_URL ?? 'http://localhost:3008'

function parseVec3(s: string): [number, number, number] {
  const parts = s.split(',').map((x) => Number(x.trim()))
  if (parts.length !== 3 || parts.some((n) => !Number.isFinite(n))) {
    throw new Error(`expected Vec3 "x,y,z", got ${s}`)
  }
  return [parts[0]!, parts[1]!, parts[2]!]
}

function parseFlags(args: string[]): { positional: string[]; flags: Record<string, string> } {
  const positional: string[] = []
  const flags: Record<string, string> = {}
  for (const a of args) {
    const m = a.match(/^--([\w-]+)(?:=(.*))?$/)
    if (m) flags[m[1]!] = m[2] ?? 'true'
    else positional.push(a)
  }
  return { positional, flags }
}

async function call(method: 'GET' | 'POST' | 'PATCH' | 'DELETE', pathSuffix: string, body?: unknown) {
  const url = `${BASE}${pathSuffix}`
  const init: RequestInit = { method, headers: {} }
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' }
    init.body = JSON.stringify(body)
  }
  const res = await fetch(url, init)
  const text = await res.text()
  let parsed: unknown = text
  try {
    parsed = JSON.parse(text)
  } catch {}
  if (!res.ok) {
    console.error(`[studio] ${method} ${url} → HTTP ${res.status}`)
    console.error(typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2))
    process.exit(1)
  }
  return parsed
}

type RenderedView = {
  view_id: string
  image_id: string
  width: number
  height: number
  bytes_size: number
}

async function fetchPngBytes(image_id: string): Promise<Buffer> {
  const res = await fetch(`${BASE}/api/screenshot/bytes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ image_id }),
  })
  if (!res.ok) {
    const text = await res.text()
    console.error(`[studio] POST ${BASE}/api/screenshot/bytes → HTTP ${res.status}`)
    console.error(text)
    process.exit(1)
  }
  return Buffer.from(await res.arrayBuffer())
}

function printJson(v: unknown) {
  console.log(JSON.stringify(v, null, 2))
}

const [, , verb, ...rest] = process.argv
const { positional, flags } = parseFlags(rest)

async function main() {
  switch (verb) {
    case undefined:
    case 'help':
    case '-h':
    case '--help':
      console.log(readUsage())
      return

    case 'state':
      printJson(await call('GET', '/api/state'))
      return

    case 'models':
      printJson(await call('GET', '/api/models'))
      return

    // -- ASDSL (Astera Shape DSL — high-level design intent layer) --

    case 'asdsl': {
      const [sub, ...subArgs] = positional
      switch (sub) {
        case undefined:
        case 'show':
        case 'get':
          printJson(await call('GET', '/api/asdsl/'))
          return
        case 'reset':
          printJson(await call('POST', '/api/asdsl/reset', { name: subArgs[0] ?? '' }))
          return
        case 'describe':
          printJson(await call('GET', '/api/asdsl/describe'))
          return
        case 'place': {
          // asdsl place <name> <json-spec>
          // Example: asdsl place blade '{"kind":"tapered_prism",...}'
          const [name, spec] = subArgs
          if (!name || !spec)
            throw new Error('usage: asdsl place <name> <json-spec>')
          printJson(
            await call('POST', '/api/asdsl/place_part', {
              name,
              part: JSON.parse(spec),
            }),
          )
          return
        }
        case 'patch': {
          const [name, patch] = subArgs
          if (!name || !patch)
            throw new Error('usage: asdsl patch <name> <json-patch>')
          printJson(
            await call('POST', '/api/asdsl/patch_part', {
              name,
              patch: JSON.parse(patch),
            }),
          )
          return
        }
        case 'remove': {
          const [name] = subArgs
          if (!name) throw new Error('usage: asdsl remove <name>')
          printJson(await call('POST', '/api/asdsl/remove_part', { name }))
          return
        }
        case 'set-var': {
          const [name, value] = subArgs
          if (!name || value === undefined)
            throw new Error('usage: asdsl set-var <name> <value>')
          printJson(
            await call('POST', '/api/asdsl/set_variable', {
              name,
              value: Number(value),
            }),
          )
          return
        }
        case 'set-quality': {
          const [quality] = subArgs
          if (!quality || !['low', 'medium', 'high'].includes(quality))
            throw new Error('usage: asdsl set-quality <low|medium|high>')
          printJson(
            await call('POST', '/api/asdsl/set_quality', { quality }),
          )
          return
        }
        case 'set-tex': {
          const [slot, slug] = subArgs
          if (!slot || !slug)
            throw new Error('usage: asdsl set-tex <slot> <slug>')
          printJson(
            await call('POST', '/api/asdsl/set_texture', { slot, slug }),
          )
          return
        }
        case 'set-face-tex': {
          const [part_name, face, slot] = subArgs
          if (!part_name || !face || !slot)
            throw new Error('usage: asdsl set-face-tex <part> <face> <slot>')
          printJson(
            await call('POST', '/api/asdsl/set_face_texture', {
              part_name,
              face,
              slot,
            }),
          )
          return
        }
        case 'load-file': {
          // Read an ASDSL JSON file from disk and replay every place_part,
          // set_texture etc. into the server. Used to bootstrap from a saved
          // *.asdsl.json (e.g. example template).
          const [filePath] = subArgs
          if (!filePath) throw new Error('usage: asdsl load-file <path>')
          const { readFileSync } = await import('node:fs')
          const raw = readFileSync(filePath, 'utf8')
          const asdsl = JSON.parse(raw) as {
            name?: string
            params?: Record<string, number>
            textures?: Record<string, string>
            parts?: Record<string, unknown>
            face_overrides?: Record<string, Record<string, string>>
          }
          await call('POST', '/api/asdsl/reset', { name: asdsl.name ?? '' })
          if ((asdsl as { quality?: string }).quality) {
            await call('POST', '/api/asdsl/set_quality', {
              quality: (asdsl as { quality?: string }).quality,
            })
          }
          for (const [name, value] of Object.entries(asdsl.params ?? {})) {
            await call('POST', '/api/asdsl/set_variable', { name, value })
          }
          for (const [slot, slug] of Object.entries(asdsl.textures ?? {})) {
            await call('POST', '/api/asdsl/set_texture', { slot, slug })
          }
          for (const [name, part] of Object.entries(asdsl.parts ?? {})) {
            await call('POST', '/api/asdsl/place_part', { name, part })
          }
          for (const [part_name, faces] of Object.entries(
            asdsl.face_overrides ?? {},
          )) {
            for (const [face, slot] of Object.entries(faces ?? {})) {
              await call('POST', '/api/asdsl/set_face_texture', {
                part_name,
                face,
                slot,
              })
            }
          }
          printJson(await call('GET', '/api/asdsl/describe'))
          return
        }
        default:
          throw new Error(
            `unknown asdsl sub: ${sub}. Try: show, reset, describe, place, patch, remove, set-var, set-tex, set-face-tex, load-file`,
          )
      }
    }

    case 'load': {
      const [scope, slug] = positional
      if (!scope || !slug) throw new Error('usage: load <scope> <slug>')
      printJson(await call('POST', '/api/state/load', { scope, slug }))
      return
    }

    case 'templates':
      printJson(await call('GET', '/api/state/templates'))
      return

    case 'load-template': {
      const [category, name, slug] = positional
      if (!category || !name)
        throw new Error('usage: load-template <category> <name> [<slug>]')
      const body: Record<string, unknown> = { category, name }
      if (slug) body.slug = slug
      printJson(await call('POST', '/api/state/load-template', body))
      return
    }

    case 'new': {
      const parent = flags.parent
      printJson(
        await call('POST', '/api/cuboids/_new', parent ? { parent } : {}),
      )
      return
    }

    case 'taper-blade': {
      const [base, length, baseW, tipW, segments] = positional
      if (!base || !length || !baseW || !tipW || !segments)
        throw new Error(
          'usage: taper-blade <base> <length> <base_w> <tip_w> <segments> [--thickness=N --curve=linear|convex|concave]',
        )
      const body: Record<string, unknown> = {
        base: parseVec3(base),
        length: Number(length),
        base_width: Number(baseW),
        tip_width: Number(tipW),
        segments: Number(segments),
      }
      if (flags.thickness) body.thickness = Number(flags.thickness)
      if (flags.curve) body.curve = flags.curve
      printJson(await call('POST', '/api/composite/tapered-blade', body))
      return
    }

    case 'crossguard': {
      const [center, width, height, depth] = positional
      if (!center || !width || !height || !depth)
        throw new Error(
          'usage: crossguard <center> <width> <height> <depth> [--shape=T|T+collar|X]',
        )
      const body: Record<string, unknown> = {
        center: parseVec3(center),
        width: Number(width),
        height: Number(height),
        depth: Number(depth),
      }
      if (flags.shape) body.shape = flags.shape
      if (flags.collarScale) body.collarScale = Number(flags.collarScale)
      printJson(await call('POST', '/api/composite/crossguard', body))
      return
    }

    case 'cylinder': {
      const [base, length, diameter, axis] = positional
      if (!base || !length || !diameter || !axis)
        throw new Error(
          'usage: cylinder <base> <length> <diameter> <axis=x|y|z> [--segments=N --roundness=1|2|3]',
        )
      const body: Record<string, unknown> = {
        base: parseVec3(base),
        length: Number(length),
        diameter: Number(diameter),
        axis,
      }
      if (flags.segments) body.segments = Number(flags.segments)
      if (flags.roundness) body.roundness = Number(flags.roundness)
      printJson(await call('POST', '/api/composite/cylinder', body))
      return
    }

    case 'sphere': {
      const [center, diameter] = positional
      if (!center || !diameter)
        throw new Error('usage: sphere <center> <diameter> [--layers=3|5|7]')
      const body: Record<string, unknown> = {
        center: parseVec3(center),
        diameter: Number(diameter),
      }
      if (flags.layers) body.layers = Number(flags.layers)
      printJson(await call('POST', '/api/composite/sphere', body))
      return
    }

    case 'ring': {
      const [center, axis, outer, inner, height] = positional
      if (!center || !axis || !outer || !inner || !height)
        throw new Error(
          'usage: ring <center> <axis=x|y|z> <outer> <inner> <height> [--resolution=N]',
        )
      const body: Record<string, unknown> = {
        center: parseVec3(center),
        axis,
        outer: Number(outer),
        inner: Number(inner),
        height: Number(height),
      }
      if (flags.resolution) body.resolution = Number(flags.resolution)
      printJson(await call('POST', '/api/composite/ring', body))
      return
    }

    case 'add-cuboid': {
      const [from, to] = positional
      if (!from || !to) throw new Error('usage: add-cuboid <from> <to>')
      const body: Record<string, unknown> = {
        from: parseVec3(from),
        to: parseVec3(to),
      }
      if (flags['rot-axis'] && flags['rot-angle']) {
        body.rotation = {
          axis: flags['rot-axis'],
          angle: Number(flags['rot-angle']),
          origin: flags['rot-origin']
            ? parseVec3(flags['rot-origin'])
            : [
                (body.from as [number, number, number])[0] / 2 +
                  (body.to as [number, number, number])[0] / 2,
                (body.from as [number, number, number])[1] / 2 +
                  (body.to as [number, number, number])[1] / 2,
                (body.from as [number, number, number])[2] / 2 +
                  (body.to as [number, number, number])[2] / 2,
              ],
        }
      }
      printJson(await call('POST', '/api/cuboids', body))
      return
    }

    case 'move': {
      const [i, delta] = positional
      if (!i || !delta) throw new Error('usage: move <i> <dx,dy,dz>')
      printJson(
        await call('PATCH', `/api/cuboids/${i}`, { delta: parseVec3(delta) }),
      )
      return
    }

    case 'resize': {
      const [i] = positional
      if (!i) throw new Error('usage: resize <i> [--from=x,y,z] [--to=x,y,z]')
      const body: Record<string, unknown> = {}
      if (flags.from) body.from = parseVec3(flags.from)
      if (flags.to) body.to = parseVec3(flags.to)
      if (!body.from && !body.to)
        throw new Error('--from or --to required for resize')
      printJson(await call('PATCH', `/api/cuboids/${i}`, body))
      return
    }

    case 'rotate': {
      const [i, axis, angle] = positional
      if (!i || !axis || !angle)
        throw new Error('usage: rotate <i> <axis> <angle> [--origin=x,y,z]')
      const body: Record<string, unknown> = {
        rotation: {
          axis,
          angle: Number(angle),
          ...(flags.origin ? { origin: parseVec3(flags.origin) } : {}),
        },
      }
      printJson(await call('PATCH', `/api/cuboids/${i}`, body))
      return
    }

    case 'delete': {
      const [i] = positional
      if (!i) throw new Error('usage: delete <i>')
      printJson(await call('DELETE', `/api/cuboids/${i}`))
      return
    }

    case 'duplicate': {
      const [i, delta] = positional
      if (!i) throw new Error('usage: duplicate <i> [<dx,dy,dz>]')
      const body = delta ? { delta: parseVec3(delta) } : {}
      printJson(await call('POST', `/api/cuboids/${i}/duplicate`, body))
      return
    }

    case 'paint': {
      const [i, face, slug] = positional
      if (!i || !face || !slug) throw new Error('usage: paint <i> <face> <slug>')
      printJson(
        await call('POST', '/api/paint', {
          element_index: Number(i),
          face,
          slug,
        }),
      )
      return
    }

    case 'paint-all': {
      const [i, slug] = positional
      if (!i || !slug) throw new Error('usage: paint-all <i> <slug>')
      printJson(
        await call('POST', '/api/paint', {
          element_index: Number(i),
          faces: ['east', 'west', 'up', 'down', 'south', 'north'],
          slug,
        }),
      )
      return
    }

    case 'list-textures': {
      const [scope, filter] = positional
      const qs = new URLSearchParams()
      if (scope) qs.set('scope', scope)
      if (filter) qs.set('filter', filter)
      const q = qs.toString()
      printJson(await call('GET', `/api/textures${q ? `?${q}` : ''}`))
      return
    }

    case 'reference': {
      const [kind, value] = positional
      if (!kind || !value) throw new Error('usage: reference <path|url|base64> <value>')
      if (kind !== 'path' && kind !== 'url' && kind !== 'base64') {
        throw new Error(`kind must be path|url|base64, got ${kind}`)
      }
      printJson(await call('POST', '/api/state/reference', { kind, value }))
      return
    }

    case 'selection': {
      const [a, b] = positional
      if (a === 'clear' || a === 'null') {
        printJson(await call('POST', '/api/state/selection', { selectedFace: null }))
        return
      }
      if (!a || !b) throw new Error('usage: selection <i> <face>  |  selection clear')
      printJson(
        await call('POST', '/api/state/selection', {
          selectedFace: { elementIndex: Number(a), face: b },
        }),
      )
      return
    }

    case 'screenshot': {
      // 2-call dance per ADR-0062 §5: POST /api/screenshot returns a
      // RenderedView (image_id + metadata, no inline bytes); a follow-up
      // POST /api/screenshot/bytes materializes the PNG on disk.
      const [camera, outPath] = positional
      const body: Record<string, unknown> = {}
      if (camera) body.camera = camera
      const res = (await call('POST', '/api/screenshot', body)) as {
        ok: true
        rendered_view: RenderedView
      }
      const bytes = await fetchPngBytes(res.rendered_view.image_id)
      const dest =
        outPath ?? path.join(os.tmpdir(), `studio-screenshot-${randomUUID().slice(0, 8)}.png`)
      await writeFile(dest, bytes)
      console.log(dest)
      printJson({ ok: true, path: dest, rendered_view: res.rendered_view })
      return
    }

    case 'iter-screenshot': {
      // iter-screenshot <run-id> <step-label> [<camera1,camera2,...>]
      // Dumps screenshots to tools/studio/web/.iter/<run-id>/<step-label>-<camera>.png
      // Returns the directory path as the last line so callers can `cd` to it.
      const [runId, label, camerasArg] = positional
      if (!runId || !label)
        throw new Error('usage: iter-screenshot <run-id> <step-label> [<cam1,cam2>]')
      const cameras = (camerasArg ?? 'iso,item_gui,front').split(',')
      const iterDir = path.resolve(
        path.dirname(import.meta.dir ?? '.'),
        '.iter',
        runId,
      )
      const { mkdir } = await import('node:fs/promises')
      await mkdir(iterDir, { recursive: true })
      const results: Array<{
        camera: string
        path: string
        image_id: string
        width: number
        height: number
        bytes_size: number
      }> = []
      for (const camera of cameras) {
        const r = (await call('POST', '/api/screenshot', { camera })) as {
          ok: true
          rendered_view: RenderedView
        }
        const bytes = await fetchPngBytes(r.rendered_view.image_id)
        const dest = path.join(iterDir, `${label}-${camera}.png`)
        await writeFile(dest, bytes)
        results.push({
          camera,
          path: dest,
          image_id: r.rendered_view.image_id,
          width: r.rendered_view.width,
          height: r.rendered_view.height,
          bytes_size: r.rendered_view.bytes_size,
        })
      }
      console.log(JSON.stringify({ ok: true, dir: iterDir, results }, null, 2))
      return
    }

    case 'crop': {
      // crop <image_id> <x0,y0,x1,y1> [<out-path>]
      const [imageId, bboxStr, outPath] = positional
      if (!imageId || !bboxStr) {
        throw new Error('usage: crop <image_id> <x0,y0,x1,y1> [<out-path>]')
      }
      const nums = bboxStr.split(',').map((s) => Number(s.trim()))
      if (nums.length !== 4 || nums.some((n) => !Number.isFinite(n))) {
        throw new Error(`expected bbox "x0,y0,x1,y1", got ${bboxStr}`)
      }
      const bbox = { x0: nums[0]!, y0: nums[1]!, x1: nums[2]!, y1: nums[3]! }
      const res = (await call('POST', '/api/screenshot/crop', {
        image_id: imageId,
        bbox,
      })) as { ok: true; rendered_view: RenderedView }
      const bytes = await fetchPngBytes(res.rendered_view.image_id)
      const dest =
        outPath ??
        path.join(os.tmpdir(), `studio-crop-${randomUUID().slice(0, 8)}.png`)
      await writeFile(dest, bytes)
      printJson({ ok: true, path: dest, rendered_view: res.rendered_view })
      return
    }

    case 'crop-model-view': {
      // crop-model-view <view_id> <part_name> [<out-path>] [--pad=N]
      const [viewId, partName, outPath] = positional
      if (!viewId || !partName) {
        throw new Error('usage: crop-model-view <view_id> <part_name> [<out-path>] [--pad=N]')
      }
      const body: Record<string, unknown> = { view_id: viewId, part_name: partName }
      if (flags.pad) body.pad = Number(flags.pad)
      const res = (await call('POST', '/api/screenshot/crop_model_view', body)) as {
        ok: true
        rendered_view: RenderedView
      }
      const bytes = await fetchPngBytes(res.rendered_view.image_id)
      const dest =
        outPath ??
        path.join(os.tmpdir(), `studio-crop-${viewId}-${partName}-${randomUUID().slice(0, 6)}.png`)
      await writeFile(dest, bytes)
      printJson({ ok: true, path: dest, rendered_view: res.rendered_view })
      return
    }

    case 'save': {
      const [slug] = positional
      printJson(await call('POST', '/api/state/save', slug ? { slug } : {}))
      return
    }

    case 'benchmark:gif': {
      // Delegate to build-benchmark-gif.ts (ForgeCAD-style timestamped GIF
      // archive — Tier C10, manual-inspection only). See
      // docs/research/model-gen-llm-survey.md §9.1.7 and the script's header.
      const scriptPath = path.resolve(
        path.dirname(new URL(import.meta.url).pathname),
        'build-benchmark-gif.ts',
      )
      const passthrough = process.argv.slice(3)
      const proc = Bun.spawn(['bun', 'run', scriptPath, ...passthrough], {
        stdout: 'inherit',
        stderr: 'inherit',
      })
      const code = await proc.exited
      process.exit(code)
    }

    case 'export-bb': {
      const [slug] = positional
      printJson(await call('POST', '/api/state/export-bb', slug ? { slug } : {}))
      return
    }

    default:
      console.error(`unknown verb: ${verb}`)
      console.error(readUsage())
      process.exit(2)
  }
}

function readUsage(): string {
  // Return the verbs section of the docstring above.
  return `usage: bun run studio <verb> [args...]

  state                              print current server state
  asdsl <sub> ...                    high-level shape DSL — sub ∈ {show,reset,describe,
                                     place,patch,remove,set-var,set-tex,
                                     set-face-tex,load-file}
  load <scope> <slug>                load samples/<slug>.model.json into state
  new [--parent=item/handheld]       start a blank model
  add-cuboid <from> <to> [--rot-axis=y --rot-angle=22.5 --rot-origin=x,y,z]
  taper-blade <base> <length> <base_w> <tip_w> <segments> [--thickness=N --curve=linear|convex|concave]
                                     N tapered cuboids in one shot
  crossguard <center> <width> <height> <depth> [--shape=T|T+collar|X]
                                     sword crossguard (T-bar / X-cross / collar)
  cylinder <base> <length> <diameter> <axis> [--segments=N --roundness=1|2|3]
                                     axis-stacked cylinder approximation
  sphere <center> <diameter> [--layers=3|5|7]
                                     voxel sphere (~30-130 cuboids)
  ring <center> <axis> <outer> <inner> <height> [--resolution=N]
                                     voxel annulus (handle wrap, decor band)
  move <i> <dx,dy,dz>                shift cuboid by delta
  resize <i> [--from=...] [--to=...]
  rotate <i> <axis> <angle> [--origin=...]
  delete <i>
  duplicate <i> [<dx,dy,dz>]
  paint <i> <face> <slug>            face ∈ {north,south,east,west,up,down}
  paint-all <i> <slug>               apply slug to all 6 faces
  list-textures [<scope>] [<filter>] scope ∈ {astera,vanilla,content,all}
  reference <kind> <value>           kind ∈ {path,url,base64}
  selection <i> <face>  |  selection clear
  screenshot [<camera>] [<out-path>] camera ∈ {iso,front,top,side,item_gui}
                                     prints path + rendered_view JSON {image_id, ...}
  iter-screenshot <run-id> <step-label> [<cam1,cam2>]
                                     dump screenshots to .iter/<run-id>/<step>-<cam>.png
                                     JSON output gains image_id + bytes_size per camera
  crop <image_id> <x0,y0,x1,y1> [<out-path>]
                                     crop cached image to pixel bbox (no re-render)
  crop-model-view <view_id> <part_name> [<out-path>] [--pad=N]
                                     auto-crop latest cached screenshot to part's
                                     projected bbox; --pad defaults to 4 pixels
  templates                          list available templates
  load-template <category> <name> [<slug>]
                                     start from a curated template
  save [<slug>]                      write content/models/<slug>.model.json
  export-bb [<slug>]                 write content/models/<slug>.bbmodel
  models                             list .model.json on disk
  benchmark:gif <RUN_ID> --slug <slug> --model <model-id> [--run-version <N>] [--view <iso|item_gui|front>]
                                     ForgeCAD-style timestamped GIF archive of an
                                     iteration run (manual-inspection, Tier C10)
`
}

main().catch((e) => {
  console.error(`[studio] ${e.message ?? e}`)
  process.exit(1)
})
