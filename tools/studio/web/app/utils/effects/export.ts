/**
 * fxgraph → exportable artifacts (client-side).
 *
 * Walks every frame via the existing render engine, packs into the requested
 * formats, returns base64-encoded PNG bytes for the server to write to disk.
 * The server has no rendering knowledge — it just demultiplexes the bag of
 * artifacts to filesystem paths.
 *
 * Output paths follow content/textures/README.md conventions:
 *   content/textures/animations/<basename>.png             (vertical sheet)
 *   content/textures/animations/<basename>.mcmeta.json     (MC flipbook meta)
 *   content/textures/animations/<basename>_horiz.png       (horizontal sheet)
 *   content/textures/animations/<basename>_<NN>.png        (per-frame)
 *   content/models/effects/<basename>.model.json           (item_display model)
 *   content/effects/<basename>.fxgraph.json                (the recipe)
 */
import type { FxGraph, ExportFormat } from '#shared/effects/types'
import { renderFrame } from './render'
import { summonHelpFile } from './summon'

export type PackedExport = {
  basename: string
  formats: ExportFormat[]
  /** PNG bytes, base64-encoded; server decodes + writes. */
  vertical_sheet_b64?: string
  horizontal_sheet_b64?: string
  /** Per-frame PNGs, base64-encoded, indexed 0..frames-1. */
  frames_b64?: string[]
  /** MC animation metadata — keyed on whether vertical_sheet is included. */
  mcmeta?: McMetaAnimation
  /** Vanilla item model JSON for a 1×1 item_display billboard. */
  model_json?: ItemDisplayModel
  /** The fxgraph itself; server commits this as the recipe. */
  fxgraph: FxGraph
  /** Markdown help file describing the resource-pack paths + summon command. */
  summon_help?: string
}

export type McMetaAnimation = {
  animation: {
    frametime: number
    interpolate?: boolean
    frames?: number[]
  }
}

export type ItemDisplayModel = {
  parent: string
  textures: Record<string, string>
}

/** Render all N frames in order, return as an array of OffscreenCanvases. */
export function renderAllFrames(graph: FxGraph): OffscreenCanvas[] {
  const out: OffscreenCanvas[] = []
  for (let f = 0; f < graph.frames; f++) {
    out.push(renderFrame(graph, f))
  }
  return out
}

/** Stitch frames into a single vertical sprite sheet — Mojang convention. */
export function packVertical(graph: FxGraph, frames: OffscreenCanvas[]): OffscreenCanvas {
  const [w, h] = graph.size
  const out = new OffscreenCanvas(w, h * frames.length)
  const g = out.getContext('2d') as OffscreenCanvasRenderingContext2D
  g.imageSmoothingEnabled = false
  for (let i = 0; i < frames.length; i++) {
    g.drawImage(frames[i], 0, i * h)
  }
  return out
}

/** Stitch frames into a single horizontal sprite sheet. */
export function packHorizontal(graph: FxGraph, frames: OffscreenCanvas[]): OffscreenCanvas {
  const [w, h] = graph.size
  const out = new OffscreenCanvas(w * frames.length, h)
  const g = out.getContext('2d') as OffscreenCanvasRenderingContext2D
  g.imageSmoothingEnabled = false
  for (let i = 0; i < frames.length; i++) {
    g.drawImage(frames[i], i * w, 0)
  }
  return out
}

export function mcmetaFor(graph: FxGraph): McMetaAnimation {
  return {
    animation: {
      frametime: graph.frame_time_ticks,
      interpolate: false,
    },
  }
}

/**
 * Generate a minimal vanilla MC item model for an item_display billboard
 * that uses the exported animated texture. Compatible with `item/generated`
 * (flat 1×1 quad with the texture). Slug refers to the texture path under
 * the resource pack's `assets/astera/textures/effects/<basename>.png`.
 */
export function itemDisplayModel(basename: string): ItemDisplayModel {
  return {
    parent: 'item/generated',
    textures: {
      layer0: `astera:effects/${basename}`,
    },
  }
}

async function canvasToBase64(canvas: OffscreenCanvas): Promise<string> {
  const blob = await canvas.convertToBlob({ type: 'image/png' })
  const buf = await blob.arrayBuffer()
  const bytes = new Uint8Array(buf)
  // Convert to base64 without exceeding stack on large arrays — chunk it.
  let s = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    s += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + CHUNK)))
  }
  return btoa(s)
}

export async function packExport(
  graph: FxGraph,
  basename: string,
  formats: ExportFormat[],
): Promise<PackedExport> {
  const frames = renderAllFrames(graph)
  const out: PackedExport = { basename, formats, fxgraph: graph }
  if (formats.includes('vertical_sheet')) {
    const sheet = packVertical(graph, frames)
    out.vertical_sheet_b64 = await canvasToBase64(sheet)
    out.mcmeta = mcmetaFor(graph)
  }
  if (formats.includes('horizontal_sheet')) {
    const sheet = packHorizontal(graph, frames)
    out.horizontal_sheet_b64 = await canvasToBase64(sheet)
  }
  if (formats.includes('frames')) {
    const perFrame: string[] = []
    for (const f of frames) perFrame.push(await canvasToBase64(f))
    out.frames_b64 = perFrame
  }
  if (formats.includes('item_display_model')) {
    out.model_json = itemDisplayModel(basename)
  }
  // Always include the summon help — costs almost nothing, super useful.
  out.summon_help = summonHelpFile(graph, basename)
  return out
}
