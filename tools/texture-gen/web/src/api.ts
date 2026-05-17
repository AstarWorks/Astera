import type {
  Job,
  JobDetail,
  Manifest,
  ManifestEntry,
  CategoryEntry,
  Tile,
} from './types'

async function j<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!r.ok) throw new Error(`${r.status}: ${await r.text()}`)
  return (await r.json()) as T
}

export const api = {
  jobs: () => j<Job[]>('/api/jobs'),
  job: (id: string) => j<JobDetail>(`/api/jobs/${id}`),
  accept: (
    jobId: string,
    tileName: string,
    body: { slug: string; targetPath: string },
  ) =>
    j<ManifestEntry>(`/api/jobs/${jobId}/tiles/${tileName}/accept`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  reject: (jobId: string, tileName: string) =>
    j<{ ok: true }>(`/api/jobs/${jobId}/tiles/${tileName}/reject`, {
      method: 'POST',
    }),
  regenerate: (jobId: string, tileName: string, refinedPrompt: string) =>
    j<{ tile: Tile }>(
      `/api/jobs/${jobId}/tiles/${tileName}/regenerate`,
      {
        method: 'POST',
        body: JSON.stringify({ refinedPrompt }),
      },
    ),
  generate: (body: { category: string; prompt: string; grid: string }) =>
    j<{ jobId: string }>('/api/generate', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  manifest: () => j<Manifest>('/api/manifest'),
  categories: () => j<CategoryEntry[]>('/api/categories'),
}
