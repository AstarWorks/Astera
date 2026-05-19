/**
 * State mutation unit tests covering ADR-0062 B6 + B7 + B10 behaviors:
 * - placePart rejects ALREADY_EXISTS
 * - patchPart merges fields
 * - finalize() emits summary + diagnostics
 * - setTexture surfaces texture_refs_added
 */
import { test, expect, describe, beforeEach } from 'bun:test'
import { placePart, patchPart, removePart, resetAsdsl, setTexture } from './state'

beforeEach(() => {
  resetAsdsl('test')
})

describe('placePart (B6)', () => {
  test('rejects an existing name with ALREADY_EXISTS', () => {
    const first = placePart('blade', { kind: 'cuboid', size: [4, 4, 4] })
    expect(first.ok).toBe(true)
    const second = placePart('blade', { kind: 'cuboid', size: [6, 6, 6] })
    expect(second.ok).toBe(false)
    expect(second.error_code).toBe('ALREADY_EXISTS')
    expect(second.hints[0]!.code).toBe('ALREADY_EXISTS')
  })

  test('successful place_part records parts_added in changes', () => {
    const env = placePart('blade', { kind: 'cuboid', size: [4, 4, 4] })
    expect(env.ok).toBe(true)
    expect(env.changes.parts_added).toEqual(['blade'])
    expect(env.changes.summary).toContain('added 1 part')
  })

  test('relative_to with @cad[] handle resolves on new part', () => {
    placePart('blade', { kind: 'cuboid', size: [4, 4, 4] })
    const env = placePart('guard', {
      kind: 'cuboid',
      size: [6, 1, 3],
      relative_to: '@cad[blade]',
      align: 'top_center',
    })
    expect(env.ok).toBe(true)
  })
})

describe('patchPart (B6)', () => {
  test('merges patch fields onto existing part', () => {
    placePart('blade', { kind: 'cuboid', size: [4, 4, 4] })
    const env = patchPart('blade', { size: [10, 4, 4] })
    expect(env.ok).toBe(true)
    expect(env.changes.parts_modified).toEqual(['blade'])
  })

  test('returns UNKNOWN_PART for missing target', () => {
    const env = patchPart('nope', { size: [4, 4, 4] })
    expect(env.ok).toBe(false)
    expect(env.error_code).toBe('UNKNOWN_PART')
  })

  test('summary reflects modification', () => {
    placePart('blade', { kind: 'cuboid', size: [4, 4, 4] })
    const env = patchPart('blade', { delta_y: 2 })
    expect(env.changes.summary).toContain('modified')
  })
})

describe('removePart', () => {
  test('removes part and lists in changes', () => {
    placePart('blade', { kind: 'cuboid', size: [4, 4, 4] })
    const env = removePart('blade')
    expect(env.ok).toBe(true)
    expect(env.changes.parts_removed).toEqual(['blade'])
    expect(env.changes.summary).toContain('removed 1')
  })
})

describe('setTexture (B10 texture_refs_added)', () => {
  test('first-time slot binding records texture_refs_added', () => {
    const env = setTexture('blade_body', 'minecraft:block/iron_block')
    expect(env.ok).toBe(true)
    expect(env.changes.texture_refs_added).toEqual(['blade_body'])
  })

  test('rebinding existing slot does not record added', () => {
    setTexture('blade_body', 'minecraft:block/iron_block')
    const env = setTexture('blade_body', 'minecraft:block/gold_block')
    expect(env.changes.texture_refs_added).toBeUndefined()
  })
})

describe('finalize() (B7 + B10 integration)', () => {
  test('summary reports texture binding', () => {
    const env = setTexture('blade_body', 'minecraft:block/iron_block')
    expect(env.changes.summary).toContain('bound texture')
  })

  test('failed assertion downgrades to WARNING with diagnostics', () => {
    const env = placePart('blade', {
      kind: 'cuboid',
      size: [4, 4, 4],
      assertions: [
        { kind: 'fits_within_voxel_envelope', width: 2, height: 2, depth: 2 },
      ],
    })
    expect(env.ok).toBe(true)
    expect(env.status).toBe('WARNING')
    expect(env.diagnostics).toBeDefined()
    expect(env.diagnostics![0]!.passed).toBe(false)
    expect(env.diagnostics![0]!.kind).toBe('fits_within_voxel_envelope')
    expect(env.hints.some((h) => h.code === 'ASSERTION_FAILED')).toBe(true)
  })

  test('passing assertion keeps OK status', () => {
    const env = placePart('blade', {
      kind: 'cuboid',
      size: [4, 4, 4],
      assertions: [
        { kind: 'fits_within_voxel_envelope', width: 32, height: 32, depth: 32 },
      ],
    })
    expect(env.ok).toBe(true)
    expect(env.status).toBe('OK')
    expect(env.diagnostics![0]!.passed).toBe(true)
  })

  test('no assertions → no diagnostics field on envelope', () => {
    const env = placePart('blade', { kind: 'cuboid', size: [4, 4, 4] })
    expect(env.diagnostics).toBeUndefined()
  })
})
