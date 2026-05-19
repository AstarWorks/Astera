/**
 * Anchor resolver unit tests, with focus on @cad[name] handle support
 * (ADR-0062 B11) and resolve() topo order.
 */
import { test, expect, describe } from 'bun:test'
import { unwrapHandle, resolve } from './anchor'
import type { Asdsl } from './types'

function emptyAsdsl(): Asdsl {
  return { name: 'test', params: {}, parts: {}, textures: {}, face_overrides: {} }
}

describe('unwrapHandle', () => {
  test('strips @cad[...] wrapper', () => {
    expect(unwrapHandle('@cad[blade]')).toBe('blade')
  })

  test('passes bare name through', () => {
    expect(unwrapHandle('blade')).toBe('blade')
  })

  test('handles dotted names inside brackets', () => {
    expect(unwrapHandle('@cad[blade_v2]')).toBe('blade_v2')
  })

  test('does not unwrap malformed handle', () => {
    expect(unwrapHandle('@cad[unclosed')).toBe('@cad[unclosed')
    expect(unwrapHandle('cad[blade]')).toBe('cad[blade]')
  })
})

describe('resolve() with @cad[] refs', () => {
  test('resolves @cad[blade] equivalently to bare blade', () => {
    const asdsl = emptyAsdsl()
    asdsl.parts.blade = { kind: 'cuboid', size: [4, 4, 4] }
    asdsl.parts.guard = {
      kind: 'cuboid',
      size: [6, 1, 3],
      relative_to: '@cad[blade]',
      align: 'top_center',
    }
    const r = resolve(asdsl)
    expect(r.ok).toBe(true)
    if (!r.ok) throw new Error('expected ok')
    expect(r.order).toEqual(['blade', 'guard'])
    expect(r.resolved.get('guard')!.bbox.min[1]).toBeGreaterThanOrEqual(4)
  })

  test('UNKNOWN_PART surfaces for missing @cad[] target', () => {
    const asdsl = emptyAsdsl()
    asdsl.parts.guard = {
      kind: 'cuboid',
      size: [6, 1, 3],
      relative_to: '@cad[nonexistent]',
      align: 'top_center',
    }
    const r = resolve(asdsl)
    expect(r.ok).toBe(false)
    if (r.ok) throw new Error('expected fail')
    expect(r.error_code).toBe('UNKNOWN_PART')
  })

  test('DEPENDENCY_CYCLE detected even when refs are @cad[] handles', () => {
    const asdsl = emptyAsdsl()
    asdsl.parts.a = {
      kind: 'cuboid',
      size: [4, 4, 4],
      relative_to: '@cad[b]',
      align: 'top_center',
    }
    asdsl.parts.b = {
      kind: 'cuboid',
      size: [4, 4, 4],
      relative_to: '@cad[a]',
      align: 'top_center',
    }
    const r = resolve(asdsl)
    expect(r.ok).toBe(false)
    if (r.ok) throw new Error('expected fail')
    expect(r.error_code).toBe('DEPENDENCY_CYCLE')
  })

  test('empty asdsl resolves to empty order', () => {
    const r = resolve(emptyAsdsl())
    expect(r.ok).toBe(true)
    if (!r.ok) throw new Error('expected ok')
    expect(r.order).toEqual([])
  })
})
