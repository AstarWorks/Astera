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
