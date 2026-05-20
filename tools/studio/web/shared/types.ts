export type Tile = {
  name: string
  path: string
  bytes: number
}

export type Job = {
  id: string
  fullPath: string
  mtime: string
  tileCount: number
}

export type JobDetail = Job & {
  prompt?: string
  hasGrid?: boolean
  tiles: Tile[]
}

export type ManifestEntry = {
  path: string
  acceptedAt: string
  source: {
    tool: string
    jobId?: string
    tileIndex?: string
    prompt?: string
  }
  reviewedBy: string
  notes: string | null
}

export type Manifest = {
  version: 1
  assets: Record<string, ManifestEntry>
}

export type CategoryEntry = {
  path: string
  fileCount: number
}

// --- MC Java model.json (subset we read/write) ---

export type Vec3 = [number, number, number]
export type Vec4 = [number, number, number, number]

export type FaceName = 'east' | 'west' | 'up' | 'down' | 'south' | 'north'

export type SelectedFace = { elementIndex: number; face: FaceName }

export type Face = {
  uv?: Vec4
  texture: string
  cullface?: 'down' | 'up' | 'north' | 'south' | 'west' | 'east'
  rotation?: 0 | 90 | 180 | 270
  tintindex?: number
}

export type Rotation = {
  origin: Vec3
  axis: 'x' | 'y' | 'z'
  angle: -45 | -22.5 | 0 | 22.5 | 45
  rescale?: boolean
}

export type Element = {
  from: Vec3
  to: Vec3
  rotation?: Rotation
  shade?: boolean
  faces?: Partial<Record<'down' | 'up' | 'north' | 'south' | 'west' | 'east', Face>>
}

export type DisplayTransform = {
  rotation?: Vec3
  translation?: Vec3
  scale?: Vec3
}

export type McModel = {
  parent?: string
  ambientocclusion?: boolean
  textures?: Record<string, string>
  display?: Partial<
    Record<
      | 'thirdperson_righthand'
      | 'thirdperson_lefthand'
      | 'firstperson_righthand'
      | 'firstperson_lefthand'
      | 'gui'
      | 'head'
      | 'ground'
      | 'fixed',
      DisplayTransform
    >
  >
  elements?: Element[]
  overrides?: Array<{ predicate: Record<string, number>; model: string }>
}
