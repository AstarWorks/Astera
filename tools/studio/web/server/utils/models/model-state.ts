/**
 * Astera Studio in-memory model state — singleton.
 *
 * Holds the currently-open MC model.json, the slug it was loaded from, the
 * selected face (for click-to-paint), and the reference image. All mutations
 * (paint, add cuboid, etc.) go through this module so REST handlers, the
 * future Skill CLI, and the WS bridge all see the same SoT.
 *
 * Post-mutation we validate against the Levertion schema and roll back on
 * failure — invalid state never reaches WS subscribers or the browser.
 */
import Ajv, { type ErrorObject, type ValidateFunction } from 'ajv'
import addFormats from 'ajv-formats'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import type { McModel, SelectedFace } from '#shared/types'
import { repoRoot, modelsContentDir, modelSamplesDir } from '../paths'
import { mcModelToBbmodel } from './bbmodel-export'
import { persistDebounced, restore } from './persist'
import { loadTemplateModel } from './templates'
import resourceLocationSchema from './schemas/resource_location.schema.json'
import mcModelSchema from './schemas/mc-model.schema.json'

const ajv = new Ajv({ allErrors: true, strict: false })
addFormats(ajv)
ajv.addSchema(resourceLocationSchema)
const validateModel: ValidateFunction = ajv.compile(mcModelSchema)

export type ReferenceImage =
  | { kind: 'path'; value: string }
  | { kind: 'url'; value: string }
  | { kind: 'base64'; value: string }

export type ModelScope = 'samples' | 'content'

export type State = {
  model: McModel | null
  slug: string | null
  scope: ModelScope | null
  selectedFace: SelectedFace | null
  referenceImage: ReferenceImage | null
}

export type StateEvent =
  | { type: 'model_changed'; model: McModel | null; slug: string | null; scope: ModelScope | null }
  | { type: 'selection_changed'; selectedFace: SelectedFace | null }
  | { type: 'reference_changed'; reference: ReferenceImage | null }

type Listener = (ev: StateEvent) => void

const state: State = restore<State>() ?? {
  model: null,
  slug: null,
  scope: null,
  selectedFace: null,
  referenceImage: null,
}

const listeners = new Set<Listener>()

function persistNow() {
  persistDebounced(state)
}

export function subscribe(fn: Listener): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

function emit(ev: StateEvent) {
  for (const l of listeners) {
    try {
      l(ev)
    } catch (e) {
      console.error('[state] listener threw:', e)
    }
  }
  persistNow()
}

export function getState(): State {
  return state
}

export class ValidationError extends Error {
  constructor(
    message: string,
    public readonly errors: ErrorObject[] | null | undefined,
  ) {
    super(message)
  }
}

/**
 * Apply a mutation as a transaction: clone, mutate, validate, commit-or-rollback.
 * The caller's mutation function should treat `draft` as the new state and
 * mutate in place. If validation fails, the function throws and the singleton
 * is untouched.
 */
function mutate(label: string, mutateFn: (draft: McModel) => void): McModel {
  if (!state.model) {
    throw new Error(`${label}: no model loaded`)
  }
  const draft: McModel = JSON.parse(JSON.stringify(state.model))
  mutateFn(draft)
  const ok = validateModel(draft)
  if (!ok) {
    throw new ValidationError(
      `${label}: model failed schema validation`,
      validateModel.errors,
    )
  }
  state.model = draft
  emit({ type: 'model_changed', model: state.model, slug: state.slug, scope: state.scope })
  return draft
}

export function loadModel(
  scope: ModelScope,
  slug: string,
): { model: McModel; slug: string; scope: ModelScope } {
  const baseDir = scope === 'samples' ? modelSamplesDir : modelsContentDir
  const norm = path.posix.normalize(slug)
  if (norm.startsWith('../') || norm.includes('/../') || path.isAbsolute(norm)) {
    throw new Error(`invalid slug: ${slug}`)
  }
  const filePath = path.join(baseDir, `${norm}.model.json`)
  if (!existsSync(filePath)) {
    throw new Error(`model not found: ${filePath}`)
  }
  const raw = readFileSync(filePath, 'utf8')
  const model = JSON.parse(raw) as McModel
  const ok = validateModel(model)
  if (!ok) {
    throw new ValidationError(
      `loaded model failed schema validation: ${slug}`,
      validateModel.errors,
    )
  }
  state.model = model
  state.slug = slug
  state.scope = scope
  state.selectedFace = null
  emit({ type: 'model_changed', model, slug, scope })
  emit({ type: 'selection_changed', selectedFace: null })
  return { model, slug, scope }
}

export function setSelectedFace(face: SelectedFace | null) {
  state.selectedFace = face
  emit({ type: 'selection_changed', selectedFace: face })
}

export function setReferenceImage(ref: ReferenceImage | null) {
  state.referenceImage = ref
  emit({ type: 'reference_changed', reference: ref })
}

export type Face = 'east' | 'west' | 'up' | 'down' | 'south' | 'north'

function allocateLayer(model: McModel, slug: string): string {
  const textures = model.textures ?? (model.textures = {})
  for (const [key, value] of Object.entries(textures)) {
    if (value === slug) return key
  }
  let n = 0
  while (textures[`tex_${n}`]) n++
  textures[`tex_${n}`] = slug
  return `tex_${n}`
}

/**
 * UV that fits the face's dimensions so block textures pixel-tile naturally
 * (instead of stretching the whole 16x16 source onto a thin 2x12 blade face).
 * Width / height / depth come from the cuboid extents in 1/16-block units.
 */
function uvForFace(
  el: { from: [number, number, number]; to: [number, number, number] },
  face: Face,
): [number, number, number, number] {
  const w = Math.abs(el.to[0] - el.from[0])
  const h = Math.abs(el.to[1] - el.from[1])
  const d = Math.abs(el.to[2] - el.from[2])
  let u = 0
  let v = 0
  if (face === 'east' || face === 'west') {
    u = d
    v = h
  } else if (face === 'up' || face === 'down') {
    u = w
    v = d
  } else {
    u = w
    v = h
  }
  // Clamp to schema range [0, 16] for safety; oversize cuboids fall back to 16.
  return [0, 0, Math.min(u, 16), Math.min(v, 16)]
}

export function paint(args: {
  element_index: number
  face?: Face
  faces?: Face[]
  slug: string
  /** Override the auto-computed UV. Rarely needed. */
  uv?: [number, number, number, number]
}): McModel {
  return mutate('paint', (draft) => {
    const element = draft.elements?.[args.element_index]
    if (!element) {
      throw new Error(`paint: element[${args.element_index}] not found`)
    }
    const layer = allocateLayer(draft, args.slug)
    if (!element.faces) element.faces = {}
    const faces = args.faces ?? (args.face ? [args.face] : [])
    if (faces.length === 0) {
      throw new Error('paint: either face or faces must be provided')
    }
    for (const f of faces) {
      const prev = element.faces[f] ?? { texture: `#${layer}` }
      const uv = args.uv ?? uvForFace(element, f)
      element.faces[f] = { ...prev, texture: `#${layer}`, uv }
    }
  })
}

// --- Shape mutations -----------------------------------------------------

type Axis = 'x' | 'y' | 'z'
type Angle = -45 | -22.5 | 0 | 22.5 | 45
type Vec3 = [number, number, number]

const DEFAULT_FACES: Face[] = ['east', 'west', 'up', 'down', 'south', 'north']

function defaultFaces(): Record<Face, { uv: [number, number, number, number]; texture: string }> {
  const all: Partial<Record<Face, { uv: [number, number, number, number]; texture: string }>> = {}
  for (const f of DEFAULT_FACES) {
    all[f] = { uv: [0, 0, 16, 16], texture: '#layer0' }
  }
  return all as Record<Face, { uv: [number, number, number, number]; texture: string }>
}

export type CuboidSpec = {
  from: Vec3
  to: Vec3
  rotation?: { origin: Vec3; axis: Axis; angle: Angle }
}

function pushCuboid(draft: McModel, spec: CuboidSpec): number {
  if (!draft.elements) draft.elements = []
  const element: McModel['elements'] extends (infer T)[] | undefined ? T : never = {
    from: spec.from,
    to: spec.to,
    faces: defaultFaces(),
  }
  if (spec.rotation) element.rotation = spec.rotation
  draft.elements.push(element)
  // Intentionally do NOT pre-populate `textures.layer0` — leaving the map
  // empty (or letting the user/paint set real slugs) means the viewport's
  // texture resolver falls back to palette colors per cuboid, which renders
  // correctly even when no PNG is bound. Pre-setting a placeholder slug
  // caused 404s and a broken texture being cached for the whole model.
  return draft.elements.length - 1
}

export function addCuboid(args: {
  from: Vec3
  to: Vec3
  rotation?: { origin: Vec3; axis: Axis; angle: Angle }
  /** Ignored — MC schema forbids additional properties on element. */
  name?: string
}): { element_index: number; model: McModel } {
  let idx = -1
  const m = mutate('add_cuboid', (draft) => {
    idx = pushCuboid(draft, {
      from: args.from,
      to: args.to,
      rotation: args.rotation,
    })
  })
  return { element_index: idx, model: m }
}

/**
 * Append N cuboids in a single Ajv-validated transaction. Used by composite
 * primitives (tapered-blade, cylinder, sphere, etc.) so the schema check
 * runs once per batch instead of once per cube.
 */
export function addCuboidBatch(
  label: string,
  specs: CuboidSpec[],
): { element_indices: number[]; model: McModel } {
  const indices: number[] = []
  const m = mutate(label, (draft) => {
    for (const spec of specs) {
      indices.push(pushCuboid(draft, spec))
    }
  })
  return { element_indices: indices, model: m }
}

function getElement(draft: McModel, i: number) {
  const el = draft.elements?.[i]
  if (!el) throw new Error(`element[${i}] not found`)
  return el
}

export function moveCuboid(i: number, delta: Vec3): McModel {
  return mutate(`move_cuboid[${i}]`, (draft) => {
    const el = getElement(draft, i)
    el.from = [el.from[0] + delta[0], el.from[1] + delta[1], el.from[2] + delta[2]]
    el.to = [el.to[0] + delta[0], el.to[1] + delta[1], el.to[2] + delta[2]]
  })
}

export function resizeCuboid(
  i: number,
  args: { from?: Vec3; to?: Vec3 },
): McModel {
  return mutate(`resize_cuboid[${i}]`, (draft) => {
    const el = getElement(draft, i)
    if (args.from) el.from = args.from
    if (args.to) el.to = args.to
  })
}

export function rotateCuboid(
  i: number,
  args: { axis: Axis; angle: Angle; origin?: Vec3 },
): McModel {
  return mutate(`rotate_cuboid[${i}]`, (draft) => {
    const el = getElement(draft, i)
    const origin: Vec3 = args.origin ?? [
      (el.from[0] + el.to[0]) / 2,
      (el.from[1] + el.to[1]) / 2,
      (el.from[2] + el.to[2]) / 2,
    ]
    el.rotation = { axis: args.axis, angle: args.angle, origin }
  })
}

export function deleteCuboid(i: number): McModel {
  return mutate(`delete_cuboid[${i}]`, (draft) => {
    if (!draft.elements || !draft.elements[i]) {
      throw new Error(`element[${i}] not found`)
    }
    draft.elements.splice(i, 1)
  })
}

export function duplicateCuboid(
  i: number,
  delta: Vec3 = [0, 0, 0],
): { element_index: number; model: McModel } {
  let idx = -1
  const m = mutate(`duplicate_cuboid[${i}]`, (draft) => {
    const el = getElement(draft, i)
    const copy = JSON.parse(JSON.stringify(el))
    copy.from = [el.from[0] + delta[0], el.from[1] + delta[1], el.from[2] + delta[2]]
    copy.to = [el.to[0] + delta[0], el.to[1] + delta[1], el.to[2] + delta[2]]
    draft.elements!.push(copy)
    idx = draft.elements!.length - 1
  })
  return { element_index: idx, model: m }
}

export function startNewModel(args?: { parent?: string; slug?: string }): McModel {
  const model: McModel = {
    parent: args?.parent ?? 'item/handheld',
    textures: {},
    elements: [],
  }
  state.model = model
  state.slug = args?.slug ?? null
  state.scope = 'content'
  state.selectedFace = null
  emit({ type: 'model_changed', model, slug: state.slug, scope: state.scope })
  emit({ type: 'selection_changed', selectedFace: null })
  return model
}

const modelsRoot = modelsContentDir
const manifestPath = path.join(modelsRoot, 'manifest.json')

type ModelsManifest = {
  version: 1
  models: Record<string, { path: string; bbmodel?: string; updated_at: string }>
}

function readModelsManifest(): ModelsManifest {
  try {
    return JSON.parse(readFileSync(manifestPath, 'utf8'))
  } catch {
    return { version: 1, models: {} }
  }
}

function writeModelsManifest(m: ModelsManifest) {
  mkdirSync(modelsRoot, { recursive: true })
  writeFileSync(manifestPath, JSON.stringify(m, null, 2))
}

function ensureSlug(override?: string): string {
  const slug = override ?? state.slug
  if (!slug) throw new Error('slug required (none currently loaded)')
  const norm = path.posix.normalize(slug)
  if (norm.startsWith('../') || norm.includes('/../') || path.isAbsolute(norm)) {
    throw new Error(`invalid slug: ${slug}`)
  }
  return slug
}

export function saveModel(override?: string): { path: string; slug: string } {
  if (!state.model) throw new Error('save: no model loaded')
  const slug = ensureSlug(override)
  const filePath = path.join(modelsRoot, `${slug}.model.json`)
  mkdirSync(path.dirname(filePath), { recursive: true })
  writeFileSync(filePath, JSON.stringify(state.model, null, 2))
  const manifest = readModelsManifest()
  manifest.models[slug] = {
    ...(manifest.models[slug] ?? {}),
    path: path.relative(repoRoot, filePath),
    updated_at: new Date().toISOString(),
  }
  writeModelsManifest(manifest)
  state.slug = slug
  state.scope = 'content'
  emit({ type: 'model_changed', model: state.model, slug, scope: 'content' })
  return { path: filePath, slug }
}

export function exportBb(override?: string): { path: string; slug: string } {
  if (!state.model) throw new Error('export_bb: no model loaded')
  const slug = ensureSlug(override)
  const bb = mcModelToBbmodel(state.model, { name: slug.split('/').pop() ?? slug })
  const filePath = path.join(modelsRoot, `${slug}.bbmodel`)
  mkdirSync(path.dirname(filePath), { recursive: true })
  writeFileSync(filePath, JSON.stringify(bb, null, 2))
  const manifest = readModelsManifest()
  manifest.models[slug] = {
    ...(manifest.models[slug] ?? {
      path: path.relative(repoRoot, path.join(modelsRoot, `${slug}.model.json`)),
      updated_at: new Date().toISOString(),
    }),
    bbmodel: path.relative(repoRoot, filePath),
    updated_at: new Date().toISOString(),
  }
  writeModelsManifest(manifest)
  return { path: filePath, slug }
}

export function loadTemplate(
  category: string,
  name: string,
  slug?: string,
): { model: McModel; slug: string | null; scope: ModelScope } {
  const model = loadTemplateModel(category, name)
  const ok = validateModel(model)
  if (!ok) {
    throw new ValidationError(
      `template ${category}/${name} failed schema validation`,
      validateModel.errors,
    )
  }
  state.model = model
  state.slug = slug ?? null
  state.scope = 'content'
  state.selectedFace = null
  emit({ type: 'model_changed', model, slug: state.slug, scope: 'content' })
  emit({ type: 'selection_changed', selectedFace: null })
  return { model, slug: state.slug, scope: 'content' }
}

/**
 * Replace the entire model directly — used by the ASDSL compiler when it
 * re-emits the model from the parts dict. Validates against the schema and
 * broadcasts via WS so the Three.js viewport repaints.
 */
export function setModelDirect(model: McModel, slug?: string | null): McModel {
  const okValid = validateModel(model)
  if (!okValid) {
    throw new ValidationError('setModelDirect: schema failed', validateModel.errors)
  }
  state.model = model
  if (slug !== undefined) state.slug = slug
  emit({ type: 'model_changed', model, slug: state.slug, scope: state.scope })
  return model
}

export const _internals = { validateModel, ajv, setModelDirect }
