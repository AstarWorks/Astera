/**
 * Apply an RFC 6902 JSON Patch to an FxGraph.
 *
 * Uses fast-json-patch (already in package.json from model-gen wave). Returns
 * a fresh FxGraph instance (immutable update); does NOT mutate the input.
 *
 * On failure, throws with the failing op + path so the chat layer can surface
 * the error to the user (and to Claude on the next turn, for self-correction).
 */
import { applyPatch, deepClone, type Operation } from 'fast-json-patch'
import type { FxGraph } from './types'
import type { JsonPatchOp } from '../api'

export function applyJsonPatch(graph: FxGraph, patch: JsonPatchOp[]): FxGraph {
  const cloned = deepClone(graph) as FxGraph
  // fast-json-patch's Operation type is compatible with our JsonPatchOp union.
  const result = applyPatch(cloned, patch as unknown as Operation[], true, false)
  // applyPatch returns an array of results; the last one's newDocument is the
  // final graph state. When mutate=false (4th arg), it returns the patched copy.
  const final = result[result.length - 1]?.newDocument as FxGraph | undefined
  return final ?? cloned
}
