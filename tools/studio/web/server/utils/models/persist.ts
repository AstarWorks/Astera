/**
 * Astera Studio state persistence — write the in-memory state to a tmp JSON
 * file after every mutation, restore on module load. Survives `bun --hot`
 * reloads (which wipe module singletons) so a CLI workflow that spans
 * multiple shell invocations doesn't lose progress mid-flow.
 *
 * Stored at /tmp/astera-studio-state-<hash>.json. The <hash> is derived from
 * the repo root path so multiple checkouts on the same machine don't collide.
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import crypto from 'node:crypto'
import { repoRoot } from '../paths'

const repoHash = crypto
  .createHash('sha256')
  .update(repoRoot)
  .digest('hex')
  .slice(0, 12)
const STATE_FILE = path.join(os.tmpdir(), `astera-studio-state-${repoHash}.json`)

export type Persisted<T> = T

let debounceHandle: ReturnType<typeof setTimeout> | null = null
let pending: unknown = null

export function persistDebounced(state: unknown, delayMs = 80) {
  pending = state
  if (debounceHandle) clearTimeout(debounceHandle)
  debounceHandle = setTimeout(flushPersist, delayMs)
}

export function flushPersist() {
  if (debounceHandle) {
    clearTimeout(debounceHandle)
    debounceHandle = null
  }
  if (pending === null) return
  try {
    writeFileSync(STATE_FILE, JSON.stringify(pending))
  } catch (e) {
    console.error('[persist] write failed:', e)
  }
  pending = null
}

export function restore<T>(): T | null {
  if (!existsSync(STATE_FILE)) return null
  try {
    return JSON.parse(readFileSync(STATE_FILE, 'utf8')) as T
  } catch (e) {
    console.error('[persist] restore failed:', e)
    return null
  }
}

export function persistedStateFile(): string {
  return STATE_FILE
}
