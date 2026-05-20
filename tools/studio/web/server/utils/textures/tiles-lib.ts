import { readdirSync, rmSync } from 'node:fs'
import path from 'node:path'
import { jobFinalDir, resolveJobDir } from '../paths'

/** Defense: slug / category / filename must not escape content/textures/. */
export function safeRelPath(rel: string): string {
  const norm = path.posix.normalize(rel).replace(/^\/+/, '')
  if (norm.startsWith('../') || norm.includes('/../') || path.isAbsolute(norm)) {
    throw new Error(`unsafe path: ${rel}`)
  }
  return norm
}

/**
 * ジョブが「全 tile レビュー済み」になったら dir ごと消す。残しても価値が
 * ない (生成物は content/textures/ に flush 済み、未採用 tile はゼロ) ため、
 * ここで掃除しないと「0 タイル」ジョブが永久にディスクに溜まる。
 */
export function cleanupJobIfDrained(jobId: string): void {
  const dir = resolveJobDir(jobId)
  if (!dir) return
  const finalDir = jobFinalDir(jobId)
  let remaining = 0
  try {
    remaining = readdirSync(finalDir).filter((f) => f.endsWith('.png')).length
  } catch {
    // final/ が無い = もう空、削除対象
  }
  if (remaining === 0) {
    rmSync(dir, { recursive: true, force: true })
  }
}
