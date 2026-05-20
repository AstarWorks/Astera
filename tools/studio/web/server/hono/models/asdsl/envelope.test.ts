/**
 * Envelope factory + HINTS unit tests (ADR-0062 B8 + B6 + B7).
 */
import { test, expect, describe } from 'bun:test'
import { HINTS, ok, info, warn, err, unknown } from './envelope'

describe('envelope status factories', () => {
  test('ok() returns OK', () => {
    const e = ok({ a: 1 })
    expect(e.ok).toBe(true)
    expect(e.status).toBe('OK')
    expect(e.data).toEqual({ a: 1 })
  })

  test('info() returns INFO with hints', () => {
    const e = info({ a: 1 }, [{ text: 'hi' }])
    expect(e.ok).toBe(true)
    expect(e.status).toBe('INFO')
    expect(e.hints).toHaveLength(1)
  })

  test('warn() returns WARNING with hints', () => {
    const e = warn({ a: 1 }, [{ text: 'soft fail' }])
    expect(e.ok).toBe(true)
    expect(e.status).toBe('WARNING')
  })

  test('err() returns ERROR with error_code', () => {
    const e = err('SOME_ERR', [])
    expect(e.ok).toBe(false)
    expect(e.status).toBe('ERROR')
    expect(e.error_code).toBe('SOME_ERR')
  })

  test('unknown() returns UNKNOWN', () => {
    const e = unknown('NO_META', [])
    expect(e.ok).toBe(false)
    expect(e.status).toBe('UNKNOWN')
    expect(e.error_code).toBe('NO_META')
  })
})

describe('HINTS factories', () => {
  test('ALREADY_EXISTS includes part name', () => {
    const h = HINTS.ALREADY_EXISTS('blade')
    expect(h.code).toBe('ALREADY_EXISTS')
    expect(h.text).toContain('blade')
    expect(h.suggestion).toEqual({ use: 'patch_part', name: 'blade' })
  })

  test('ASSERTION_FAILED includes detail', () => {
    const h = HINTS.ASSERTION_FAILED('aligned_with', 'guard', '0.7 apart')
    expect(h.code).toBe('ASSERTION_FAILED')
    expect(h.text).toContain('aligned_with')
    expect(h.text).toContain('guard')
    expect(h.text).toContain('0.7 apart')
  })

  test('ASSERTION_FAILED works without detail', () => {
    const h = HINTS.ASSERTION_FAILED('no_self_intersection', 'blade')
    expect(h.text).not.toContain('undefined')
  })
})
