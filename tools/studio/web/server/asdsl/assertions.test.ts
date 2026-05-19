/**
 * Assertion runner unit tests (ADR-0062 B7).
 * Constructs ResolvedPart + compiled stub directly to exercise each kind.
 */
import { test, expect, describe } from 'bun:test'
import { runAssertions } from './assertions'
import type { ResolvedPart } from './anchor'
import type { Asdsl, Part, Vec3 } from './types'
import type { McModel } from '../../src/types'

function part(p: Part): Part {
  return p
}

function resolvedCuboid(name: string, origin: Vec3, size: Vec3): ResolvedPart {
  const [w, h, d] = size
  return {
    name,
    origin,
    extents: { min: [-w / 2, 0, -d / 2], max: [w / 2, h, d / 2] },
    bbox: {
      min: [origin[0] - w / 2, origin[1], origin[2] - d / 2],
      max: [origin[0] + w / 2, origin[1] + h, origin[2] + d / 2],
    },
    part: { kind: 'cuboid', size } as Part,
  }
}

function emptyAsdsl(parts: Record<string, Part>): Asdsl {
  return { name: 't', params: {}, parts, textures: {}, face_overrides: {} }
}

describe('aligned_with', () => {
  test('passes when two anchors are co-located', () => {
    const blade = resolvedCuboid('blade', [8, 0, 8], [4, 4, 4])
    const guard = resolvedCuboid('guard', [8, 4, 8], [4, 1, 4])
    const resolved = new Map([
      ['blade', blade],
      ['guard', guard],
    ])
    const asdsl = emptyAsdsl({
      blade: part({
        kind: 'cuboid',
        size: [4, 4, 4],
        assertions: [{ kind: 'aligned_with', a: 'blade.top_center', b: 'guard.base_center' }],
      }),
      guard: part({ kind: 'cuboid', size: [4, 1, 4] }),
    })
    const out = runAssertions(resolved, asdsl)
    expect(out).toHaveLength(1)
    expect(out[0]!.passed).toBe(true)
  })

  test('fails when anchors are far apart', () => {
    const a = resolvedCuboid('a', [0, 0, 0], [4, 4, 4])
    const b = resolvedCuboid('b', [20, 0, 0], [4, 4, 4])
    const resolved = new Map([
      ['a', a],
      ['b', b],
    ])
    const asdsl = emptyAsdsl({
      a: part({
        kind: 'cuboid',
        size: [4, 4, 4],
        assertions: [{ kind: 'aligned_with', a: 'a.center', b: 'b.center' }],
      }),
      b: part({ kind: 'cuboid', size: [4, 4, 4] }),
    })
    const out = runAssertions(resolved, asdsl)
    expect(out[0]!.passed).toBe(false)
    expect(out[0]!.detail).toContain('apart')
  })

  test('parser handles @cad[name].slot syntax', () => {
    const a = resolvedCuboid('blade', [0, 0, 0], [4, 4, 4])
    const b = resolvedCuboid('guard', [0, 4, 0], [4, 1, 4])
    const resolved = new Map([
      ['blade', a],
      ['guard', b],
    ])
    const asdsl = emptyAsdsl({
      blade: part({
        kind: 'cuboid',
        size: [4, 4, 4],
        assertions: [
          { kind: 'aligned_with', a: '@cad[blade].top_center', b: 'guard.base_center' },
        ],
      }),
      guard: part({ kind: 'cuboid', size: [4, 1, 4] }),
    })
    const out = runAssertions(resolved, asdsl)
    expect(out[0]!.passed).toBe(true)
  })
})

describe('fits_within_voxel_envelope', () => {
  test('passes when bbox is within envelope', () => {
    const rp = resolvedCuboid('blade', [8, 0, 8], [4, 4, 4])
    const resolved = new Map([['blade', rp]])
    const asdsl = emptyAsdsl({
      blade: part({
        kind: 'cuboid',
        size: [4, 4, 4],
        assertions: [{ kind: 'fits_within_voxel_envelope', width: 16, height: 16, depth: 16 }],
      }),
    })
    const out = runAssertions(resolved, asdsl)
    expect(out[0]!.passed).toBe(true)
  })

  test('fails when bbox exceeds height', () => {
    const rp = resolvedCuboid('blade', [8, 0, 8], [4, 32, 4])
    const resolved = new Map([['blade', rp]])
    const asdsl = emptyAsdsl({
      blade: part({
        kind: 'cuboid',
        size: [4, 32, 4],
        assertions: [{ kind: 'fits_within_voxel_envelope', width: 16, height: 16, depth: 16 }],
      }),
    })
    const out = runAssertions(resolved, asdsl)
    expect(out[0]!.passed).toBe(false)
    expect(out[0]!.detail).toContain('bbox.max[y]')
  })
})

describe('clearance_min', () => {
  test('passes when parts are sufficiently separated', () => {
    const a = resolvedCuboid('a', [0, 0, 0], [4, 4, 4])
    const b = resolvedCuboid('b', [10, 0, 0], [4, 4, 4])
    const resolved = new Map([
      ['a', a],
      ['b', b],
    ])
    const asdsl = emptyAsdsl({
      a: part({
        kind: 'cuboid',
        size: [4, 4, 4],
        assertions: [{ kind: 'clearance_min', from: 'a', to: 'b', voxels: 2 }],
      }),
      b: part({ kind: 'cuboid', size: [4, 4, 4] }),
    })
    const out = runAssertions(resolved, asdsl)
    expect(out[0]!.passed).toBe(true)
  })

  test('fails when parts overlap or touch', () => {
    const a = resolvedCuboid('a', [0, 0, 0], [4, 4, 4])
    const b = resolvedCuboid('b', [3, 0, 0], [4, 4, 4])
    const resolved = new Map([
      ['a', a],
      ['b', b],
    ])
    const asdsl = emptyAsdsl({
      a: part({
        kind: 'cuboid',
        size: [4, 4, 4],
        assertions: [{ kind: 'clearance_min', from: 'a', to: 'b', voxels: 2 }],
      }),
      b: part({ kind: 'cuboid', size: [4, 4, 4] }),
    })
    const out = runAssertions(resolved, asdsl)
    expect(out[0]!.passed).toBe(false)
  })
})

describe('no_self_intersection', () => {
  test('passes for single-cuboid part', () => {
    const rp = resolvedCuboid('blade', [0, 0, 0], [4, 4, 4])
    const resolved = new Map([['blade', rp]])
    const asdsl = emptyAsdsl({
      blade: part({
        kind: 'cuboid',
        size: [4, 4, 4],
        assertions: [{ kind: 'no_self_intersection' }],
      }),
    })
    const fakeModel: McModel = {
      parent: 'item/handheld',
      textures: {},
      elements: [{ from: [0, 0, 0], to: [4, 4, 4], faces: {} }],
    }
    const out = runAssertions(resolved, asdsl, {
      model: fakeModel,
      per_part_indices: { blade: [0] },
    })
    expect(out[0]!.passed).toBe(true)
  })

  test('fails when two cuboids of the same part overlap by volume', () => {
    const rp = resolvedCuboid('blade', [0, 0, 0], [4, 4, 4])
    const resolved = new Map([['blade', rp]])
    const asdsl = emptyAsdsl({
      blade: part({
        kind: 'cuboid',
        size: [4, 4, 4],
        assertions: [{ kind: 'no_self_intersection' }],
      }),
    })
    const fakeModel: McModel = {
      parent: 'item/handheld',
      textures: {},
      elements: [
        { from: [0, 0, 0], to: [4, 4, 4], faces: {} },
        { from: [2, 2, 2], to: [6, 6, 6], faces: {} },
      ],
    }
    const out = runAssertions(resolved, asdsl, {
      model: fakeModel,
      per_part_indices: { blade: [0, 1] },
    })
    expect(out[0]!.passed).toBe(false)
    expect(out[0]!.detail).toContain('overlap')
  })

  test('edge-touching cuboids do not count as overlap', () => {
    const rp = resolvedCuboid('blade', [0, 0, 0], [4, 4, 4])
    const resolved = new Map([['blade', rp]])
    const asdsl = emptyAsdsl({
      blade: part({
        kind: 'cuboid',
        size: [4, 4, 4],
        assertions: [{ kind: 'no_self_intersection' }],
      }),
    })
    const fakeModel: McModel = {
      parent: 'item/handheld',
      textures: {},
      elements: [
        { from: [0, 0, 0], to: [4, 4, 4], faces: {} },
        { from: [4, 0, 0], to: [8, 4, 4], faces: {} },
      ],
    }
    const out = runAssertions(resolved, asdsl, {
      model: fakeModel,
      per_part_indices: { blade: [0, 1] },
    })
    expect(out[0]!.passed).toBe(true)
  })
})
